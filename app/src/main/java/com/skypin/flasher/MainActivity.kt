package com.skypin.flasher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

private const val ACTION_USB_PERMISSION = "com.skypin.flasher.USB_PERMISSION"

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var log: TextView

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                readDevice(device)
            } else {
                append("Izin USB ditolak.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 32, 32, 32)

        val button = Button(this)
        button.text = "Cari HP target (mode fastboot)"
        button.setOnClickListener { cariTarget() }
        root.addView(button)

        val scroll = ScrollView(this)
        log = TextView(this)
        log.textSize = 16f
        scroll.addView(log)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }

        append("Flasher v0.1 siap.\nSambungkan HP target lewat OTG dalam mode fastboot, lalu tekan tombol di atas.")
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun append(text: String) {
        runOnUiThread { log.append(text + "\n\n") }
    }

    private fun cariTarget() {
        val target = usb.deviceList.values.firstOrNull { findFastbootIface(it) != null }
        if (target == null) {
            append("Tidak ada HP mode fastboot yang terdeteksi.\nCek kabel OTG dan pastikan HP target dalam mode fastboot.")
            return
        }
        if (usb.hasPermission(target)) {
            readDevice(target)
        } else {
            append("Meminta izin USB... pilih Izinkan di layar.")
            val pi = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(target, pi)
        }
    }

    private fun findFastbootIface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val iface = d.getInterface(i)
            if (iface.interfaceClass == 0xFF &&
                iface.interfaceSubclass == 0x42 &&
                iface.interfaceProtocol == 0x03
            ) {
                return iface
            }
        }
        return null
    }

    private fun readDevice(device: UsbDevice) {
        thread {
            var fb: Fastboot? = null
            try {
                val conn = usb.openDevice(device)
                    ?: throw IllegalStateException("Gagal membuka perangkat")
                val iface = findFastbootIface(device)
                    ?: throw IllegalStateException("Antarmuka fastboot tidak ditemukan")
                fb = Fastboot(conn, iface)
                val product = fb.command("getvar:product").trim()
                val serial = fb.command("getvar:serialno").trim()
                val info = try {
                    fb.command("oem device-info").trim()
                } catch (e: Exception) {
                    "tidak tersedia (${e.message})"
                }
                append(
                    "HASIL BACA (tidak ada perubahan di HP):\n" +
                        "Codename: $product\n" +
                        "Serial: $serial\n" +
                        "Bootloader:\n$info"
                )
            } catch (e: Exception) {
                append("Error: ${e.message}")
            } finally {
                fb?.close()
            }
        }
    }
}

class Fastboot(private val conn: UsbDeviceConnection, private val iface: UsbInterface) {
    private val inEp: UsbEndpoint
    private val outEp: UsbEndpoint

    init {
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (k in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(k)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
            }
        }
        inEp = i ?: throw IllegalStateException("Endpoint masuk tidak ditemukan")
        outEp = o ?: throw IllegalStateException("Endpoint keluar tidak ditemukan")
        if (!conn.claimInterface(iface, true)) {
            throw IllegalStateException("Gagal mengklaim antarmuka USB")
        }
    }

    fun close() {
        conn.releaseInterface(iface)
        conn.close()
    }

    fun command(cmd: String, timeoutMs: Int = 5000): String {
        val out = cmd.toByteArray(StandardCharsets.UTF_8)
        if (conn.bulkTransfer(outEp, out, out.size, timeoutMs) < 0) {
            throw IllegalStateException("Gagal mengirim perintah: $cmd")
        }
        val info = StringBuilder()
        while (true) {
            val buf = ByteArray(64)
            val n = conn.bulkTransfer(inEp, buf, buf.size, timeoutMs)
            if (n < 0) throw IllegalStateException("Tidak ada balasan untuk: $cmd")
            if (n == 0) continue
            val resp = String(buf, 0, n, StandardCharsets.UTF_8)
            when {
                resp.startsWith("INFO") -> info.append(resp.substring(4)).append('\n')
                resp.startsWith("OKAY") -> return info.toString() + resp.substring(4)
                resp.startsWith("FAIL") -> throw IllegalStateException("FAIL: " + resp.substring(4))
                else -> throw IllegalStateException("Balasan tidak dikenal: $resp")
            }
        }
    }
}

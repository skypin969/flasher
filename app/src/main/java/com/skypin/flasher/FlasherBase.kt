package com.skypin.flasher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val ACTION_USB_PERMISSION = "com.skypin.flasher.USB_PERMISSION"
const val REQ_PICK_IMG = 1001
const val REQ_PICK_TREE = 1002
const val REQ_PICK_TGZ = 1003

/** Data perangkat hasil Cek HP target. Dipakai bersama oleh semua layar dalam satu aplikasi. */
object Session {
    @Volatile var target: DeviceInfo? = null
    @Volatile var unlocked: String = "belum dicek"
}

private val DEVICE_NAMES = mapOf(
    "spinel" to "Redmi Note 15 4G",
    "malachite" to "Poco X7 5G"
)

/** Nama pasar dari codename. Codename yang belum ada di tabel tampil apa adanya. */
fun marketName(codename: String): String = DEVICE_NAMES[codename] ?: codename

/** Kerangka bersama: izin USB, tugas latar, log, dan elemen tampilan. */
abstract class FlasherBase : Activity() {

    protected val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile protected var busy = false

    private var logView: TextView? = null
    private var logScroll: ScrollView? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log(if (granted) "Izin USB sudah diberikan. Tekan tombol tadi sekali lagi." else "Izin USB ditolak.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permReceiver) }
        worker.shutdown()
        super.onDestroy()
    }

    /** Area log di bagian bawah layar. Halaman tanpa log memakai notifikasi singkat. */
    protected fun makeLog(): ScrollView {
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        logView = tv
        logScroll = sv
        return sv
    }

    protected fun log(msg: String) {
        runOnUiThread {
            val tv = logView
            if (tv == null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } else {
                tv.append(msg + "\n")
                logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    protected fun alert(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }

    protected fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Ya") { _, _ -> onYes() }
            .setNegativeButton("Batal", null)
            .show()
    }

    protected fun runTask(name: String, task: () -> Unit) {
        if (busy) {
            log("Masih ada proses berjalan, tunggu sampai selesai.")
            return
        }
        busy = true
        log("== $name ==")
        worker.execute {
            try {
                task()
            } catch (e: FastbootException) {
                log("GAGAL: ${e.message}")
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    // ---------- USB fastboot ----------

    private fun findFastboot(usb: UsbManager): Pair<UsbDevice, UsbInterface>? {
        for (dev in usb.deviceList.values) {
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == 0x03) {
                    return Pair(dev, itf)
                }
            }
        }
        return null
    }

    protected fun <T> withFastboot(block: (Fastboot) -> T): T {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val (dev, iface) = findFastboot(usb)
            ?: throw FastbootException("Perangkat fastboot tidak ditemukan. Pastikan HP target masuk mode fastboot dan kabel OTG terpasang.")
        if (!usb.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
            throw FastbootException("Belum ada izin USB. Setujui izin yang muncul, lalu tekan tombol lagi.")
        }
        val conn = usb.openDevice(dev) ?: throw FastbootException("Gagal membuka perangkat USB")
        val fb = try {
            Fastboot(conn, iface)
        } catch (e: FastbootException) {
            conn.close()
            throw e
        }
        try {
            return block(fb)
        } finally {
            fb.close()
        }
    }

    /** Perangkat yang terhubung harus sama dengan hasil Cek HP target. */
    protected fun ensureSameDevice(now: DeviceInfo, expected: DeviceInfo) {
        if (now.product.isBlank() || now.serial.isBlank()) {
            throw FastbootException("Codename atau serial tidak terbaca. Flash diblokir.")
        }
        if (now.product != expected.product || now.serial != expected.serial) {
            throw FastbootException("Perangkat berbeda dari hasil Cek HP target. Flash diblokir. Tekan Cek HP target lagi di halaman utama.")
        }
    }

    /** Mengambil data target dari Session. Jika belum ada, tampilkan peringatan. */
    protected fun requireTarget(): DeviceInfo? {
        val t = Session.target
        if (t == null) {
            alert("Tekan Cek HP target di halaman utama dulu. Flash diblokir sebelum perangkat dicek.")
        }
        return t
    }

    // ---------- File ----------

    protected fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, req)
    }

    protected fun queryNameSize(uri: Uri): Pair<String, Long> {
        val cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "file"
                val size = if (c.isNull(1)) 0L else c.getLong(1)
                return Pair(name, size)
            }
        }
        return Pair("file", 0L)
    }

    protected fun readMagic(uri: Uri): String {
        val head = ByteArray(8)
        val n = contentResolver.openInputStream(uri)?.use { it.read(head) } ?: 0
        val text = if (n >= 8) String(head, 0, 8, Charsets.US_ASCII) else ""
        return when {
            n >= 4 && head[0] == 0x3A.toByte() && head[1] == 0xFF.toByte() &&
                head[2] == 0x26.toByte() && head[3] == 0xED.toByte() -> "sparse (format Android)"
            text == "ANDROID!" -> "boot image"
            text == "VNDRBOOT" -> "vendor_boot image"
            else -> "raw / tidak dikenal"
        }
    }

    // ---------- Elemen tampilan ----------

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Kotak dengan garis tepi tipis dan sudut membulat. Warna teks mengikuti tema HP. */
    protected fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0x33888888)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    protected fun textLine(s: String = "", size: Float = 16f): TextView = TextView(this).apply {
        text = s
        textSize = size
    }

    protected fun caption(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        alpha = 0.7f
        setPadding(0, dp(6), 0, 0)
    }

    protected fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    /** Bagian atas untuk isi halaman, bagian bawah untuk log. */
    protected fun splitScreen(top: View, bottom: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f))
        addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))
    }
}

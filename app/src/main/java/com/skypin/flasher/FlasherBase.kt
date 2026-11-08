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
        runCatching

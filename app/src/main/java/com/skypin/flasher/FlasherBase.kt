package com.skypin.flasher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val ACTION_USB_PERMISSION = "com.skypin.flasher.USB_PERMISSION"
const val REQ_PICK_IMG = 1001
const val REQ_PICK_TREE = 1002
const val REQ_PICK_TGZ = 1003
const val REQ_PICK_ZIP = 1004

/** Tinggi log di halaman yang memakai [logPage]. Sama dengan log ADB SIDELOAD. */
private const val LOG_HEIGHT_DP = 188

/** Data bersama: hasil CEK FASTBOOT dan bahasa. */
object Session {
    @Volatile var target: DeviceInfo? = null
    /** "yes", "no", "unknown", atau teks mentah dari perangkat. Kosong jika belum dicek. */
    @Volatile var unlocked: String = ""
    /** "id" atau "en". */
    @Volatile var lang: String = "id"
    private var loaded = false

    /** Membaca setelan tersimpan. Jika belum ada, bahasa mengikuti HP. */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences("flasher", Context.MODE_PRIVATE)
        val sysLang = Locale.getDefault().language
        lang = p.getString("lang", null) ?: if (sysLang == "in" || sysLang == "id") "id" else "en"
        loaded = true
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("flasher", Context.MODE_PRIVATE).edit()
            .putString("lang", lang)
            .apply()
    }
}

/**
 * Font Exo 2 (400, 500, 600, 700) dan JetBrains Mono dari assets/fonts. Jika file belum ada, pakai font bawaan HP.
 * Ketebalan dipilih menurut peran tulisan, lihat [textLine], [caption], dan [btn].
 */
object Fonts {
    lateinit var regular: Typeface   // 400: keterangan dan isi
    lateinit var medium: Typeface    // 500: tombol kedua
    lateinit var semibold: Typeface  // 600: judul bagian, label kecil, tombol utama
    lateinit var bold: Typeface      // 700: judul halaman dan angka besar
    lateinit var mono: Typeface      // JetBrains Mono: log dan teks gaya terminal
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        regular = fromAsset(ctx, "fonts/exo2_regular.ttf") ?: Typeface.SANS_SERIF
        medium = fromAsset(ctx, "fonts/exo2_medium.ttf")
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        semibold = fromAsset(ctx, "fonts/exo2_semibold.ttf")
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        bold = fromAsset(ctx, "fonts/exo2_bold.ttf")
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        mono = fromAsset(ctx, "fonts/jetbrains_mono_regular.ttf") ?: Typeface.MONOSPACE
        loaded = true
    }

    private fun fromAsset(ctx: Context, path: String): Typeface? =
        try {
            Typeface.createFromAsset(ctx.assets, path)
        } catch (e: Exception) {
            null
        }
}

/** Warna aplikasi. Nilainya diambil dari NeonTheme (latar gradasi icon, aksen neon dari √-). */
class Palette(
    val bg: Int,
    val card: Int,
    val sub: Int,
    val text: Int,
    val sec: Int,
    val muted: Int,
    val border: Int,
    val accent: Int,
    val onAccent: Int,
    val soft: Int,
    val ok: Int,
    val okText: Int,
    val warnBg: Int,
    val warnText: Int,
    val danger: Int,
    val onDanger: Int,
    val tgSoft: Int,
    val tgIcon: Int,
    val ghSoft: Int,
    val ghIcon: Int
)

private val DARK = Palette(
    bg = NeonTheme.BG_START,
    card = NeonTheme.CARD,
    sub = NeonTheme.SUB,
    text = NeonTheme.TEXT,
    sec = NeonTheme.SEC,
    muted = NeonTheme.MUTED,
    border = NeonTheme.BORDER,
    accent = NeonTheme.CYAN,
    onAccent = NeonTheme.ON_ACCENT,
    soft = NeonTheme.SUB,
    ok = NeonTheme.SUB,
    okText = NeonTheme.CYAN,
    warnBg = NeonTheme.WARN_BG,
    warnText = NeonTheme.WARN_TEXT,
    danger = NeonTheme.DANGER,
    onDanger = NeonTheme.ON_DANGER,
    tgSoft = NeonTheme.SUB,
    tgIcon = NeonTheme.MAGENTA,
    ghSoft = NeonTheme.SUB,
    ghIcon = NeonTheme.MAGENTA
)

/** Palet untuk mode yang sedang aktif. */
fun pal(): Palette = DARK

/** Teks Indonesia atau Inggris, mengikuti setelan bahasa. */
fun tr(id: String, en: String): String = if (Session.lang == "en") en else id

private val DEVICE_NAMES = mapOf(
    "spinel" to "Redmi Note 15 4G",
    "malachite" to "Poco X7 5G"
)

/** Nama pasar dari codename. Codename yang belum ada di tabel tampil apa adanya. */
fun marketName(codename: String): String = DEVICE_NAMES[codename] ?: codename

/** Panah kiri yang digambar sendiri, supaya pas di tengah dan ukurannya jelas. */
class BackArrowDrawable(private val strokePx: Float, arrowColor: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arrowColor
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val r = bounds
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val s = minOf(r.width(), r.height()) / 2f * 0.72f
        val arm = s * 0.9f
        canvas.drawLine(cx - s, cy, cx + s, cy, paint)
        canvas.drawLine(cx - s, cy, cx - s + arm, cy - arm, paint)
        canvas.drawLine(cx - s, cy, cx - s + arm, cy + arm, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Gaya tombol: utama, sekunder, dan bahaya. */
enum class BtnStyle { PRIMARY, SECONDARY, DANGER }

/** Dilempar saat menunggu izin USB. Bukan kegagalan, jadi tidak ditampilkan sebagai GAGAL. */
class PermissionPendingException : RuntimeException()

/** Kerangka bersama: izin USB, tugas latar, log, dan elemen tampilan. */
abstract class FlasherBase : Activity() {

    protected val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile protected var busy = false

    private var logView: TextView? = null
    private var logScroll: ScrollView? = null

    /** Tugas yang dijalankan sendiri begitu izin USB diberikan, misalnya CEK FASTBOOT. */
    @Volatile private var pendingRetry: (() -> Unit)? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val retry = pendingRetry
            pendingRetry = null
            when {
                !granted -> log(tr("Izin USB ditolak.", "USB permission denied."))
                retry != null -> retry()
                else -> log(
                    tr("Izin USB sudah diberikan. Tekan tombol lagi untuk melanjutkan.",
                        "USB permission granted. Press the button again to continue.")
                )
            }
        }
    }

    /** Colokan fastboot baru dan izinnya belum ada: minta izin, lalu lanjutkan sendiri setelah Oke. */
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val (dev, _) = findFastboot(usb) ?: return
            if (usb.hasPermission(dev)) return
            pendingRetry = { onFastbootAttached() }
            requestUsbPermission(usb, dev)
        }
    }

    /** Halaman yang ingin bereaksi sendiri saat colokan fastboot baru diberi izin. */
    protected open val autoCheckOnAttach: Boolean get() = false

    /** Dipanggil setelah izin untuk colokan baru diberikan. */
    protected open fun onFastbootAttached() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        Fonts.load(this)
        applyWindow()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permReceiver, filter)
        }
        if (autoCheckOnAttach) {
            val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(attachReceiver, attachFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(attachReceiver, attachFilter)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permReceiver) }
        runCatching { unregisterReceiver(attachReceiver) }
        worker.shutdown()
        super.onDestroy()
    }

    /** Warna bar status dan navigasi mengikuti latar aplikasi. */
    protected fun applyWindow() {
        val p = pal()
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        window.decorView.systemUiVisibility = 0
    }

    /**
     * Area log. Mode biasa bisa dibuka dan ditutup, tingginya 150 dp.
     * Mode [fill] mengisi sisa tinggi layar dan selalu terbuka. Pemanggil harus memberi layout weight 1 pada hasilnya.
     * Halaman tanpa log memakai notifikasi singkat.
     */
    protected fun makeLog(fill: Boolean = false): LinearLayout {
        val p = pal()
        val tv = textLine("", if (fill) 13f else 12f, color = p.text, mono = true).apply { setTextIsSelectable(true) }
        val sv = ScrollView(this).apply {
            addView(tv)
            layoutParams = if (fill) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
            }
        }
        logView = tv
        logScroll = sv
        val title = textLine(if (fill) "Log" else "Log  ▼", 12f, medium = true, color = p.accent)
        var open = true
        if (!fill) {
            title.setOnClickListener {
                open = !open
                sv.visibility = if (open) View.VISIBLE else View.GONE
                title.text = if (open) "Log  ▼" else "Log  ▶"
            }
        }
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 12, p.border)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        section.addView(title)
        section.addView(sv)
        return section
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

    /** Baris status yang diganti di tempat, misalnya persentase kirim dan penghitung waktu. Selalu berada di bawah log. */
    private var statusText: String? = null

    protected fun logStatus(msg: String) {
        runOnUiThread {
            val tv = logView
            if (tv == null) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                val full = tv.text.toString()
                val old = statusText
                val base = if (old != null && full.endsWith(old + "\n")) full.removeSuffix(old + "\n") else full
                tv.text = base + msg + "\n"
                statusText = msg
                logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    /** Berhenti mengganti baris status. Baris yang terakhir tampil tetap ada di log. */
    protected fun clearStatus() {
        runOnUiThread { statusText = null }
    }

    protected fun alert(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }

    protected fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(tr("Ya", "Yes")) { _, _ -> onYes() }
            .setNegativeButton(tr("Batal", "Cancel"), null)
            .show()
    }

    protected fun runTask(name: String, task: () -> Unit) {
        if (busy) {
            log(tr("Masih ada proses berjalan, tunggu sampai selesai.", "Another task is still running. Please wait."))
            return
        }
        busy = true
        log("== $name ==")
        // Penanda untuk modul SAVER: flash sedang berjalan, jangan kunci layar atau hentikan proses
        val marker = java.io.File(filesDir, "busy")
        runCatching { marker.createNewFile() }
        worker.execute {
            try {
                task()
            } catch (e: FastbootException) {
                log(tr("GAGAL: ", "FAILED: ") + e.message)
            } catch (e: AdbException) {
                log(tr("GAGAL: ", "FAILED: ") + e.message)
            } catch (e: PermissionPendingException) {
                log(tr("Menunggu izin USB. Setujui popup yang muncul.", "Waiting for USB permission. Approve the popup."))
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
                runCatching { marker.delete() }
            }
        }
    }

    // ---------- USB fastboot dan ADB ----------

    /** Cari interface USB dengan kelas 0xFF, subclass 0x42, dan protokol [protocol]. */
    private fun findInterface(usb: UsbManager, protocol: Int): Pair<UsbDevice, UsbInterface>? {
        for (dev in usb.deviceList.values) {
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == protocol) {
                    return Pair(dev, itf)
                }
            }
        }
        return null
    }

    /** Interface fastboot memakai protokol 0x03. */
    private fun findFastboot(usb: UsbManager) = findInterface(usb, 0x03)

    /** Interface ADB sideload memakai protokol 0x01. */
    private fun findAdb(usb: UsbManager) = findInterface(usb, 0x01)

    /** Daftar semua perangkat USB dan interface-nya, untuk diagnosis saat perangkat ADB tidak ditemukan. */
    private fun usbDump(usb: UsbManager): String {
        val devices = usb.deviceList.values
        if (devices.isEmpty()) return tr("(tidak ada perangkat USB yang terbaca)", "(no USB device readable)")
        return devices.joinToString("\n") { dev ->
            val head = "- ${dev.productName ?: tr("tanpa nama", "no name")} vid=%04x pid=%04x".format(dev.vendorId, dev.productId)
            val ifs = (0 until dev.interfaceCount).joinToString("") { i ->
                val itf = dev.getInterface(i)
                "\n    interface $i: class=%02x sub=%02x proto=%02x".format(
                    itf.interfaceClass, itf.interfaceSubclass, itf.interfaceProtocol
                )
            }
            head + ifs
        }
    }

    private fun requestUsbPermission(usb: UsbManager, dev: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE
        )
        usb.requestPermission(dev, pi)
    }

    /** Menjalankan [block] dengan koneksi fastboot. Jika izin USB belum ada, [retryOnPermission] dijalankan sendiri setelah Oke. */
    protected fun <T> withFastboot(retryOnPermission: (() -> Unit)? = null, block: (Fastboot) -> T): T {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val (dev, iface) = findFastboot(usb)
            ?: throw FastbootException(
                tr(
                    "Perangkat fastboot tidak ditemukan. Pastikan HP target masuk mode fastboot dan kabel OTG terpasang.",
                    "Fastboot device not found. Make sure the target phone is in fastboot mode and the OTG cable is connected."
                )
            )
        if (!usb.hasPermission(dev)) {
            pendingRetry = retryOnPermission
            requestUsbPermission(usb, dev)
            if (retryOnPermission != null) throw PermissionPendingException()
            throw FastbootException(
                tr(
                    "Belum ada izin USB. Setujui izin yang muncul, lalu tekan tombol lagi.",
                    "USB permission is not granted yet. Approve the prompt, then press the button again."
                )
            )
        }
        val conn = usb.openDevice(dev) ?: throw FastbootException(tr("Gagal membuka perangkat USB", "Failed to open USB device"))
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

    /** Menjalankan [block] dengan koneksi ADB sideload. Izin USB ditangani sama seperti [withFastboot]. */
    protected fun <T> withAdb(retryOnPermission: (() -> Unit)? = null, block: (AdbSideload) -> T): T {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val (dev, iface) = findAdb(usb)
            ?: throw AdbException(
                tr(
                    "Perangkat ADB tidak ditemukan. Pastikan HP target sudah di recovery dan memilih Apply from ADB, serta kabel OTG terpasang.",
                    "ADB device not found. Make sure the target phone is in recovery with Apply from ADB selected, and the OTG cable is connected."
                ) + "\n" + tr("Perangkat USB yang terbaca:", "USB devices seen:") + "\n" + usbDump(usb)
            )
        if (!usb.hasPermission(dev)) {
            pendingRetry = retryOnPermission
            requestUsbPermission(usb, dev)
            if (retryOnPermission != null) throw PermissionPendingException()
            throw AdbException(
                tr(
                    "Belum ada izin USB. Setujui izin yang muncul, lalu tekan tombol lagi.",
                    "USB permission is not granted yet. Approve the prompt, then press the button again."
                )
            )
        }
        val conn = usb.openDevice(dev) ?: throw AdbException(tr("Gagal membuka perangkat USB", "Failed to open USB device"))
        val adb = try {
            AdbSideload(conn, iface)
        } catch (e: AdbException) {
            conn.close()
            throw e
        }
        try {
            return block(adb)
        } finally {
            adb.close()
        }
    }

    /**
     * Nama produk perangkat ADB dari USB.
     * Null jika tidak ada perangkat ADB. Kosong jika perangkat ada tapi namanya tidak terbaca.
     */
    protected fun adbDeviceName(): String? {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val (dev, _) = findAdb(usb) ?: return null
        return dev.productName?.trim().orEmpty()
    }

    /** Perangkat yang terhubung harus sama dengan hasil CEK FASTBOOT. */
    protected fun ensureSameDevice(now: DeviceInfo, expected: DeviceInfo) {
        if (now.product.isBlank() || now.serial.isBlank()) {
            throw FastbootException(tr("Codename atau serial tidak terbaca. Flash diblokir.", "Codename or serial could not be read. Flashing is blocked."))
        }
        if (now.product != expected.product || now.serial != expected.serial) {
            throw FastbootException(
                tr(
                    "Perangkat berbeda dari hasil CEK FASTBOOT. Flash diblokir. Tekan CEK FASTBOOT lagi di halaman utama.",
                    "The device differs from the CHECK FASTBOOT DEVICE result. Flashing is blocked. Press CHECK FASTBOOT DEVICE again on the main page."
                )
            )
        }
    }

    /** Mengambil data target dari Session. Jika belum ada, tampilkan peringatan. */
    protected fun requireTarget(): DeviceInfo? {
        val t = Session.target
        if (t == null) {
            alert(
                tr(
                    "Tekan CEK FASTBOOT di halaman utama dulu. Flash diblokir sebelum perangkat dicek.",
                    "Press CHECK FASTBOOT DEVICE on the main page first. Flashing is blocked until the device is checked."
                )
            )
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
                head[2] == 0x26.toByte() && head[3] == 0xED.toByte() ->
                tr("sparse (format Android)", "sparse (Android format)")
            text == "ANDROID!" -> "boot image"
            text == "VNDRBOOT" -> "vendor_boot image"
            else -> tr("raw / tidak dikenal", "raw / unknown")
        }
    }

    // ---------- Elemen tampilan ----------

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun rounded(fill: Int, radiusDp: Int, stroke: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    /** Garis pinggir gradasi neon dengan isi [fill]. Dipakai kartu, baris menu, dan tombol garis neon. */
    protected fun neonBorder(radiusDp: Int, fill: Int): LayerDrawable {
        val outer = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN)
        ).apply { cornerRadius = dp(radiusDp).toFloat() }
        val inner = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp - 2).toFloat()
        }
        return LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
        }
    }

    /** Margin standar untuk elemen di dalam kolom vertikal. */
    protected fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    /** Kartu utama dengan sudut membulat. */
    protected fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(pal().card, 14, pal().border)
        layoutParams = lp(bottom = 10)
    }

    /** Kartu dengan garis neon, dipakai untuk kartu perangkat dan status. */
    protected fun neonCard(): LinearLayout {
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neonBorder(14, p.card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = lp(bottom = 16)
        }
    }

    /** Kartu kecil di dalam kartu utama. */
    protected fun subCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(10))
        background = rounded(pal().sub, 12, null)
        layoutParams = lp(top = 6)
    }

    protected fun textLine(
        s: String = "",
        size: Float = 15f,
        medium: Boolean = false,
        color: Int = pal().text,
        mono: Boolean = false
    ): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        // Teks mono tetap JetBrains Mono. Ukuran 20 sp ke atas dianggap judul halaman (700).
        typeface = when {
            mono -> Fonts.mono
            medium && size >= 20f -> Fonts.bold
            medium -> Fonts.semibold
            else -> Fonts.regular
        }
    }

    /** Label kecil di atas isian dan judul bagian kecil. Ketebalannya 600. */
    protected fun caption(s: String): TextView = textLine(s, 12f, color = pal().muted).apply {
        setPadding(0, dp(8), 0, dp(2))
        typeface = Fonts.semibold
    }

    protected fun btn(label: String, style: BtnStyle = BtnStyle.PRIMARY, onClick: () -> Unit): Button {
        val p = pal()
        val fill = when (style) {
            BtnStyle.PRIMARY -> p.accent
            BtnStyle.SECONDARY -> p.sub
            BtnStyle.DANGER -> p.danger
        }
        val fg = when (style) {
            BtnStyle.PRIMARY -> p.onAccent
            BtnStyle.SECONDARY -> p.text
            BtnStyle.DANGER -> p.onDanger
        }
        // Tombol utama memakai gradasi neon, tombol lain warna datar
        val bgDrawable = if (style == BtnStyle.PRIMARY) neonButtonBackground(this) else rounded(fill, 10, null)
        return Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 14f
            typeface = when (style) {
                BtnStyle.PRIMARY -> Fonts.semibold
                BtnStyle.SECONDARY -> Fonts.medium
                BtnStyle.DANGER -> Fonts.semibold
            }
            setTextColor(fg)
            background = bgDrawable
            stateListAnimator = null
            minimumHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
            layoutParams = lp(top = 8)
        }
    }

    /** Tombol garis neon dengan isi gelap, untuk aksi kedua di layar seperti PILIH FILE ZIP. */
    protected fun neonOutlineBtn(label: String, onClick: () -> Unit): Button {
        val p = pal()
        return Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 14f
            typeface = Fonts.medium
            setTextColor(p.text)
            background = neonBorder(10, p.card)
            stateListAnimator = null
            minimumHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
            layoutParams = lp(top = 8)
        }
    }

    /** Tombol kecil berbentuk kapsul, untuk pilihan cepat nama partisi. */
    protected fun chip(label: String, onClick: () -> Unit): TextView = textLine(label, 13f).apply {
        typeface = Fonts.medium
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = rounded(pal().sub, 999, pal().border)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }
    }

    /** Tombol kembali: tanda "<" diikuti tulisan Kembali, tanpa kotak. */
    protected fun backButton(onClick: () -> Unit): LinearLayout {
        val p = pal()
        val arrow = textLine("<", 17f, medium = true, color = p.accent).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        }
        val label = textLine(tr("Kembali", "Back"), 15f, color = p.accent)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(4), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = lp(top = 8)
            addView(arrow)
            addView(label)
        }
    }

    /** Baris dengan lencana, judul, keterangan, dan panah. Dipakai untuk menu dan kontak. */
    protected fun rowCard(
        badge: String,
        title: String,
        sub: String,
        badgeFill: Int,
        badgeText: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val p = pal()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(p.card, 12, p.border)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = lp(bottom = 8)
        }
        row.addView(textLine(badge, 11f, medium = true, color = badgeText).apply {
            gravity = Gravity.CENTER
            background = rounded(badgeFill, 8, null)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(32))
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        col.addView(textLine(title, 14f, medium = true, color = p.text))
        col.addView(textLine(sub, 12f, color = p.muted))
        row.addView(col)
        row.addView(textLine("›", 20f, color = p.muted))
        return row
    }

    /** Baris label dan nilai di dalam [parent]. Mengembalikan TextView nilainya. */
    protected fun kvRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(textLine(label, 13f, color = pal().muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = textLine("", 13f, medium = true, mono = true)
        row.addView(value)
        parent.addView(row)
        return value
    }

    /**
     * Halaman dengan isi di atas dan log di bawah, sama seperti layar ADB SIDELOAD.
     * Log setinggi [LOG_HEIGHT_DP] dp dan berjarak 16 dp dari kiri, kanan, dan bawah layar.
     * Isi di atas bisa digulir jika lebih tinggi dari layar.
     */
    protected fun logPage(top: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = neonBackground()
        addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(
            makeLog(fill = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(LOG_HEIGHT_DP)).apply {
                topMargin = dp(8)
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(16)
            }
        )
    }

    /** Isi halaman di atas, log di bawah (bisa kosong). Latar memakai gradasi icon. */
    protected fun page(top: View, bottom: View?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = neonBackground()
        addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (bottom != null) {
            addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}

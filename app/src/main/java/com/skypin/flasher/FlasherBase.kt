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

/** Font Space Grotesk dan JetBrains Mono dari assets/fonts. Jika file belum ada, pakai font bawaan HP. */
object Fonts {
    lateinit var regular: Typeface
    lateinit var medium: Typeface
    lateinit var mono: Typeface
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        regular = fromAsset(ctx, "fonts/space_grotesk_regular.ttf") ?: Typeface.SANS_SERIF
        medium = fromAsset(ctx, "fonts/space_grotesk_medium.ttf")
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

    /** Area log di bawah layar. Bisa dibuka dan ditutup. Halaman tanpa log memakai notifikasi singkat. */
    protected fun makeLog(): LinearLayout {
        val p = pal()
        val tv = textLine("", 12f, color = p.text, mono = true).apply { setTextIsSelectable(true) }
        val sv = ScrollView(this).apply {
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        }
        logView = tv
        logScroll = sv
        val title = textLine("Log  ▼", 12f, medium = true, color = p.accent)
        var open = true
        title.setOnClickListener {
            open = !open
            sv.visibility = if (open) View.VISIBLE else View.GONE
            title.text = if (open) "Log  ▼" else "Log  ▶"
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
        worker.execute {
            try {
                task()
            } catch (e: FastbootException) {
                log(tr("GAGAL: ", "FAILED: ") + e.message)
            } catch (e: PermissionPendingException) {
                log(tr("Menunggu izin USB. Setujui popup yang muncul.", "Waiting for USB permission. Approve the popup."))
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
        typeface = if (mono) Fonts.mono else if (medium) Fonts.medium else Fonts.regular
    }

    protected fun caption(s: String): TextView = textLine(s, 12f, color = pal().muted).apply {
        setPadding(0, dp(8), 0, dp(2))
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
            typeface = if (style == BtnStyle.SECONDARY) Fonts.regular else Fonts.medium
            setTextColor(fg)
            background = bgDrawable
            stateListAnimator = null
            minimumHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
            layoutParams = lp(top = 8)
        }
    }

    /** Tombol kecil berbentuk kapsul, untuk pilihan cepat nama partisi. */
    protected fun chip(label: String, onClick: () -> Unit): TextView = textLine(label, 13f).apply {
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

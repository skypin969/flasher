package com.skypin.flasher

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.IOException

/** Halaman Flash fastboot ROM tgz: pilih paket, pilih script, buat rencana, lalu jalankan. */
class FlashRomActivity : FlasherBase() {

    @Volatile private var romSource: RomSource? = null
    @Volatile private var romName = ""
    @Volatile private var romScripts: List<String> = emptyList()
    @Volatile private var romScript: String? = null
    @Volatile private var plan: RomPlan? = null

    private lateinit var romInfo: TextView
    private lateinit var scriptBtn: TextView
    private lateinit var dryRunSwitch: NeonToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK_TREE -> loadRomFolder(uri)
            REQ_PICK_TGZ -> loadRomTgz(uri)
        }
    }

    private fun pickRomFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_TREE)
    }

    private fun loadRomFolder(tree: Uri) {
        runTask(tr("Baca folder ROM", "Read ROM folder")) {
            runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setRom(SafRomSource(contentResolver, tree), tree.lastPathSegment ?: "folder")
        }
    }

    private fun loadRomTgz(uri: Uri) {
        runTask(tr("Ekstrak ROM .tgz", "Extract ROM .tgz")) {
            val name = queryNameSize(uri).first
            val stem = name.removeSuffix(".tgz").removeSuffix(".tar.gz").removeSuffix(".tar").ifBlank { "rom" }
            val dest = File(getExternalFilesDir(null), "rom/$stem")
            dest.deleteRecursively()
            dest.mkdirs()
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException(tr("Tidak bisa membuka file .tgz", "Cannot open .tgz file"))
            input.use { TgzExtractor.extract(it, dest) { m -> log(m) } }
            setRom(FileRomSource(dest), name)
        }
    }

    private fun setRom(src: RomSource, name: String) {
        // Script flash_all_except_data_storage.sh tidak dipakai di aplikasi ini, jadi disembunyikan dari daftar
        val scripts = listScripts(src).filter { it != "flash_all_except_data_storage.sh" }
        romSource = src
        romName = name
        romScripts = scripts
        romScript = null
        plan = null
        val msg = if (scripts.isEmpty()) {
            tr("Paket: $name\nTidak ada script flash (flash_all.sh) di paket ini.",
                "Package: $name\nNo flash script (flash_all.sh) in this package.")
        } else {
            tr("Paket: $name\nScript ditemukan: ${scripts.joinToString(", ")}\nPilih script, lalu tekan Buat rencana.",
                "Package: $name\nScripts found: ${scripts.joinToString(", ")}\nChoose a script, then press Make plan.")
        }
        runOnUiThread {
            romInfo.text = msg
            scriptBtn.text = tr("PILIH SCRIPT", "CHOOSE SCRIPT")
            applyNeonText(scriptBtn)
        }
        log(msg.replace("\n", " | "))
    }

    /** Dialog pilih script: latar gelap, garis gradasi neon, pilihan tulisan putih. */
    private fun pickScript() {
        val scripts = romScripts
        if (scripts.isEmpty()) {
            alert(tr("Belum ada paket ROM, atau paket tidak punya script flash.",
                "No ROM package yet, or the package has no flash script."))
            return
        }
        val p = pal()
        val width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        val picker = Dialog(this)
        picker.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        picker.window?.setDimAmount(0.6f)
        picker.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neonFrame(p.card, 14)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        panel.addView(textLine(tr("PILIH SCRIPT", "CHOOSE SCRIPT"), 16f, medium = true, color = p.text))
        for (s in scripts) {
            panel.addView(textLine(s, 14f, color = Color.WHITE).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = neonFrame(p.card, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                setOnClickListener {
                    picker.dismiss()
                    romScript = s
                    plan = null
                    scriptBtn.text = s
                    applyNeonText(scriptBtn)
                    romInfo.text = tr("Paket: $romName\nScript: $s\nTekan Buat rencana.",
                        "Package: $romName\nScript: $s\nPress Make plan.")
                }
            })
        }
        picker.setContentView(panel)
        picker.show()
    }

    private fun onPlanRom() {
        val t = requireTarget() ?: return
        val src = romSource
        val script = romScript
        if (src == null) {
            alert(tr("Pilih paket ROM dulu.", "Choose a ROM package first."))
            return
        }
        if (script == null) {
            alert(tr("Pilih script dulu.", "Choose a script first."))
            return
        }
        runTask(tr("Buat rencana ROM", "Make ROM plan")) {
            val p = withFastboot { fb ->
                val now = readDevice(fb)
                ensureSameDevice(now, t)
                buildPlan(src, script, now, romName)
            }
            plan = p
            log(describePlan(p, src))
        }
    }

    private fun onRunRom() {
        val t = requireTarget() ?: return
        val src = romSource
        val p = plan
        if (src == null || p == null) {
            alert(tr("Buat rencana dulu: pilih paket, pilih script, lalu tekan Buat rencana.",
                "Make a plan first: choose a package, choose a script, then press Make plan."))
            return
        }
        if (p.errors.isNotEmpty()) {
            alert(tr("Rencana tidak bisa dijalankan:\n\n", "The plan cannot be run:\n\n") + p.errors.joinToString("\n"))
            return
        }
        val dry = dryRunSwitch.isChecked
        val msg = if (dry) {
            tr("Mode uji: tidak ada data yang dikirim ke perangkat. Lanjutkan?",
                "Test mode: no data is sent to the device. Continue?")
        } else {
            tr("PERINGATAN: flash asli akan menghapus semua data di ${t.product} (${t.serial}) " +
                "dan menimpa semua partisi yang sudah diubah. Lanjutkan?",
                "WARNING: real flash will erase all data on ${t.product} (${t.serial}) " +
                    "and overwrite every changed partition. Continue?")
        }
        confirm(msg) {
            runTask(if (dry) tr("Mode uji ROM", "ROM test mode") else tr("Flash ROM", "Flash ROM")) {
                withFastboot { fb ->
                    val now = readDevice(fb)
                    ensureSameDevice(now, t)
                    val fresh = buildPlan(src, p.script, now, romName)
                    if (fresh.errors.isNotEmpty()) throw FastbootException(fresh.errors.joinToString("; "))
                    runPlan(fb, src, fresh, dry) { m -> log(m) }
                }
            }
        }
    }

    /** Garis pinggir gradasi neon dengan isi gelap di dalamnya. */
    private fun neonFrame(fill: Int, radiusDp: Int): LayerDrawable {
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

    /** Tombol sekunder: isi gelap, garis pinggir dan tulisan bergradasi neon. */
    private fun neonOutline(label: String, action: () -> Unit): TextView {
        val p = pal()
        return textLine(label, 15f, medium = true, color = p.text).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = neonFrame(p.card, 10)
            setOnClickListener { action() }
            applyNeonText(this)
        }
    }

    private fun buildUi() {
        val p = pal()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        content.addView(backButton { finish() })
        content.addView(textLine(tr("FLASH FASTBOOT ROM", "FLASH FASTBOOT ROM"), 22f, medium = true).apply {
            paint.isFakeBoldText = true
            applyNeonText(this)
            setPadding(0, dp(8), 0, 0)
        })
        val tgt = Session.target
        content.addView(
            caption(
                if (tgt != null) "${marketName(tgt.product)} · ${tgt.product}"
                else tr("Target belum dicek", "Target not checked yet")
            )
        )

        val form = box()

        val step1 = subCard()
        step1.addView(caption(tr("sebelum pilih folder, ekstrak dulu file fastboot.tgz",
            "Before choosing a folder, extract fastboot.tgz first")))
        val bFolder = neonOutline(tr("PILIH FOLDER", "CHOOSE FOLDER")) { pickRomFolder() }
        bFolder.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        step1.addView(bFolder)
        romInfo = textLine(tr("belum ada paket rom dipilih", "No ROM package chosen yet"), 12f, color = p.muted, mono = true)
        romInfo.setPadding(0, dp(8), 0, 0)
        step1.addView(romInfo)
        form.addView(step1)

        val step2 = subCard()
        step2.addView(textLine(tr("PILIH SCRIPT FLASH", "CHOOSE FLASH SCRIPT"), 12f, medium = true, color = Color.WHITE).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        scriptBtn = neonOutline(tr("PILIH SCRIPT", "CHOOSE SCRIPT")) { pickScript() }
        scriptBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        step2.addView(scriptBtn)
        form.addView(step2)

        form.addView(btn(tr("BUAT RENCANA", "MAKE PLAN"), BtnStyle.PRIMARY) { onPlanRom() })

        // Mode uji: kartu sendiri, dengan saklar gradasi
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.card, 10, p.border)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = lp(top = 8)
        }
        val modeText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        modeText.addView(textLine(tr("Mode Uji", "Test Mode"), 14f, medium = true, color = p.text))
        modeText.addView(textLine(tr("tidak mengirim data", "sends no data"), 12f, color = p.muted))
        modeRow.addView(modeText)
        dryRunSwitch = NeonToggle(this).apply {
            isChecked = true
        }
        modeRow.addView(dryRunSwitch)
        form.addView(modeRow)

        val warn = box()
        warn.background = rounded(p.warnBg, 10, null)
        warn.addView(textLine(
            tr("saat proses flashing rom berjalan, layar tidak boleh sampai mati atau mengunci layar.",
                "While ROM flashing is running, the screen must not turn off or lock."),
            12f,
            color = p.warnText
        ))
        form.addView(warn)

        form.addView(btn(tr("JALANKAN", "RUN"), BtnStyle.PRIMARY) { onRunRom() })
        content.addView(form)

        setContentView(page(ScrollView(this).apply { addView(content) }, makeLog()))
    }
}

/** Saklar Mode uji yang digambar sendiri: jalur pill, bulatan selalu bulat sempurna. */
class NeonToggle(context: Context) : View(context) {

    var isChecked: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }

    init {
        isClickable = true
        setOnClickListener { isChecked = !isChecked }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((52 * density).toInt(), (28 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val cy = h / 2f
        val pad = 3f * density
        val knobR = r - pad
        val gradient = intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN)

        paint.shader = LinearGradient(0f, 0f, w, 0f, gradient, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        paint.shader = null

        if (isChecked) {
            paint.color = Color.WHITE
            val cx = w - pad - knobR
            canvas.drawCircle(cx, cy, knobR, paint)
            checkPaint.color = pal().onAccent
            val check = Path().apply {
                moveTo(cx - knobR * 0.35f, cy)
                lineTo(cx - knobR * 0.05f, cy + knobR * 0.3f)
                lineTo(cx + knobR * 0.4f, cy - knobR * 0.35f)
            }
            canvas.drawPath(check, checkPaint)
        } else {
            val ring = 1.5f * density
            paint.color = pal().card
            canvas.drawRoundRect(ring, ring, w - ring, h - ring, r - ring, r - ring, paint)
            paint.color = pal().muted
            canvas.drawCircle(pad + knobR, cy, knobR, paint)
        }
    }
}

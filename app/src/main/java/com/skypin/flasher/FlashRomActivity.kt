package com.skypin.flasher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Halaman Flash fastboot ROM: pilih folder paket ROM yang sudah diekstrak, script flash_all.sh dipilih otomatis,
 * isi file diperiksa otomatis, lalu jalankan.
 */
class FlashRomActivity : FlasherBase() {

    @Volatile private var romSource: RomSource? = null
    @Volatile private var romName = ""
    /** "flash_all.sh" jika ada di paket. Null jika tidak ada. */
    @Volatile private var romScript: String? = null
    /** True jika pemeriksaan menemukan file korup atau hilang. JALANKAN dimatikan. */
    @Volatile private var checkBlocked = false
    /** True setelah halaman ditutup, supaya pemeriksaan yang sedang berjalan berhenti. */
    @Volatile private var closing = false

    private lateinit var romInfo: TextView
    private lateinit var missingText: TextView
    private lateinit var scriptRow: LinearLayout
    private lateinit var runBtn: Button
    private lateinit var checkCard: LinearLayout
    private lateinit var progressBox: LinearLayout
    private lateinit var progressHead: TextView
    private lateinit var progressBar: LinearLayout
    private lateinit var progressFill: View
    private lateinit var progressRest: View
    private lateinit var progressDetail: TextView
    private lateinit var resultBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onDestroy() {
        closing = true
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK_TREE -> loadRomFolder(uri)
        }
    }

    private fun pickRomFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_TREE)
    }

    /** Baca folder, pilih script, lalu langsung periksa isi file. */
    private fun loadRomFolder(tree: Uri) {
        runTask(tr("Baca dan periksa folder ROM", "Read and check ROM folder")) {
            runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val src = SafRomSource(contentResolver, tree)
            setRom(src, tree.lastPathSegment ?: "folder")
            val script = romScript
            if (script != null) checkRom(src, script)
        }
    }

    /** Memilih flash_all.sh otomatis. JALANKAN tetap mati sampai pemeriksaan file selesai. */
    private fun setRom(src: RomSource, name: String) {
        // Script flash_all_except_data_storage.sh tidak dipakai di aplikasi ini, jadi disembunyikan
        val scripts = listScripts(src).filter { it != "flash_all_except_data_storage.sh" }
        val script = if ("flash_all.sh" in scripts) "flash_all.sh" else null
        romSource = src
        romName = name
        romScript = script
        checkBlocked = false
        val msg = if (script == null) {
            tr("Paket: $name\nTidak ada script flash (flash_all.sh) di paket ini.",
                "Package: $name\nNo flash script (flash_all.sh) in this package.")
        } else {
            tr("Paket: $name\nScript dipilih otomatis: flash_all.sh",
                "Package: $name\nScript chosen automatically: flash_all.sh")
        }
        runOnUiThread {
            romInfo.text = tr("Paket: $name", "Package: $name")
            missingText.visibility = if (script == null) View.VISIBLE else View.GONE
            scriptRow.visibility = if (script != null) View.VISIBLE else View.GONE
            setRunEnabled(false)
            if (script == null) {
                checkCard.visibility = View.GONE
            } else {
                checkCard.visibility = View.VISIBLE
                resultBox.visibility = View.GONE
                progressBox.visibility = View.VISIBLE
                progressHead.text = tr("memeriksa file", "checking files")
                progressDetail.text = ""
                setProgress(0)
            }
        }
        log(msg.replace("\n", " | "))
    }

    /** Memeriksa isi semua file paket dengan daftar pembanding dari paket itu sendiri. Dipanggil di dalam tugas latar. */
    private fun checkRom(src: RomSource, script: String) {
        log(tr("Periksa file ROM. Ini bisa memakan waktu beberapa menit.",
            "Checking ROM files. This can take a few minutes."))
        val result = try {
            verifyRom(src, script, { closing }, { pr -> runOnUiThread { showProgress(pr) } }) { m -> log(m) }
        } catch (e: CheckCancelled) {
            return
        }
        checkBlocked = result.blocked
        runOnUiThread { showResult(result) }
    }

    private fun setRunEnabled(on: Boolean) {
        runBtn.isEnabled = on
        runBtn.alpha = if (on) 1f else 0.4f
    }

    private fun setProgress(percent: Int) {
        val pct = percent.coerceIn(0, 100)
        (progressFill.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
        (progressRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
        progressBar.requestLayout()
    }

    private fun showProgress(pr: CheckProgress) {
        progressHead.text = tr("memeriksa ${pr.index} dari ${pr.total} file", "checking ${pr.index} of ${pr.total} files")
        progressDetail.text = tr("${pr.file} · ${human(pr.fileDone)} dari ${human(pr.fileSize)}",
            "${pr.file} · ${human(pr.fileDone)} of ${human(pr.fileSize)}")
        setProgress(if (pr.allTotal > 0L) (pr.allDone * 100L / pr.allTotal).toInt() else 0)
    }

    /** Satu baris hasil: titik warna, judul, keterangan, dan daftar nama file. */
    private fun resultRow(color: Int, head: String, sub: String?, names: List<String>): LinearLayout {
        val p = pal()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(View(this).apply {
            background = rounded(color, 4, null)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                topMargin = dp(5)
                marginEnd = dp(8)
            }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(textLine(head, 14f, medium = true, color = color))
        if (sub != null) col.addView(textLine(sub, 12f, color = p.muted))
        if (names.isNotEmpty()) {
            val shown = names.take(8).toMutableList()
            if (names.size > 8) shown.add(tr("dan ${names.size - 8} file lain", "and ${names.size - 8} more files"))
            col.addView(textLine(shown.joinToString("\n"), 12f, color = p.text, mono = true).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        row.addView(col)
        return row
    }

    /** Menampilkan hasil pemeriksaan. JALANKAN hanya menyala jika tidak ada file korup atau hilang. */
    private fun showResult(r: RomCheckResult) {
        val p = pal()
        val ok = r.of(FileVerdict.OK)
        val corrupt = r.of(FileVerdict.CORRUPT)
        val missing = r.of(FileVerdict.MISSING)
        val unknown = r.of(FileVerdict.UNKNOWN)
        val total = r.items.size
        val redo = tr("ekstrak ulang file .tgz, lalu pilih folder lagi", "extract the .tgz again, then choose the folder again")

        resultBox.removeAllViews()
        if (corrupt.isNotEmpty()) {
            resultBox.addView(resultRow(
                p.danger,
                tr("${corrupt.size} file korup", "${corrupt.size} corrupt files"),
                redo,
                corrupt.map { it.file }
            ))
        }
        if (missing.isNotEmpty()) {
            resultBox.addView(resultRow(
                p.danger,
                tr("${missing.size} file hilang", "${missing.size} missing files"),
                redo,
                missing.map { it.file }
            ))
        }
        if (ok.isNotEmpty()) {
            if (ok.size == total) {
                resultBox.addView(resultRow(
                    p.okText,
                    tr("$total dari $total file cocok", "$total of $total files match"),
                    tr("isi semua file sama dengan daftar dari paket", "all file contents match the package's list"),
                    emptyList()
                ))
            } else {
                resultBox.addView(resultRow(p.okText, tr("${ok.size} file cocok", "${ok.size} files match"), null, emptyList()))
            }
        }
        if (unknown.isNotEmpty()) {
            resultBox.addView(resultRow(
                p.warnText,
                tr("${unknown.size} file tidak bisa dipastikan", "${unknown.size} files could not be confirmed"),
                if (r.hasLists) tr("tidak ada di daftar pembanding paket ini", "not in this package's reference list")
                else tr("paket ini tidak punya daftar pembanding", "this package has no reference list"),
                unknown.map { it.file }
            ))
        }
        progressBox.visibility = View.GONE
        resultBox.visibility = View.VISIBLE
        setRunEnabled(romScript != null && !r.blocked)
    }

    /** Alur JALANKAN: cek perangkat, buat rencana dari flash_all.sh, tulis langkahnya ke log, lalu jalankan. */
    private fun onRunRom() {
        val t = requireTarget() ?: return
        val src = romSource
        val script = romScript
        if (src == null || script == null) {
            alert(tr("Pilih paket ROM yang berisi flash_all.sh dulu.",
                "Choose a ROM package that contains flash_all.sh first."))
            return
        }
        if (checkBlocked) {
            alert(tr("Ada file korup atau hilang di paket ini. Ekstrak ulang file .tgz, lalu pilih folder lagi.",
                "This package has corrupt or missing files. Extract the .tgz again, then choose the folder again."))
            return
        }
        val dry = FlashSettings.dryRun(this)
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
                    val p = buildPlan(src, script, now, romName)
                    if (p.errors.isNotEmpty()) throw FastbootException(p.errors.joinToString("; "))
                    log(describePlan(p, src))
                    runPlan(fb, src, p, dry) { m -> log(m) }
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

    /** Log setinggi 260 dp. Ukurannya sama dengan log di FLASH FILE IMG. */
    private fun logPanel(): LinearLayout = makeLog().also { section ->
        section.getChildAt(1).layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
    }

    private fun buildUi() {
        val p = pal()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        // Baris atas: Kembali di kiri, tombol Pengaturan (ikon sliders) di kanan
        val back = backButton { finish() }
        back.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(8)
        }
        val gear = FrameLayout(this).apply {
            background = rounded(p.card, 10, p.border)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@FlashRomActivity, FlashSettingsActivity::class.java))
            }
            addView(SlidersIcon(this@FlashRomActivity), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(gear, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(8) })
        }
        content.addView(topRow)

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

        // Setelah paket dipilih: flash_all.sh tampil sebagai pil, dengan keterangan dipilih otomatis
        scriptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        scriptRow.addView(textLine("flash_all.sh", 13f, medium = true, color = p.text).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = neonFrame(p.card, 14)
            applyNeonText(this)
        })
        scriptRow.addView(textLine(tr("dipilih otomatis", "chosen automatically"), 12f, color = p.muted).apply {
            setPadding(dp(8), 0, 0, 0)
        })
        step1.addView(scriptRow)

        // Jika flash_all.sh tidak ada di paket
        missingText = textLine(
            tr("Tidak ada script flash (flash_all.sh) di paket ini.",
                "No flash script (flash_all.sh) in this package."),
            12f,
            color = p.warnText
        ).apply {
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        step1.addView(missingText)
        form.addView(step1)

        // Kartu pemeriksaan file: tampil setelah folder dipilih
        checkCard = subCard()
        checkCard.visibility = View.GONE
        checkCard.addView(caption(tr("pemeriksaan file", "file check")))

        progressBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progressHead = textLine("", 14f, medium = true, color = p.text)
        progressBox.addView(progressHead)
        progressFill = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN)
            ).apply { cornerRadius = dp(3).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        }
        progressRest = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100f)
        }
        progressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.border, 3, null)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(8)
            }
            addView(progressFill)
            addView(progressRest)
        }
        progressBox.addView(progressBar)
        progressDetail = textLine("", 12f, color = p.muted, mono = true)
        progressDetail.setPadding(0, dp(6), 0, 0)
        progressBox.addView(progressDetail)
        checkCard.addView(progressBox)

        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        checkCard.addView(resultBox)
        form.addView(checkCard)
        content.addView(form)

        val warn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(p.warnBg, 10, null)
            layoutParams = lp(top = 8)
        }
        warn.addView(textLine(
            tr("saat proses flashing rom berjalan, layar tidak boleh sampai mati atau mengunci layar.",
                "While ROM flashing is running, the screen must not turn off or lock."),
            12f,
            color = p.warnText
        ))
        content.addView(warn)

        runBtn = btn(tr("JALANKAN", "RUN"), BtnStyle.PRIMARY) { onRunRom() }
        runBtn.paint.isFakeBoldText = true
        setRunEnabled(false)
        content.addView(runBtn)

        setContentView(page(ScrollView(this).apply { addView(content) }, logPanel()))
    }
}

/** Ikon tiga garis dengan bulatan (sliders), digambar sendiri. Dipakai sebagai tombol Pengaturan. */
class SlidersIcon(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NeonTheme.CYAN
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        // Koordinat dibuat untuk kotak 24 x 24, lalu diskalakan ke ukuran view
        val s = width / 24f
        knobPaint.color = pal().card
        val rows = listOf(Pair(6f, 9f), Pair(12f, 15f), Pair(18f, 8f))
        for ((y, kx) in rows) {
            canvas.drawLine(4f * s, y * s, 20f * s, y * s, linePaint)
            canvas.drawCircle(kx * s, y * s, 2.5f * s, knobPaint)
            canvas.drawCircle(kx * s, y * s, 2.5f * s, linePaint)
        }
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

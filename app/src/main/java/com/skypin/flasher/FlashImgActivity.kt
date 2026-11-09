package com.skypin.flasher

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException

/** Halaman FLASH FILE IMG: pilih file, tulis ke slot aktif, lalu tampil hasil dengan tombol flash file lain dan reboot. */
class FlashImgActivity : FlasherBase() {

    @Volatile private var imgUri: Uri? = null
    @Volatile private var imgName = ""
    @Volatile private var imgSize = 0L

    private lateinit var backBtn: View
    private lateinit var partitionInput: EditText
    private lateinit var imgInfo: TextView
    private lateinit var formPanel: LinearLayout
    private lateinit var resultPanel: LinearLayout
    private lateinit var resultPart: TextView
    private lateinit var resultSlot: TextView
    private lateinit var resultFile: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadImage(uri)
    }

    private fun loadImage(uri: Uri) {
        runTask(tr("Baca file .img", "Read .img file")) {
            val (name, size) = queryNameSize(uri)
            val magic = readMagic(uri)
            imgUri = uri
            imgName = name
            imgSize = size
            runOnUiThread { imgInfo.text = "$name\n${human(size)} · $magic" }
            log(tr("File dipilih: $name (${human(size)}), format: $magic", "File chosen: $name (${human(size)}), format: $magic"))
        }
    }

    private fun onFlash() {
        val uri = imgUri
        val base = partitionInput.text.toString().trim()
        val t = requireTarget() ?: return
        if (uri == null) {
            alert(tr("Pilih file .img dulu.", "Choose an .img file first."))
            return
        }
        if (!Regex("[a-z0-9_]+").matches(base)) {
            alert(
                tr("Nama partisi tidak valid. Pakai huruf kecil, angka, atau garis bawah, contoh: boot",
                    "Invalid partition name. Use lowercase letters, numbers, or underscore, example: boot")
            )
            return
        }
        if (imgSize <= 0L) {
            alert(tr("Ukuran file tidak terbaca. Pilih ulang file .img.", "File size could not be read. Choose the .img file again."))
            return
        }
        confirm(
            tr(
                "Flash $imgName ke partisi $base (slot aktif) di ${t.product} (${t.serial})?\n\n" +
                    "Aplikasi tidak bisa memastikan file ini cocok dengan perangkat. Itu tanggung jawab Anda.",
                "Flash $imgName to partition $base (active slot) on ${t.product} (${t.serial})?\n\n" +
                    "The app cannot confirm this file matches the device. That is your responsibility."
            )
        ) {
            runTask(tr("Flash img", "Flash img")) { flashToActiveSlot(uri, base, t) }
        }
    }

    private fun flashToActiveSlot(uri: Uri, base: String, t: DeviceInfo) {
        val cr = contentResolver
        val size = imgSize
        val img = ImageSrc(size) {
            cr.openInputStream(uri) ?: throw IOException(tr("Tidak bisa membuka file", "Cannot open file"))
        }
        val (part, slot) = withFastboot { fb ->
            val now = readDevice(fb)
            ensureSameDevice(now, t)
            val cur = fb.getvar("current-slot")?.trim().orEmpty()
            val partName = when {
                base.endsWith("_a") || base.endsWith("_b") -> base
                cur == "a" || cur == "b" -> "${base}_$cur"
                else -> throw FastbootException(
                    tr("Slot aktif tidak terbaca dari perangkat. Flash dibatalkan.",
                        "Active slot could not be read from the device. Flash cancelled.")
                )
            }
            val map = SparseImage.scan(img)
            val outSize = map.totalBlks * map.blk
            val partSize = parseHex(fb.getvar("partition-size:$partName"))
            if (partSize == null) {
                log(tr("Ukuran partisi $partName tidak terbaca, pengecekan ukuran dilewati",
                    "Size of partition $partName could not be read, size check skipped"))
            } else if (outSize > partSize) {
                throw FastbootException(
                    tr("File (${human(outSize)}) lebih besar dari partisi $partName (${human(partSize)})",
                        "File (${human(outSize)}) is larger than partition $partName (${human(partSize)})")
                )
            } else {
                log(tr("Ukuran file ${human(outSize)} muat di partisi $partName (${human(partSize)})",
                    "File size ${human(outSize)} fits in partition $partName (${human(partSize)})"))
            }
            log(tr("Tujuan: $partName (slot aktif: ${cur.ifEmpty { "tidak ada" }})",
                "Target: $partName (active slot: ${cur.ifEmpty { "none" }})"))
            flashImage(fb, img, partName, now.maxDownload, false) { m -> log(m) }
            log(tr("Selesai: $partName sudah ditulis.", "Done: $partName has been written."))
            Pair(partName, cur)
        }
        runOnUiThread { showResult(part, slot) }
    }

    /** Setelah flash selesai: form disembunyikan, hasil dan tombol lanjutan ditampilkan. Tanda Kembali tetap terlihat. */
    private fun showResult(part: String, slot: String) {
        resultPart.text = part
        resultSlot.text = slot.ifEmpty { "-" }
        resultFile.text = imgName
        formPanel.visibility = View.GONE
        resultPanel.visibility = View.VISIBLE
    }

    /** Flash file lain: kosongkan nama partisi dan file, lalu kembali ke form. */
    private fun resetForm() {
        partitionInput.setText("")
        imgUri = null
        imgName = ""
        imgSize = 0L
        imgInfo.text = tr("belum ada file .img yang di pilih", "no .img file chosen yet")
        resultPanel.visibility = View.GONE
        formPanel.visibility = View.VISIBLE
    }

    private fun onReboot() {
        runTask(tr("Reboot perangkat", "Reboot device")) {
            withFastboot { fb -> fb.reboot() }
            log(tr("Perintah reboot dikirim.", "Reboot command sent."))
        }
    }

    /** Log setinggi 260 dp. Ukurannya sama dengan log di FLASH FASTBOOT ROM. */
    private fun logPanel(): LinearLayout = makeLog().also { section ->
        section.getChildAt(1).layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
    }

    /** Tombol sekunder bergaris neon: isi gelap, garis pinggir dan tulisan bergradasi. */
    private fun neonOutline(label: String, radiusDp: Int, textSp: Float, padDp: Int, action: () -> Unit): TextView {
        val p = pal()
        val outer = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN)
        ).apply { cornerRadius = dp(radiusDp).toFloat() }
        val inner = GradientDrawable().apply {
            setColor(p.card)
            cornerRadius = dp(radiusDp - 1).toFloat()
        }
        val layers = LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
        }
        return textLine(label, textSp, medium = true, color = p.text).apply {
            gravity = Gravity.CENTER
            setPadding(dp(padDp), dp(10), dp(padDp), dp(10))
            background = layers
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

        backBtn = backButton { finish() }
        content.addView(backBtn)
        content.addView(textLine(tr("FLASH FILE IMG", "FLASH FILE IMG"), 22f, medium = true).apply {
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

        formPanel = box()
        formPanel.addView(caption(tr("nama partisi", "partition name")))
        partitionInput = EditText(this).apply {
            hint = tr("contoh: boot, init_boot, atau vendor_boot", "example: boot, init_boot, or vendor_boot")
            setHintTextColor(p.muted)
            setTextColor(p.text)
            typeface = Fonts.regular
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(p.sub, 10, p.border)
        }
        formPanel.addView(partitionInput)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        for ((i, name) in listOf("boot", "init_boot", "vendor_boot").withIndex()) {
            val chip = neonOutline(name, 20, 13f, 8) { partitionInput.setText(name) }
            chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (i > 0) dp(8) else 0
            }
            chips.addView(chip)
        }
        formPanel.addView(chips)

        formPanel.addView(caption(tr("file .img", ".img file")))
        val bPick = neonOutline(tr("PILIH FILE", "CHOOSE FILE"), 10, 15f, 12) { pickFile(REQ_PICK_IMG) }
        bPick.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        formPanel.addView(bPick)
        imgInfo = textLine(tr("belum ada file .img yang di pilih", "no .img file chosen yet"), 13f, color = p.muted, mono = true)
        imgInfo.setPadding(0, dp(8), 0, 0)
        formPanel.addView(imgInfo)

        val bFlash = btn(tr("FLASH FILE", "FLASH FILE"), BtnStyle.PRIMARY) { onFlash() }
        bFlash.paint.isFakeBoldText = true
        formPanel.addView(bFlash)
        content.addView(formPanel)

        resultPanel = box()
        resultPanel.visibility = View.GONE
        resultPanel.addView(textLine(tr("Flash Berhasil", "Flash Successful"), 16f, medium = true, color = p.okText))
        resultPart = kvRow(resultPanel, tr("partisi", "partition"))
        resultSlot = kvRow(resultPanel, tr("slot aktif", "active slot"))
        resultFile = kvRow(resultPanel, "file")
        resultPanel.addView(btn(tr("FLASH FILE LAIN", "FLASH ANOTHER FILE"), BtnStyle.PRIMARY) { resetForm() })
        resultPanel.addView(btn(tr("REBOOT PERANGKAT", "REBOOT DEVICE"), BtnStyle.PRIMARY) { onReboot() })
        content.addView(resultPanel)

        setContentView(page(ScrollView(this).apply { addView(content) }, logPanel()))
    }
}

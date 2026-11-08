package com.skypin.flasher

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException

/** Halaman Flash file img: pilih file, tulis ke slot aktif, lalu tampil hasil dengan tombol reboot. */
class FlashImgActivity : FlasherBase() {

    @Volatile private var imgUri: Uri? = null
    @Volatile private var imgName = ""
    @Volatile private var imgSize = 0L

    private lateinit var partitionInput: EditText
    private lateinit var imgInfo: TextView
    private lateinit var formPanel: LinearLayout
    private lateinit var resultPanel: LinearLayout
    private lateinit var resultText: TextView

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
        runTask("Baca file .img") {
            val (name, size) = queryNameSize(uri)
            val magic = readMagic(uri)
            imgUri = uri
            imgName = name
            imgSize = size
            runOnUiThread { imgInfo.text = "File: $name\nUkuran: ${human(size)}\nFormat: $magic" }
            log("File dipilih: $name (${human(size)}), format: $magic")
        }
    }

    private fun onFlash() {
        val uri = imgUri
        val base = partitionInput.text.toString().trim()
        val t = requireTarget() ?: return
        if (uri == null) {
            alert("Pilih file .img dulu.")
            return
        }
        if (!Regex("[a-z0-9_]+").matches(base)) {
            alert("Nama partisi tidak valid. Pakai huruf kecil, angka, atau garis bawah, contoh: boot")
            return
        }
        if (imgSize <= 0L) {
            alert("Ukuran file tidak terbaca. Pilih ulang file .img.")
            return
        }
        confirm(
            "Flash $imgName ke partisi $base (slot aktif) di ${t.product} (${t.serial})?\n\n" +
                "Aplikasi tidak bisa memastikan file ini cocok dengan perangkat. Itu tanggung jawab Anda."
        ) {
            runTask("Flash img") { flashToActiveSlot(uri, base, t) }
        }
    }

    private fun flashToActiveSlot(uri: Uri, base: String, t: DeviceInfo) {
        val cr = contentResolver
        val size = imgSize
        val img = ImageSrc(size) { cr.openInputStream(uri) ?: throw IOException("Tidak bisa membuka file") }
        val (part, slot) = withFastboot { fb ->
            val now = readDevice(fb)
            ensureSameDevice(now, t)
            val cur = fb.getvar("current-slot")?.trim().orEmpty()
            val partName = when {
                base.endsWith("_a") || base.endsWith("_b") -> base
                cur == "a" || cur == "b" -> "${base}_$cur"
                else -> throw FastbootException("Slot aktif tidak terbaca dari perangkat. Flash dibatalkan.")
            }
            val map = SparseImage.scan(img)
            val outSize = map.totalBlks * map.blk
            val partSize = parseHex(fb.getvar("partition-size:$partName"))
            if (partSize == null) {
                log("Ukuran partisi $partName tidak terbaca, pengecekan ukuran dilewati")
            } else if (outSize > partSize) {
                throw FastbootException("File (${human(outSize)}) lebih besar dari partisi $partName (${human(partSize)})")
            } else {
                log("Ukuran file ${human(outSize)} muat di partisi $partName (${human(partSize)})")
            }
            log("Tujuan: $partName (slot aktif: ${cur.ifEmpty { "tidak ada" }})")
            flashImage(fb, img, partName, now.maxDownload, false) { m -> log(m) }
            log("Selesai: $partName sudah ditulis.")
            Pair(partName, cur)
        }
        runOnUiThread { showResult(part, slot) }
    }

    private fun showResult(part: String, slot: String) {
        resultText.text = "Partisi: $part\nSlot aktif: $slot\nFile: $imgName"
        formPanel.visibility = View.GONE
        resultPanel.visibility = View.VISIBLE
    }

    private fun onReboot() {
        runTask("Reboot perangkat") {
            withFastboot { fb -> fb.reboot() }
            log("Perintah reboot dikirim.")
        }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        content.addView(btn("Kembali") { finish() })
        content.addView(textLine("Flash file img", 20f).apply { setTypeface(null, Typeface.BOLD) })
        content.addView(caption("Target: ${Session.target?.product ?: "belum dicek"}"))

        formPanel = box()
        formPanel.addView(caption("Nama partisi"))
        partitionInput = EditText(this).apply {
            hint = "contoh: boot atau vendor_boot"
            setSingleLine(true)
        }
        formPanel.addView(partitionInput)
        formPanel.addView(btn("Pilih file .img") { pickFile(REQ_PICK_IMG) })
        imgInfo = textLine("Belum ada file .img dipilih", 14f)
        formPanel.addView(imgInfo)
        formPanel.addView(btn("Flash ke slot aktif") { onFlash() })
        content.addView(formPanel)

        resultPanel = box()
        resultPanel.visibility = View.GONE
        resultPanel.addView(textLine("Flash berhasil", 16f).apply { setTypeface(null, Typeface.BOLD) })
        resultText = textLine("", 14f)
        resultPanel.addView(resultText)
        resultPanel.addView(btn("Reboot perangkat") { onReboot() })
        content.addView(resultPanel)

        val top = ScrollView(this).apply { addView(content) }
        setContentView(splitScreen(top, makeLog()))
    }
}

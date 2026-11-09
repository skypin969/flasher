package com.skypin.flasher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Layar ADB SIDELOAD: kirim satu file zip ke HP target yang ada di recovery (Apply from ADB). */
class AdbSideloadActivity : FlasherBase() {

    private var zipUri: Uri? = null
    private var zipName = ""
    private var zipSize = 0L

    /** false = sebelum kirim, true = sesudah kirim. */
    private var sent = false

    private lateinit var deviceLine: TextView
    private lateinit var guideTitle: TextView
    private lateinit var guideCard: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var fileLabel: TextView
    private lateinit var pickTop: Button
    private lateinit var fileCard: LinearLayout
    private lateinit var fileNameLine: TextView
    private lateinit var fileStateLine: TextView
    private lateinit var pickBottom: Button
    private lateinit var sendRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        applyState()
        refreshDevice()
    }

    override fun onResume() {
        super.onResume()
        refreshDevice()
    }

    override fun onBackPressed() {
        onBack()
    }

    private fun buildUi() {
        val p = pal()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = neonBackground()
        }

        root.addView(backButton { onBack() })
        root.addView(textLine("ADB SIDELOAD", 22f, medium = true, color = p.text).apply {
            setPadding(0, dp(4), 0, dp(2))
        })
        deviceLine = textLine("", 13f, color = p.muted)
        root.addView(deviceLine)

        // Petunjuk, tampil sebelum kirim
        guideCard = box()
        guideTitle = textLine("", 12f, color = p.muted)
        guideCard.addView(guideTitle)
        guideCard.addView(textLine(
            tr("1. masuk recovery dengan volume atas + power", "1. enter recovery with volume up + power"),
            14f
        ).apply { setPadding(0, dp(6), 0, 0) })
        guideCard.addView(textLine(
            tr("2. pilih Apply update → Apply from ADB", "2. choose Apply update → Apply from ADB"),
            14f
        ).apply { setPadding(0, dp(6), 0, 0) })
        guideCard.addView(textLine(
            tr("3. kembali ke sini, lalu tekan KIRIM", "3. come back here, then press SEND"),
            14f
        ).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(guideCard)

        // Status, tampil sesudah kirim
        statusCard = neonCard()
        val statusTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusTop.addView(textLine(tr("Status", "Status"), 12f, color = p.muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        statusTop.addView(textLine(tr("Selesai", "Done"), 11f, color = p.okText).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(p.ok, 999, null)
        })
        statusCard.addView(statusTop)
        statusCard.addView(textLine(tr("zip terkirim", "zip sent"), 16f, medium = true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        statusCard.addView(
            View(this).apply {
                background = neonButtonBackground(this@AdbSideloadActivity, 999)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        )
        statusCard.addView(textLine(
            tr("di recovery, pilih Reboot system. untuk Google apps, lanjut kirim MindTheGapps.",
                "in recovery, choose Reboot system. for Google apps, send MindTheGapps next."),
            12f,
            color = p.sec
        ).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(statusCard)

        // Pilih file, sebelum kirim
        fileLabel = caption(tr("file zip", "zip file"))
        root.addView(fileLabel)
        pickTop = neonOutlineBtn(tr("PILIH FILE ZIP", "PICK ZIP FILE")) { pickZip() }
        root.addView(pickTop)

        fileCard = box()
        fileNameLine = textLine("", 14f, medium = true)
        fileStateLine = textLine("", 12f, color = p.muted).apply { setPadding(0, dp(4), 0, 0) }
        fileCard.addView(fileNameLine)
        fileCard.addView(fileStateLine)
        root.addView(fileCard)

        // Pilih zip berikutnya, sesudah kirim
        pickBottom = neonOutlineBtn(tr("PILIH ZIP BERIKUTNYA", "PICK NEXT ZIP")) { pickZip() }
        root.addView(pickBottom)

        // CEK dan KIRIM, sebelum kirim
        sendRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sendRow.addView(neonOutlineBtn(tr("CEK ADB SIDELOAD", "CHECK ADB SIDELOAD")) { onCheck() })
        sendRow.addView(btn(tr("KIRIM", "SEND"), BtnStyle.PRIMARY) { onSend() })
        root.addView(sendRow)

        // Log mengisi sisa tinggi layar
        root.addView(
            makeLog(fill = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(10)
            }
        )

        setContentView(root)
    }

    /** Menampilkan bagian sebelum kirim atau sesudah kirim, sesuai keadaan [sent]. */
    private fun applyState() {
        val done = sent
        guideCard.visibility = if (done) View.GONE else View.VISIBLE
        statusCard.visibility = if (done) View.VISIBLE else View.GONE
        fileLabel.visibility = if (done) View.GONE else View.VISIBLE
        pickTop.visibility = if (done) View.GONE else View.VISIBLE
        pickBottom.visibility = if (done) View.VISIBLE else View.GONE
        sendRow.visibility = if (done) View.GONE else View.VISIBLE
        fileNameLine.text = if (zipUri == null) tr("belum ada file dipilih", "no file picked yet") else zipName
        fileStateLine.text = if (done) tr("terkirim", "sent") else tr("belum dikirim", "not sent yet")
    }

    private fun setSent(done: Boolean) {
        sent = done
        applyState()
    }

    /** Nama perangkat dari USB. Jika nama tidak terbaca, tampil "perangkat ADB terdeteksi". */
    private fun refreshDevice() {
        val name = adbDeviceName()
        deviceLine.text = when {
            name == null -> tr("belum ada perangkat ADB", "no ADB device yet")
            name.isBlank() -> tr("perangkat ADB terdeteksi", "ADB device detected")
            else -> name
        }
        val label = if (name.isNullOrBlank()) tr("HP target", "target phone") else name
        guideTitle.text = tr("langkah di ", "steps on ") + label
    }

    private fun pickZip() {
        if (busy) {
            log(tr("Tunggu proses berjalan selesai dulu.", "Wait for the running task to finish."))
            return
        }
        pickFile(REQ_PICK_ZIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_ZIP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val (name, size) = queryNameSize(uri)
        zipUri = uri
        zipName = name
        zipSize = size
        if (!looksLikeZip(uri)) {
            log(tr("Peringatan: file ini tidak terbaca sebagai zip.", "Warning: this file does not look like a zip."))
        }
        runOnUiThread { setSent(false) }
    }

    /** Zip selalu diawali huruf PK. */
    private fun looksLikeZip(uri: Uri): Boolean {
        val head = ByteArray(4)
        val n = contentResolver.openInputStream(uri)?.use { it.read(head) } ?: 0
        return n >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
    }

    private fun onCheck() {
        runTask(tr("CEK ADB SIDELOAD", "CHECK ADB SIDELOAD")) {
            withAdb(retryOnPermission = { onCheck() }) { adb -> adb.connect() }
            log(tr("Perangkat ADB siap. Pilih zip, lalu tekan KIRIM.", "ADB device is ready. Pick a zip, then press SEND."))
        }
    }

    private fun onSend() {
        val uri = zipUri
        val size = zipSize
        val name = zipName
        if (uri == null || size <= 0L) {
            log(tr("Pilih file zip dulu.", "Pick a zip file first."))
            return
        }
        runTask(tr("KIRIM ZIP", "SEND ZIP")) {
            log("[1/1] " + tr("kirim", "send") + " " + name)
            var lastPct = -1
            withAdb(retryOnPermission = { onSend() }) { adb ->
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw AdbException(tr("Gagal membuka file zip.", "Failed to open the zip file."))
                pfd.use { fd ->
                    val ch = FileInputStream(fd.fileDescriptor).channel
                    adb.sideload(size, { off, dst, len -> readAt(ch, off, dst, len) }) { sentBytes ->
                        val pct = (sentBytes * 100 / size).toInt()
                        if (pct >= lastPct + 5 || (pct == 100 && lastPct != 100)) {
                            lastPct = pct
                            log(tr("kirim $pct%", "send $pct%"))
                        }
                    }
                }
            }
            // Recovery selalu memberi tanda selesai, walau pemasangan gagal. Hasil pemasangan dilihat di Poco.
            log(
                tr("transfer selesai. lihat hasil pemasangan di layar Poco.",
                    "transfer finished. check the install result on the Poco screen.")
            )
            runOnUiThread { setSent(true) }
        }
    }

    /** Membaca [len] byte dari posisi [offset] file. Mengembalikan jumlah byte yang terbaca. */
    private fun readAt(ch: FileChannel, offset: Long, dst: ByteArray, len: Int): Int {
        val bb = ByteBuffer.wrap(dst, 0, len)
        var pos = offset
        while (bb.hasRemaining()) {
            val n = ch.read(bb, pos)
            if (n < 0) break
            pos += n
        }
        return bb.position()
    }

    private fun onBack() {
        if (busy) {
            log(tr("Tunggu proses berjalan selesai dulu.", "Wait for the running task to finish."))
            return
        }
        finish()
    }
}

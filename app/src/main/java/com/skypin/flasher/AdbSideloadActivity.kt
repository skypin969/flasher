package com.skypin.flasher

import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/** Layar ADB SIDELOAD: kirim satu file zip ke HP target yang ada di recovery (Apply from ADB). */
class AdbSideloadActivity : FlasherBase() {

    private var zipUri: Uri? = null
    private var zipName = ""
    private var zipSize = 0L

    /** Codename dari pesan koneksi ADB. Kosong jika tidak terbaca. */
    @Volatile private var peerCodename = ""

    /** false = sebelum kirim, true = sesudah kirim. */
    private var sent = false

    private lateinit var deviceLine: TextView
    private lateinit var guideTitle: TextView
    private lateinit var guideCard: LinearLayout
    private lateinit var guideSteps: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var statusNote: TextView
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
            // Jarak atas dibuat kecil supaya isi halaman naik dan log (yang mengisi sisa layar) jadi lebih tinggi
            setPadding(dp(16), dp(4), dp(16), dp(16))
            background = neonBackground()
        }

        val back = backButton { onBack() }
        back.minimumHeight = dp(32)
        back.setPadding(dp(4), 0, dp(12), 0)
        back.layoutParams = lp(top = 0)
        root.addView(back)
        root.addView(gradientTitle("ADB SIDELOAD", 22f))
        root.addView(textLine(
            tr("kirim file .zip dari recovery lewat adb", "send a .zip file from recovery over adb"),
            13f,
            color = p.muted
        ))
        deviceLine = textLine("", 14f, color = p.sec).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(deviceLine)

        // Petunjuk, tampil sebelum kirim. Isinya ikut zip yang dipilih (lihat guideLines)
        guideCard = neonCard()
        guideTitle = textLine("", 12f, color = p.muted)
        guideCard.addView(guideTitle)
        guideSteps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        guideCard.addView(guideSteps)
        // Jarak kartu petunjuk ke judul "file zip" dibuat lebih rapat, supaya blok zip naik sedikit
        guideCard.layoutParams = lp(bottom = 4)
        root.addView(guideCard)

        // Status, tampil sesudah kirim
        statusCard = neonCard()
        val statusTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusTop.addView(textLine(tr("zip terkirim", "zip sent"), 15f, medium = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        statusTop.addView(textLine(tr("Selesai", "Done"), 11f, medium = true, color = p.okText).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(p.ok, 999, null)
        })
        statusCard.addView(statusTop)
        statusCard.addView(
            View(this).apply {
                background = neonButtonBackground(this@AdbSideloadActivity, 999)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        )
        statusNote = textLine("", 13f, color = p.sec)
        statusCard.addView(statusNote)
        root.addView(statusCard)

        // Pilih file
        fileLabel = caption(tr("file zip", "zip file"))
        root.addView(fileLabel)
        pickTop = neonOutlineBtn(tr("PILIH FILE ZIP", "PICK ZIP FILE")) { pickZip() }
        root.addView(pickTop)

        // Jarak 10 dp dari tombol di atasnya supaya tidak menempel
        fileCard = box()
        fileCard.layoutParams = lp(top = 10)
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
        sendRow.addView(btn(tr("CEK ADB SIDELOAD", "CHECK ADB SIDELOAD"), BtnStyle.PRIMARY) { onCheck() })
        val sendBtn = btn(tr("KIRIM", "SEND"), BtnStyle.PRIMARY) { onSend() }
        sendRow.addView(sendBtn)
        root.addView(sendRow)

        // Log di bawah layar, sama dengan layar lain
        setContentView(logPage(ScrollView(this).apply { addView(root) }))
    }

    private fun stepLine(s: String): TextView = textLine(s, 10f).apply {
        setPadding(0, dp(4), 0, 0)
    }

    /**
     * Langkah sebelum kirim. Zip ROM pertama: factory reset di recovery, pasang kabel dengan pengisian daya terbalik, lalu Apply from ADB.
     * Zip MindTheGapps (putaran kedua): Apply from ADB lagi di HP target, lalu pilih Yes saat diminta.
     */
    private fun guideLines(): List<String> =
        if (isMindTheGapps(zipName)) {
            listOf(
                tr("1. di ${deviceLabel()} (recovery), pilih Apply update → Apply from ADB",
                    "1. on ${deviceLabel()} (recovery), choose Apply update → Apply from ADB"),
                tr("2. di sini, tekan CEK ADB SIDELOAD (setujui popup izin USB jika muncul), lalu KIRIM",
                    "2. here, press CHECK ADB SIDELOAD (approve the USB popup if it appears), then SEND"),
                tr("3. saat di ${deviceLabel()} muncul \"Install anyway?\", pilih Yes",
                    "3. when ${deviceLabel()} shows \"Install anyway?\", choose Yes")
            )
        } else {
            listOf(
                tr("1. Di recovery pilih: Factory reset, lalu konfirmasi (hanya untuk pemasangan rom pertama)",
                    "1. In recovery choose: Factory reset, then confirm (only for the first rom install)"),
                tr("2. Selanjutnya pasang kabel, ketuk notifikasi \"Mengisi daya perangkat ini\", lalu pilih: \"pengisian daya terbalik\".",
                    "2. Next connect the cable, tap the \"Charging this device\" notification, then choose: \"reverse charging\"."),
                tr("3. Lalu pilih: Apply update → Apply from ADB",
                    "3. Then choose: Apply update → Apply from ADB"),
                tr("4. kembali ke sini, tekan CEK ADB SIDELOAD (setujui popup izin USB jika muncul), lalu KIRIM, lihat log di bawah.",
                    "4. come back here, press CHECK ADB SIDELOAD (approve the USB popup if it appears), then SEND, and check the log below.")
            )
        }

    /** Mengisi ulang petunjuk sebelum kirim sesuai zip yang dipilih. */
    private fun renderGuide() {
        guideSteps.removeAllViews()
        for (s in guideLines()) guideSteps.addView(stepLine(s))
    }

    /** Judul tebal dengan gradasi ungu ke cyan, seperti di tampilan Flashmate. Gradasi mengikuti lebar tulisan. */
    private fun gradientTitle(s: String, size: Float): TextView = textLine(s, size, medium = true).apply {
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tv = v as TextView
            val w = tv.paint.measureText(tv.text.toString())
            if (w > 0f) {
                tv.paint.shader = LinearGradient(
                    0f, 0f, w, 0f,
                    intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN),
                    null,
                    Shader.TileMode.CLAMP
                )
                tv.invalidate()
            }
        }
    }

    /** Menampilkan bagian sebelum kirim atau sesudah kirim, sesuai keadaan [sent]. */
    private fun applyState() {
        val p = pal()
        val done = sent
        renderGuide()
        guideCard.visibility = if (done) View.GONE else View.VISIBLE
        statusCard.visibility = if (done) View.VISIBLE else View.GONE
        pickTop.visibility = if (done) View.GONE else View.VISIBLE
        pickBottom.visibility = if (done) View.VISIBLE else View.GONE
        sendRow.visibility = if (done) View.GONE else View.VISIBLE
        fileNameLine.text = if (zipUri == null) tr("belum ada file dipilih", "no file picked yet") else zipName
        fileStateLine.text = if (done) tr("terkirim", "sent") else tr("belum dikirim", "not sent yet")
        fileStateLine.setTextColor(if (done) p.accent else p.muted)
    }

    private fun setSent(done: Boolean) {
        sent = done
        applyState()
    }

    /**
     * Nama untuk pesan dan tampilan: nama dari USB, ditambah codename jika terbaca.
     * Jika keduanya tidak terbaca, tulisannya "perangkat".
     */
    private fun deviceLabel(): String {
        val usbName = adbDeviceName()?.takeIf { it.isNotBlank() }
        val codename = peerCodename.takeIf { it.isNotBlank() }
        return when {
            usbName != null && codename != null ->
                if (usbName.contains(codename, ignoreCase = true)) usbName else "$usbName · $codename"
            usbName != null -> usbName
            codename != null -> codename
            else -> tr("perangkat", "device")
        }
    }

    private fun refreshDevice() {
        val deviceFound = adbDeviceName() != null
        if (!deviceFound) peerCodename = ""
        deviceLine.text = if (deviceFound) deviceLabel() else tr("belum ada perangkat ADB", "no ADB device yet")
        guideTitle.text = tr("langkah di ", "steps on ") + deviceLabel()
        // Langkah MindTheGapps memakai codename, jadi ikut diperbarui setiap nama perangkat berubah
        renderGuide()
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
            withAdb(retryOnPermission = { onCheck() }) { adb ->
                adb.connect()
                peerCodename = adb.codename
            }
            runOnUiThread { refreshDevice() }
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
            var waitShown = false
            try {
                withAdb(retryOnPermission = { onSend() }) { adb ->
                    adb.connect()
                    peerCodename = adb.codename
                    runOnUiThread { refreshDevice() }
                    val label = deviceLabel()
                    logStatus(tr("Proses mengirim file ke $label", "Sending the file to $label"))

                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw AdbException(tr("Gagal membuka file zip.", "Failed to open the zip file."))
                    pfd.use { fd ->
                        val ch = FileInputStream(fd.fileDescriptor).channel
                        adb.sideload(size, { off, dst, len -> readAt(ch, off, dst, len) }) { sentBytes ->
                            // Tanpa persen. Setelah blok terakhir terkirim, tampilkan tahap menunggu Poco
                            if (sentBytes >= size && !waitShown) {
                                waitShown = true
                                logStatus(waitTextFor(name, label))
                            }
                        }
                    }

                    // Zip sudah selesai dikirim. Hasil pemasangan hanya terlihat di layar perangkat, jadi log memberi tahu langkah berikutnya
                    logStatus(finishTextFor(name, label))
                }
            } finally {
                clearStatus()
            }
            val note = noteFor(name, deviceLabel())
            runOnUiThread {
                statusNote.text = note
                setSent(true)
            }
        }
    }

    /**
     * Teks kartu status sesudah zip terkirim, sesuai cara flashing masing-masing ROM.
     * Jenisnya dilihat dari nama file zip: mindthegapps, gapps (GApps bawaan), atau lainnya (GApps terpisah).
     */
    private fun noteFor(file: String, label: String): String {
        val f = file.lowercase(Locale.ROOT)
        return when {
            isMindTheGapps(file) -> tr(
                "Pilih Reboot system now.",
                "Choose Reboot system now."
            )
            f.contains("gapps") -> tr(
                "Jika ditanya install paket tambahan, pilih No.\nLalu pilih Reboot system now.",
                "If asked to install additional packages, choose No.\nThen choose Reboot system now."
            )
            else -> tr(
                "Tanpa MindTheGapps, pilih: Reboot system now.\nJika ingin pasang MindTheGapps: pilih Advanced → Reboot to recovery. Di $label pilih Apply update → Apply from ADB, lalu di sini pilih PILIH ZIP BERIKUTNYA dan MindTheGapps.",
                "Without MindTheGapps, choose: Reboot system now.\nTo install MindTheGapps: choose Advanced → Reboot to recovery. On $label choose Apply update → Apply from ADB, then here pick NEXT ZIP and MindTheGapps."
            )
        }
    }

    /** MindTheGapps: GApps terpisah. Di layar perangkat muncul "Install anyway?" dan pilihannya harus Yes. */
    private fun isMindTheGapps(file: String): Boolean =
        file.lowercase(Locale.ROOT).contains("mindthegapps")

    /**
     * Teks log saat zip sudah terkirim dan perangkat sedang memasang (belum ada tanda selesai).
     * Log menjelaskan pilihan di layar perangkat, sesuai jenis zip.
     */
    private fun waitTextFor(file: String, label: String): String =
        if (isMindTheGapps(file)) {
            tr(
                "menunggu $label memproses file... Jika di layar $label muncul \"Install anyway?\", pilih Yes.",
                "waiting for $label to process the file... If $label shows \"Install anyway?\", choose Yes."
            )
        } else {
            tr(
                "menunggu $label memproses file... Jika di layar $label muncul pilihan Yes atau No, pilih No.",
                "waiting for $label to process the file... If $label shows a Yes or No choice, choose No."
            )
        }

    /** Teks log setelah zip selesai dikirim. Hanya menyebut langkah berikutnya, tidak menunggu tulisan di layar. */
    private fun finishTextFor(file: String, label: String): String =
        if (isMindTheGapps(file)) {
            tr(
                "MindTheGapps sudah terkirim. Pastikan di layar $label ada tulisan \"Install completed with status 0\", lalu pilih Reboot system now.",
                "MindTheGapps has been sent. Make sure the $label screen shows \"Install completed with status 0\", then choose Reboot system now."
            )
        } else {
            tr(
                "Zip sudah terkirim. Pastikan di layar $label ada tulisan \"Install completed with status 0\", lalu ikuti petunjuk di kartu di atas.",
                "Zip has been sent. Make sure the $label screen shows \"Install completed with status 0\", then follow the steps on the card above."
            )
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

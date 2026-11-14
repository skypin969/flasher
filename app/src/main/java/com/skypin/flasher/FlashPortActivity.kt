package com.skypin.flasher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Halaman FLASH ROM PORT: memasang paket port HyperOS 4 untuk Poco X7 5G (malachite).
 * Paketnya folder yang berisi images/ dan DragonX_Installer.bat. Langkahnya mengikuti .bat (lihat PortPlan.kt).
 * Halaman ini tidak memakai dan tidak mengubah FLASH FASTBOOT ROM.
 */
class FlashPortActivity : FlasherBase() {

    /** Sumber paket dari folder yang dipilih. Null jika belum ada folder. */
    @Volatile private var portSrc: RomSource? = null
    @Volatile private var portName = ""
    @Volatile private var portWipe = true
    @Volatile private var portRoot = PortRoot.NONE
    @Volatile private var portToRecovery = false

    private lateinit var romInfo: TextView
    private lateinit var checkText: TextView
    private lateinit var ujiToggle: NeonToggle
    private lateinit var ujiState: TextView
    private lateinit var wipeBox: LinearLayout
    private lateinit var rootBox: LinearLayout
    private lateinit var rebootBox: LinearLayout
    private lateinit var runBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUji()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_PICK_TREE) loadPortFolder(uri)
    }

    private fun pickPortFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_TREE)
    }

    /** Membaca folder yang dipilih. Dipanggil di tugas latar karena membaca isi folder. */
    private fun loadPortFolder(tree: Uri) {
        runTask(tr("Baca folder paket port", "Read the port package folder")) {
            runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            portSrc = SafRomSource(contentResolver, tree)
            portName = tree.lastPathSegment ?: "folder"
            runOnUiThread { refreshPortCheck() }
        }
    }

    /** Menghitung ulang file yang kurang dan mengatur JALANKAN. Hanya memeriksa daftar di memori, jadi aman di layar utama. */
    private fun refreshPortCheck() {
        val p = pal()
        val src = portSrc
        if (src == null) {
            romInfo.text = tr("belum ada folder dipilih", "no folder chosen yet")
            checkText.text = tr(
                "Pilih folder yang berisi images/ dan DragonX_Installer.bat dari paket HyperOS 4.",
                "Choose the folder that contains images/ and DragonX_Installer.bat from the HyperOS 4 package."
            )
            checkText.setTextColor(p.muted)
            setRunEnabled(false)
            return
        }
        romInfo.text = tr("Folder: $portName", "Folder: $portName")
        val need = requiredPortFiles(portRoot)
        val missing = need.filter { !src.exists(it) }
        if (missing.isEmpty()) {
            checkText.text = tr("Semua ${need.size} file yang dibutuhkan ada.", "All ${need.size} required files are present.")
            checkText.setTextColor(p.okText)
        } else {
            val head = tr("File tidak lengkap, JALANKAN dimatikan. Yang hilang:", "Files are incomplete, RUN is off. Missing:")
            checkText.text = head + "\n" + missing.joinToString("\n")
            checkText.setTextColor(p.warnText)
        }
        setRunEnabled(missing.isEmpty())
    }

    private fun setRunEnabled(on: Boolean) {
        runBtn.isEnabled = on
        runBtn.alpha = if (on) 1f else 0.4f
    }

    /** Baris saklar Mode uji. Klik di mana saja pada baris untuk mengganti. */
    private fun ujiRow(): LinearLayout {
        val p = pal()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleUji() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(textLine(tr("Mode uji", "Test mode"), 15f, medium = true, color = p.text))
        ujiState = textLine("", 12f, medium = true, color = p.accent)
        col.addView(ujiState)
        row.addView(col)
        // Saklar hanya tampilan. Klik ditangani oleh baris, supaya nilainya selalu disimpan
        ujiToggle = NeonToggle(this).apply { isClickable = false }
        row.addView(ujiToggle)
        row.layoutParams = lp(top = 6)
        return row
    }

    private fun toggleUji() {
        if (busy) {
            log(tr("Tunggu proses berjalan selesai dulu.", "Wait for the running task to finish."))
            return
        }
        PortSettings.setDryRun(this, !PortSettings.dryRun(this))
        refreshUji()
    }

    /** Menyesuaikan saklar dan tulisannya dengan setelan yang tersimpan. */
    private fun refreshUji() {
        val on = PortSettings.dryRun(this)
        val p = pal()
        ujiToggle.isChecked = on
        ujiState.text = if (on) {
            tr("aktif: tidak ada data yang dikirim", "on: no data is sent")
        } else {
            tr("nonaktif: flash sungguhan", "off: real flash")
        }
        ujiState.setTextColor(if (on) p.accent else p.warnText)
    }

    /** Isi ulang deret pilihan, satu baris per pilihan. Pilihan yang aktif diberi gradasi neon. */
    private fun fillChoices(box: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val p = pal()
        box.removeAllViews()
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val item = textLine(label, 14f, medium = true, color = if (on) p.onAccent else p.text).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = if (on) neonButtonBackground(context) else rounded(p.sub, 10, p.border)
                isClickable = true
                isFocusable = true
                setOnClickListener { if (!busy) onPick(i) }
                layoutParams = lp(top = 6)
            }
            box.addView(item)
        }
    }

    /** Membuat ulang semua deret pilihan dari nilai yang sedang aktif. */
    private fun fillAllChoices() {
        fillChoices(
            wipeBox,
            listOf(
                tr("Y  Ya, hapus data (clean flash, default)", "Y  Yes, erase data (clean flash, default)"),
                tr("N  Tidak, data tetap ada (dirty flash)", "N  No, keep data (dirty flash)")
            ),
            if (portWipe) 0 else 1
        ) { i ->
            portWipe = i == 0
            fillAllChoices()
        }
        val roots = PortRoot.values().toList()
        fillChoices(rootBox, roots.map { it.label() }, roots.indexOf(portRoot)) { i ->
            portRoot = roots[i]
            fillAllChoices()
            refreshPortCheck()
        }
        fillChoices(
            rebootBox,
            listOf(
                tr("S  Sistem (default)", "S  System (default)"),
                tr("R  Recovery", "R  Recovery")
            ),
            if (portToRecovery) 1 else 0
        ) { i ->
            portToRecovery = i == 1
            fillAllChoices()
        }
    }

    /** Alur JALANKAN: cek perangkat, periksa file dan ukuran partisi, tulis langkah ke log, lalu jalankan. */
    private fun onRunPort() {
        val t = requireTarget() ?: return
        val src = portSrc
        if (src == null) {
            alert(tr("Pilih folder paket port dulu.", "Choose the port package folder first."))
            return
        }
        val root = portRoot
        val wipe = portWipe
        val toRecovery = portToRecovery
        val missing = requiredPortFiles(root).filter { !src.exists(it) }
        if (missing.isNotEmpty()) {
            alert(tr("File tidak lengkap:", "Files are incomplete:") + "\n" + missing.joinToString("\n"))
            return
        }
        val dry = PortSettings.dryRun(this)
        val dest = if (toRecovery) tr("Recovery", "Recovery") else tr("Sistem", "System")
        val msg = if (dry) {
            tr(
                "Mode uji: tidak ada data yang dikirim ke perangkat. Lanjutkan?",
                "Test mode: no data is sent to the device. Continue?"
            )
        } else {
            val wipeText = if (wipe) {
                tr("semua data dihapus (wipe Y)", "all data is erased (wipe Y)")
            } else {
                tr("data tidak dihapus (wipe N)", "data is not erased (wipe N)")
            }
            tr(
                "PERINGATAN: flash asli akan menulis ulang partisi ROM port di ${t.product} (${t.serial}), dan $wipeText. Lanjutkan?",
                "WARNING: real flash will rewrite the port ROM partitions on ${t.product} (${t.serial}), and $wipeText. Continue?"
            )
        }
        confirm(msg) {
            runTask(if (dry) tr("Mode uji ROM PORT", "PORT ROM test mode") else tr("Flash ROM PORT", "Flash PORT ROM")) {
                withFastboot { fb ->
                    val now = readDevice(fb)
                    ensureSameDevice(now, t)
                    val plan = buildPortPlan(src, now, wipe, root, portName, readPortSizes(fb))
                    log(describePlan(plan, src))
                    if (plan.errors.isNotEmpty()) {
                        throw FastbootException(plan.errors.joinToString("; "))
                    }
                    try {
                        runPlan(fb, src, plan, dry) { m -> log(m) }
                    } catch (e: FastbootException) {
                        if (!dry) {
                            log(
                                tr(
                                    "Flash berhenti di tengah. HP belum di-reboot. Jangan cabut kabel. Perbaiki penyebabnya, lalu JALANKAN lagi dari awal.",
                                    "Flash stopped halfway. The phone has not been rebooted. Do not unplug the cable. Fix the cause, then RUN again from the start."
                                )
                            )
                        }
                        throw e
                    }
                    if (dry) {
                        log(tr("[UJI] Langkah terakhir: reboot ke $dest (tidak dijalankan)",
                            "[UJI] Last step: reboot to $dest (not run)"))
                    } else {
                        log(tr("Reboot ke $dest...", "Rebooting to $dest..."))
                        try {
                            if (toRecovery) fb.rebootRecovery() else fb.reboot()
                            log("   OK")
                        } catch (e: FastbootException) {
                            log(
                                tr(
                                    "Reboot gagal: ${e.message}. Flash sudah selesai, reboot manual dari HP.",
                                    "Reboot failed: ${e.message}. The flash is done, reboot the phone manually."
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildUi() {
        val p = pal()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

        content.addView(backButton { finish() }.apply { minimumHeight = dp(32) })

        val title = textLine("FLASH ROM PORT", 22f, medium = true)
        applyNeonText(title)
        title.setPadding(0, dp(8), 0, 0)
        content.addView(title)

        val tgt = Session.target
        content.addView(
            caption(
                if (tgt != null) "${marketName(tgt.product)} · ${tgt.product}"
                else tr("Target belum dicek", "Target not checked yet")
            )
        )

        // Peringatan urutan aman, selalu tampil di atas form
        val warn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.warnBg, 12, null)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = lp(top = 10)
        }
        warn.addView(textLine(tr("urutan yang aman:", "safe order:"), 13f, medium = true, color = p.warnText))
        warn.addView(
            textLine(
                tr(
                    "1. pilih folder paket HyperOS 4 (yang berisi images/ dan DragonX_Installer.bat)\n" +
                        "2. biarkan MODE UJI aktif, lalu JALANKAN. Mode uji hanya membaca HP dan menyusun rencana, tidak mengirim data\n" +
                        "3. baca daftar langkah dan CATATAN di log. Jika ada yang tidak bisa dijalankan, perbaiki dulu\n" +
                        "4. matikan MODE UJI, lalu JALANKAN lagi untuk flash sungguhan",
                    "1. choose the HyperOS 4 package folder (the one with images/ and DragonX_Installer.bat)\n" +
                        "2. keep TEST MODE on, then RUN. Test mode only reads the phone and builds a plan, it sends no data\n" +
                        "3. read the step list and CATATAN in the log. If something cannot run, fix it first\n" +
                        "4. turn TEST MODE off, then RUN again for the real flash"
                ),
                12f,
                color = p.warnText
            ).apply { setPadding(0, dp(5), 0, 0) }
        )
        warn.addView(
            textLine(
                tr(
                    "flash bisa menghapus data, jadi backup dulu. Selama flash, jangan matikan layar HP ini dan jangan tekan tombol apa pun.",
                    "flashing can erase data, so back up first. During the flash, do not turn off this phone's screen or press any button."
                ),
                12f,
                color = p.warnText
            ).apply { setPadding(0, dp(8), 0, 0) }
        )
        content.addView(warn)

        // Langkah 1: pilih folder
        val step1 = box()
        step1.addView(caption(tr("1. paket port", "1. port package")))
        step1.addView(neonOutlineBtn(tr("PILIH FOLDER", "CHOOSE FOLDER")) { pickPortFolder() })
        romInfo = textLine("", 12f, color = p.muted, mono = true)
        romInfo.setPadding(0, dp(8), 0, 0)
        step1.addView(romInfo)
        content.addView(step1)

        // Langkah 2: hasil pemeriksaan file
        val step2 = box()
        step2.addView(caption(tr("2. pemeriksaan file", "2. file check")))
        checkText = textLine("", 12f, color = p.muted)
        checkText.setPadding(0, dp(4), 0, 0)
        step2.addView(checkText)
        content.addView(step2)

        // Langkah 3: pilihan
        val step3 = box()
        step3.addView(caption(tr("3. pilihan", "3. options")))
        step3.addView(ujiRow())
        step3.addView(caption(tr("hapus data (wipe)", "erase data (wipe)")))
        wipeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        step3.addView(wipeBox)
        step3.addView(caption(tr("root", "root")))
        rootBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        step3.addView(rootBox)
        step3.addView(caption(tr("reboot setelah selesai", "reboot when finished")))
        rebootBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        step3.addView(rebootBox)
        content.addView(step3)
        fillAllChoices()

        runBtn = btn(tr("JALANKAN", "RUN"), BtnStyle.PRIMARY) { onRunPort() }
        content.addView(runBtn)
        setRunEnabled(false)
        refreshPortCheck()
        refreshUji()

        setContentView(logPage(ScrollView(this).apply { addView(content) }))
    }
}

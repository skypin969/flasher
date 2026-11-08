package com.skypin.flasher

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
    private lateinit var scriptBtn: Button
    private lateinit var dryRunSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val scripts = listScripts(src)
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
            scriptBtn.text = tr("Pilih script", "Choose script")
        }
        log(msg.replace("\n", " | "))
    }

    private fun pickScript() {
        val scripts = romScripts
        if (scripts.isEmpty()) {
            alert(tr("Belum ada paket ROM, atau paket tidak punya script flash.",
                "No ROM package yet, or the package has no flash script."))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Pilih script", "Choose script"))
            .setItems(scripts.toTypedArray<CharSequence>()) { _, which ->
                romScript = scripts[which]
                plan = null
                scriptBtn.text = scripts[which]
                romInfo.text = tr("Paket: $romName\nScript: ${scripts[which]}\nTekan Buat rencana.",
                    "Package: $romName\nScript: ${scripts[which]}\nPress Make plan.")
            }
            .show()
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
            tr("PERINGATAN: flash ROM ini menghapus semua data di ${t.product} (${t.serial}) " +
                "dan menimpa root serta OrangeFox. Lanjutkan?",
                "WARNING: this ROM flash erases all data on ${t.product} (${t.serial}) " +
                    "and overwrites root and OrangeFox. Continue?")
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

    private fun buildUi() {
        val p = pal()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            setBackgroundColor(p.bg)
        }
        content.addView(btn(tr("← Kembali", "← Back"), BtnStyle.SECONDARY) { finish() })
        content.addView(textLine(tr("Flash fastboot ROM tgz", "Flash fastboot ROM tgz"), 22f, medium = true).apply {
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
        step1.addView(caption(tr("1. Paket ROM", "1. ROM package")))
        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bFolder = btn(tr("Folder", "Folder"), BtnStyle.SECONDARY) { pickRomFolder() }
        val bTgz = btn(tr("File .tgz", ".tgz file"), BtnStyle.SECONDARY) { pickFile(REQ_PICK_TGZ) }
        bFolder.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(8)
            marginEnd = dp(6)
        }
        bTgz.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(8)
        }
        pickRow.addView(bFolder)
        pickRow.addView(bTgz)
        step1.addView(pickRow)
        romInfo = textLine(tr("Belum ada paket ROM dipilih", "No ROM package chosen yet"), 12f, color = p.muted, mono = true)
        romInfo.setPadding(0, dp(8), 0, 0)
        step1.addView(romInfo)
        form.addView(step1)

        val step2 = subCard()
        step2.addView(caption(tr("2. Script", "2. Script")))
        scriptBtn = btn(tr("Pilih script", "Choose script"), BtnStyle.SECONDARY) { pickScript() }
        step2.addView(scriptBtn)
        form.addView(step2)

        form.addView(btn(tr("Buat rencana", "Make plan"), BtnStyle.SECONDARY) { onPlanRom() })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val modeText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        modeText.addView(textLine(tr("Mode uji", "Test mode"), 14f, medium = true))
        modeText.addView(textLine(tr("Tidak mengirim data", "Sends no data"), 12f, color = p.muted))
        modeRow.addView(modeText)
        dryRunSwitch = Switch(this).apply { isChecked = true }
        modeRow.addView(dryRunSwitch)
        form.addView(modeRow)

        val warn = box()
        warn.background = rounded(p.warnBg, 10, null)
        warn.addView(textLine(
            tr("Flash asli menghapus semua data dan menimpa root serta OrangeFox.",
                "Real flash erases all data and overwrites root and OrangeFox."),
            12f,
            color = p.warnText
        ))
        form.addView(warn)

        form.addView(btn(tr("Jalankan ROM", "Run ROM"), BtnStyle.DANGER) { onRunRom() })
        content.addView(form)

        setContentView(page(ScrollView(this).apply { addView(content) }, makeLog()))
    }
}

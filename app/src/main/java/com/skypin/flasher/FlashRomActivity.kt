package com.skypin.flasher

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
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
    private lateinit var dryRunBox: CheckBox

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
        runTask("Baca folder ROM") {
            runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setRom(SafRomSource(contentResolver, tree), tree.lastPathSegment ?: "folder")
        }
    }

    private fun loadRomTgz(uri: Uri) {
        runTask("Ekstrak ROM .tgz") {
            val name = queryNameSize(uri).first
            val stem = name.removeSuffix(".tgz").removeSuffix(".tar.gz").removeSuffix(".tar").ifBlank { "rom" }
            val dest = File(getExternalFilesDir(null), "rom/$stem")
            dest.deleteRecursively()
            dest.mkdirs()
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka file .tgz")
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
            "Paket: $name\nTidak ada script flash (flash_all.sh) di paket ini."
        } else {
            "Paket: $name\nScript ditemukan: ${scripts.joinToString(", ")}\nPilih script, lalu tekan Buat rencana."
        }
        runOnUiThread { romInfo.text = msg }
        log(msg.replace("\n", " | "))
    }

    private fun pickScript() {
        val scripts = romScripts
        if (scripts.isEmpty()) {
            alert("Belum ada paket ROM, atau paket tidak punya script flash.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pilih script")
            .setItems(scripts.toTypedArray<CharSequence>()) { _, which ->
                romScript = scripts[which]
                plan = null
                romInfo.text = "Paket: $romName\nScript: ${scripts[which]}\nTekan Buat rencana."
            }
            .show()
    }

    private fun onPlanRom() {
        val t = requireTarget() ?: return
        val src = romSource
        val script = romScript
        if (src == null) {
            alert("Pilih paket ROM dulu.")
            return
        }
        if (script == null) {
            alert("Pilih script dulu.")
            return
        }
        runTask("Buat rencana ROM") {
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
            alert("Buat rencana dulu: pilih paket, pilih script, lalu tekan Buat rencana.")
            return
        }
        if (p.errors.isNotEmpty()) {
            alert("Rencana tidak bisa dijalankan:\n\n" + p.errors.joinToString("\n"))
            return
        }
        val dry = dryRunBox.isChecked
        val msg = if (dry) {
            "Mode uji: tidak ada data yang dikirim ke perangkat. Lanjutkan?"
        } else {
            "PERINGATAN: flash ROM ini menghapus semua data di ${t.product} (${t.serial}) " +
                "dan menimpa root serta OrangeFox. Lanjutkan?"
        }
        confirm(msg) {
            runTask(if (dry) "Mode uji ROM" else "Flash ROM") {
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        content.addView(btn("Kembali") { finish() })
        content.addView(textLine("Flash fastboot ROM tgz", 20f).apply { setTypeface(null, Typeface.BOLD) })
        content.addView(caption("Target: ${Session.target?.product ?: "belum dicek"}"))

        val form = box()
        form.addView(caption("Pilih paket ROM"))
        form.addView(btn("Pilih ROM (folder)") { pickRomFolder() })
        form.addView(btn("Pilih ROM (.tgz)") { pickFile(REQ_PICK_TGZ) })
        romInfo = textLine("Belum ada paket ROM dipilih", 14f)
        form.addView(romInfo)
        form.addView(btn("Pilih script") { pickScript() })
        form.addView(btn("Buat rencana") { onPlanRom() })
        dryRunBox = CheckBox(this).apply {
            text = "Mode uji (tidak mengirim data)"
            isChecked = true
        }
        form.addView(dryRunBox)
        form.addView(btn("Jalankan ROM") { onRunRom() })
        content.addView(form)

        val top = ScrollView(this).apply { addView(content) }
        setContentView(splitScreen(top, makeLog()))
    }
}

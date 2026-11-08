package com.skypin.flasher

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Halaman utama: data perangkat, Cek HP target, dan dua menu flash. */
class MainActivity : FlasherBase() {

    private lateinit var infoCodename: TextView
    private lateinit var infoName: TextView
    private lateinit var infoSerial: TextView
    private lateinit var infoUnlock: TextView
    private lateinit var menuImg: LinearLayout
    private lateinit var menuRom: LinearLayout
    private lateinit var lockNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val t = Session.target
        val ok = t != null
        infoCodename.text = t?.product ?: "belum dicek"
        infoName.text = if (t != null) marketName(t.product) else "belum dicek"
        infoSerial.text = t?.serial ?: "belum dicek"
        infoUnlock.text = if (ok) Session.unlocked else "belum dicek"
        menuImg.alpha = if (ok) 1f else 0.4f
        menuRom.alpha = if (ok) 1f else 0.4f
        lockNote.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun onCheckTarget() {
        runTask("Cek HP target") {
            val (d, unlocked) = withFastboot { fb -> Pair(readDevice(fb), fb.getvar("unlocked")) }
            if (d.product.isBlank() || d.serial.isBlank()) {
                Session.target = null
                throw FastbootException("Codename atau serial tidak terbaca. Semua flash diblokir.")
            }
            Session.target = d
            Session.unlocked = when {
                unlocked == null -> "tidak terbaca"
                unlocked.trim().equals("yes", ignoreCase = true) -> "Ya"
                unlocked.trim().equals("no", ignoreCase = true) -> "Tidak"
                else -> unlocked.trim()
            }
            log("Codename: ${d.product}, serial: ${d.serial}")
            runOnUiThread { refresh() }
        }
    }

    private fun openScreen(target: Class<*>) {
        if (Session.target == null) {
            Toast.makeText(this, "Tekan Cek HP target dulu.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, target))
    }

    private fun menuBox(title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val b = box()
        b.isClickable = true
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        b.addView(textLine(title, 16f).apply { setTypeface(null, Typeface.BOLD) })
        b.addView(textLine(sub, 13f))
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(textLine("Flasher", 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })

        val info = box()
        info.addView(caption("Codename"))
        infoCodename = textLine("", 16f)
        info.addView(infoCodename)
        info.addView(caption("Nama perangkat"))
        infoName = textLine("", 16f)
        info.addView(infoName)
        info.addView(caption("Serial"))
        infoSerial = textLine("", 14f)
        info.addView(infoSerial)
        info.addView(caption("Unlock bootloader"))
        infoUnlock = textLine("", 14f)
        info.addView(infoUnlock)
        root.addView(info)

        val check = box()
        check.addView(textLine("Cek HP target", 15f).apply { setTypeface(null, Typeface.BOLD) })
        check.addView(btn("Cek HP target") { onCheckTarget() })
        root.addView(check)

        menuImg = menuBox("Flash file img", "Flash satu file .img ke slot aktif") {
            openScreen(FlashImgActivity::class.java)
        }
        menuRom = menuBox("Flash fastboot ROM tgz", "ROM lengkap dari folder atau .tgz") {
            openScreen(FlashRomActivity::class.java)
        }
        root.addView(menuImg)
        root.addView(menuRom)

        lockNote = textLine("Tekan Cek HP target dulu sebelum flash.", 13f)
        root.addView(lockNote)

        setContentView(ScrollView(this).apply { addView(root) })
    }
}

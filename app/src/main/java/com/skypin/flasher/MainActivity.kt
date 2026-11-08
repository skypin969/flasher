package com.skypin.flasher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Beranda: setelan bahasa dan mode, data perangkat, Cek HP target, dua menu flash, dan kontak. */
class MainActivity : FlasherBase() {

    private lateinit var statusBadge: TextView
    private lateinit var infoName: TextView
    private lateinit var infoDetail: TextView
    private lateinit var infoUnlock: TextView
    private lateinit var menuImg: View
    private lateinit var menuRom: View
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
        val p = pal()
        statusBadge.text = if (ok) tr("Sudah dicek", "Checked") else tr("Belum dicek", "Not checked")
        statusBadge.setTextColor(if (ok) p.okText else p.muted)
        statusBadge.background = rounded(if (ok) p.ok else p.sub, 999, null)
        infoName.text = if (t != null) marketName(t.product) else tr("Belum dicek", "Not checked")
        infoDetail.text = if (t != null) "${t.product} · ${t.serial}" else "-"
        infoUnlock.text = unlockText()
        menuImg.alpha = if (ok) 1f else 0.4f
        menuRom.alpha = if (ok) 1f else 0.4f
        lockNote.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun unlockText(): String = when {
        Session.target == null -> tr("Belum dicek", "Not checked")
        Session.unlocked == "yes" -> tr("Terbuka", "Unlocked")
        Session.unlocked == "no" -> tr("Terkunci", "Locked")
        Session.unlocked == "unknown" -> tr("Tidak terbaca", "Not readable")
        else -> Session.unlocked
    }

    private fun onCheckTarget() {
        runTask(tr("Cek HP target", "Check target phone")) {
            val (d, unlocked) = withFastboot { fb -> Pair(readDevice(fb), fb.getvar("unlocked")) }
            if (d.product.isBlank() || d.serial.isBlank()) {
                Session.target = null
                throw FastbootException(
                    tr("Codename atau serial tidak terbaca. Semua flash diblokir.",
                        "Codename or serial could not be read. All flashing is blocked.")
                )
            }
            Session.target = d
            Session.unlocked = when {
                unlocked == null -> "unknown"
                unlocked.trim().equals("yes", ignoreCase = true) -> "yes"
                unlocked.trim().equals("no", ignoreCase = true) -> "no"
                else -> unlocked.trim()
            }
            log("Codename: ${d.product}, serial: ${d.serial}")
            runOnUiThread { refresh() }
        }
    }

    private fun openScreen(target: Class<*>) {
        if (Session.target == null) {
            Toast.makeText(this, tr("Tekan Cek HP target dulu.", "Press Check target phone first."), Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, target))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, tr("Tidak ada aplikasi untuk membuka link ini.", "No app can open this link."), Toast.LENGTH_LONG).show()
        }
    }

    private fun busyToast() {
        Toast.makeText(this, tr("Tunggu proses berjalan selesai dulu.", "Wait for the running task to finish."), Toast.LENGTH_SHORT).show()
    }

    private fun changeLang(code: String) {
        if (busy) {
            busyToast()
            return
        }
        Session.lang = code
        Session.save(this)
        recreate()
    }

    private fun toggleTheme() {
        if (busy) {
            busyToast()
            return
        }
        Session.dark = !Session.dark
        Session.save(this)
        recreate()
    }

    /** Pilihan bahasa ID | EN. Yang aktif diberi warna aksen. */
    private fun langSwitch(): LinearLayout {
        val p = pal()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.card, 999, p.border)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        for (code in listOf("id", "en")) {
            val on = Session.lang == code
            val seg = textLine(code.uppercase(), 12f, medium = true, color = if (on) p.onAccent else p.muted, mono = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = if (on) rounded(p.accent, 999, null) else null
                setOnClickListener { changeLang(code) }
            }
            wrap.addView(seg)
        }
        return wrap
    }

    /** Tombol bulan (ke mode gelap) atau matahari (ke mode terang). */
    private fun themeButton(): TextView {
        val p = pal()
        return textLine(if (Session.dark) "☀️" else "🌙", 15f, color = p.text).apply {
            gravity = Gravity.CENTER
            background = rounded(p.card, 999, p.border)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(8) }
            setOnClickListener { toggleTheme() }
        }
    }

    private fun buildUi() {
        val p = pal()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(p.bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(bottom = 12)
            addView(textLine("Flasher", 22f, medium = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(langSwitch())
            addView(themeButton())
        }
        root.addView(header)

        val card = box()
        val cardTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cardTop.addView(textLine(tr("Perangkat target", "Target device"), 12f, color = p.muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        statusBadge = textLine("", 11f).apply { setPadding(dp(8), dp(2), dp(8), dp(2)) }
        cardTop.addView(statusBadge)
        card.addView(cardTop)

        infoName = textLine("", 16f, medium = true).apply { setPadding(0, dp(6), 0, 0) }
        card.addView(infoName)
        infoDetail = textLine("", 12f, color = p.sec, mono = true)
        card.addView(infoDetail)

        val bootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        bootRow.addView(textLine("Bootloader", 12f, color = p.sec).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        infoUnlock = textLine("", 12f, medium = true)
        bootRow.addView(infoUnlock)
        card.addView(bootRow)
        root.addView(card)

        root.addView(btn(tr("Cek HP target", "Check target phone"), BtnStyle.PRIMARY) { onCheckTarget() })

        root.addView(caption(tr("Menu", "Menu")))
        menuImg = rowCard(
            "IMG",
            tr("Flash file img", "Flash img file"),
            tr("Satu file ke slot aktif", "One file to the active slot"),
            p.soft, p.accent
        ) { openScreen(FlashImgActivity::class.java) }
        root.addView(menuImg)
        menuRom = rowCard(
            "ROM",
            tr("Flash fastboot ROM tgz", "Flash fastboot ROM tgz"),
            tr("ROM lengkap dari folder atau .tgz", "Full ROM from a folder or .tgz"),
            p.soft, p.accent
        ) { openScreen(FlashRomActivity::class.java) }
        root.addView(menuRom)

        lockNote = textLine(
            tr("Tekan Cek HP target dulu sebelum flash.", "Press Check target phone before flashing."),
            12f,
            color = p.muted
        )
        root.addView(lockNote)

        root.addView(caption(tr("Kontak", "Contact")))
        root.addView(rowCard("TG", "Telegram", "@Skypin969", p.tgSoft, p.tgIcon) {
            openUrl("https://t.me/Skypin969")
        })
        root.addView(rowCard("GH", "GitHub", "github.com/skypin969", p.ghSoft, p.ghIcon) {
            openUrl("https://github.com/skypin969")
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
    }
}

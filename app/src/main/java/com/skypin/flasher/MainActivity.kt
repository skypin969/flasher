package com.skypin.flasher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Beranda: setelan bahasa, data perangkat, CEK FASTBOOT, menu flash dan ADB sideload, dan kontak. */
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        statusBadge.text = if (ok) tr("Selesai", "Done") else tr("Belum Dicek", "Not checked")
        statusBadge.setTextColor(if (ok) p.okText else p.muted)
        statusBadge.background = rounded(if (ok) p.ok else p.sub, 999, null)
        infoName.text = if (t != null) marketName(t.product) else tr("Belum Dicek", "Not checked")
        infoDetail.text = if (t != null) "${t.product} · ${t.serial}" else "-"
        infoUnlock.text = unlockText()
        menuImg.alpha = if (ok) 1f else 0.4f
        menuRom.alpha = if (ok) 1f else 0.4f
        lockNote.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun unlockText(): String = when {
        Session.target == null -> tr("Belum Dicek", "Not checked")
        Session.unlocked == "yes" -> tr("Terbuka", "Unlocked")
        Session.unlocked == "no" -> tr("Terkunci", "Locked")
        Session.unlocked == "unknown" -> tr("Tidak terbaca", "Not readable")
        else -> Session.unlocked
    }

    private fun onCheckTarget() {
        runTask(tr("CEK FASTBOOT", "CHECK FASTBOOT DEVICE")) {
            val (d, unlocked) = withFastboot(retryOnPermission = { onCheckTarget() }) { fb -> Pair(readDevice(fb), fb.getvar("unlocked")) }
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

    override val autoCheckOnAttach: Boolean get() = true

    /** Colokan fastboot baru diberi izin: langsung CEK FASTBOOT tanpa perlu menekan tombol. */
    override fun onFastbootAttached() {
        onCheckTarget()
    }

    private fun openScreen(target: Class<*>) {
        if (Session.target == null) {
            Toast.makeText(this, tr("Tekan CEK FASTBOOT dulu.", "Press CHECK FASTBOOT DEVICE first."), Toast.LENGTH_LONG).show()
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

    /** Pilihan bahasa ID | EN. Yang aktif diberi gradasi neon. */
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
                background = if (on) neonButtonBackground(this@MainActivity, 999) else null
                setOnClickListener { changeLang(code) }
            }
            wrap.addView(seg)
        }
        return wrap
    }

    /** Garis pinggir gradasi neon dengan isi gelap, sama seperti tombol di layar Flash file img. */
    private fun neonFrame(radiusDp: Int, fill: Int): LayerDrawable {
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

    /** Kartu dengan garis neon, dipakai untuk kartu perangkat. */
    private fun neonCard(): LinearLayout {
        val p = pal()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neonFrame(14, p.card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = lp(bottom = 16)
        }
    }

    /** Baris menu atau kontak dengan garis neon: lencana, judul, keterangan, dan panah. */
    private fun neonRow(
        badge: String,
        badgeFill: Int,
        badgeText: Int,
        title: String,
        sub: String,
        onClick: () -> Unit
    ): LinearLayout {
        val p = pal()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = neonFrame(12, p.card)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = lp(top = 10)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(textLine(badge, 12f, medium = true, color = badgeText).apply {
            gravity = Gravity.CENTER
            background = rounded(badgeFill, 10, null)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(44))
        })
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(textLine(title, 16f, medium = true, color = p.text))
        text.addView(textLine(sub, 13f, color = p.muted))
        row.addView(text)
        row.addView(textLine("›", 22f, color = p.muted))
        return row
    }

    private fun buildUi() {
        val p = pal()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Header: judul bergradasi neon dan pilihan bahasa
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(bottom = 12)
        }
        val title = textLine("√- Flashmate", 22f, medium = true, color = p.text).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        applyNeonText(title)
        header.addView(title)
        header.addView(langSwitch())
        root.addView(header)

        val card = neonCard()
        val cardTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cardTop.addView(textLine(tr("Perangkat", "Device"), 12f, color = p.muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        statusBadge = textLine("", 11f).apply { setPadding(dp(8), dp(2), dp(8), dp(2)) }
        cardTop.addView(statusBadge)
        card.addView(cardTop)

        infoName = textLine("", 16f, medium = true, color = p.text).apply { setPadding(0, dp(6), 0, 0) }
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

        root.addView(btn(tr("CEK FASTBOOT", "CHECK FASTBOOT DEVICE"), BtnStyle.PRIMARY) { onCheckTarget() })

        root.addView(caption(tr("Menu", "Menu")).apply { setTextColor(NeonTheme.MAGENTA) })
        menuImg = neonRow(
            "IMG", p.soft, NeonTheme.MAGENTA,
            "FLASH FILE IMG",
            tr("isi nama partisi, pilih file .img",
                "enter the partition name, pick a .img file")
        ) { openScreen(FlashImgActivity::class.java) }
        root.addView(menuImg)
        menuRom = neonRow(
            "ROM", p.soft, NeonTheme.MAGENTA,
            "FLASH FASTBOOT ROM",
            tr("pilih folder atau .tgz, pilih script, buat rencana, lalu jalankan",
                "pick a folder or .tgz, choose a script, make a plan, then run it")
        ) { openScreen(FlashRomActivity::class.java) }
        root.addView(menuRom)
        // ADB sideload: hanya tampilan dulu, fiturnya belum dikerjakan
        val menuSdl = neonRow(
            "SDL", p.soft, NeonTheme.MAGENTA,
            "ADB SIDELOAD",
            tr("kirim file .zip dari recovery lewat adb", "send a .zip file from recovery over adb")
        ) {
            Toast.makeText(this, tr("Segera hadir", "Coming soon"), Toast.LENGTH_SHORT).show()
        }
        root.addView(menuSdl)

        lockNote = textLine(
            tr("Tekan CEK FASTBOOT dulu sebelum flash.", "Press CHECK FASTBOOT DEVICE before flashing."),
            12f,
            color = p.muted
        )
        root.addView(lockNote)

        root.addView(caption(tr("Kontak", "Contact")).apply { setTextColor(NeonTheme.MAGENTA) })
        root.addView(neonRow("TG", p.tgSoft, p.tgIcon, "Telegram", "@Skypin969") {
            openUrl("https://t.me/Skypin969")
        })
        root.addView(neonRow("GH", p.ghSoft, p.ghIcon, "GitHub", "github.com/skypin969") {
            openUrl("https://github.com/skypin969")
        })

        setContentView(ScrollView(this).apply {
            background = neonBackground()
            addView(root)
        })
    }
}

package com.skypin.flasher

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/** Pengaturan untuk FLASH FASTBOOT ROM. Saat ini hanya Mode Uji. */
object FlashSettings {
    private const val PREFS = "flasher"
    private const val KEY_DRY_RUN = "rom_dry_run"

    /** Mode uji: saat aktif tidak ada data yang dikirim ke perangkat. Jika belum pernah diatur, aktif. */
    fun dryRun(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DRY_RUN, true)

    fun setDryRun(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DRY_RUN, on).apply()
    }
}

/** Halaman PENGATURAN, dibuka dari ikon sliders di FLASH FASTBOOT ROM. */
class FlashSettingsActivity : FlasherBase() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val p = pal()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        content.addView(backButton { finish() })
        content.addView(textLine(tr("PENGATURAN", "SETTINGS"), 22f, medium = true).apply {
            paint.isFakeBoldText = true
            applyNeonText(this)
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(caption(tr("FLASH FASTBOOT ROM", "FLASH FASTBOOT ROM")))

        val card = box()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(textLine(tr("Mode Uji", "Test Mode"), 14f, medium = true, color = p.text))
        textCol.addView(textLine(tr("tidak mengirim data", "sends no data"), 12f, color = p.muted))
        row.addView(textCol)

        val toggle = NeonToggle(this).apply {
            isChecked = FlashSettings.dryRun(this@FlashSettingsActivity)
            // Klik ditangani oleh baris, supaya nilai selalu disimpan
            isClickable = false
        }
        row.addView(toggle)
        row.setOnClickListener {
            val on = !toggle.isChecked
            toggle.isChecked = on
            FlashSettings.setDryRun(this@FlashSettingsActivity, on)
        }
        card.addView(row)

        card.addView(textLine(
            tr("Saat aktif, tidak ada data yang dikirim ke perangkat. Nonaktifkan untuk flash sungguhan.",
                "When on, no data is sent to the device. Turn it off for a real flash."),
            12f,
            color = p.muted
        ).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(card)

        setContentView(page(ScrollView(this).apply { addView(content) }, null))
    }
}

package com.skypin.flasher

import android.content.Context

/** Pengaturan untuk FLASH FASTBOOT ROM. Saat ini hanya Mode Uji. */
object FlashSettings {
    private const val PREFS = "flasher"
    private const val KEY_DRY_RUN = "rom_dry_run"

    /** Mode uji: saat aktif tidak ada data yang dikirim ke perangkat. Jika belum pernah diatur, nonaktif (flash sungguhan). */
    fun dryRun(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DRY_RUN, false)

    fun setDryRun(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DRY_RUN, on).apply()
    }
}

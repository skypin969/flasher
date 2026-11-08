package com.skypin.flasher

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView

// File ini berisi warna dan helper tampilan yang sudah disetujui.
// Belum dicocokkan dengan kode asli di repo. Saat repo sudah bisa dibuka,
// nama dan pemanggilannya dicek dulu sebelum dimasukkan.

object NeonTheme {
    // Latar: gradasi dari icon (kiri atas ke kanan bawah)
    val BG_START: Int = Color.parseColor("#3B3E47")
    val BG_END: Int = Color.parseColor("#1E2128")

    // Kartu dan garis
    val CARD: Int = Color.parseColor("#2A2E37")
    val SUB: Int = Color.parseColor("#343842")
    val BORDER: Int = Color.parseColor("#3F4450")

    // Teks
    val TEXT: Int = Color.parseColor("#E8EAF0")
    val SEC: Int = Color.parseColor("#B9BFCC")
    val MUTED: Int = Color.parseColor("#A0A7B5")

    // Aksen neon dari √-
    val VIOLET: Int = Color.parseColor("#A78BFA")
    val MAGENTA: Int = Color.parseColor("#E879F9")
    val CYAN: Int = Color.parseColor("#3EE6F5")
    val ON_ACCENT: Int = Color.parseColor("#0A0F1A") // teks gelap di atas tombol gradasi

    // Peringatan tetap kuning
    val WARN_BG: Int = Color.parseColor("#4A3D1C")
    val WARN_TEXT: Int = Color.parseColor("#F5CD7A")

    // Bahaya (BATAL tidak memakai ini)
    val DANGER: Int = Color.parseColor("#8C1D18")
    val ON_DANGER: Int = Color.WHITE

    // Latar belakang dialog konfirmasi (gelap 60%)
    val SCRIM: Int = Color.parseColor("#99000000")
}

fun neonDp(context: Context, value: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()

// Latar seluruh layar: gradasi sama dengan icon
fun neonBackground(): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(NeonTheme.BG_START, NeonTheme.BG_END)
    )

// Tombol aksi (CEK FASTBOOT, FLASH SLOT AKTIF, JALANKAN, REBOOT PERANGKAT, FLASH FILE LAIN)
// Pakai bersama ON_ACCENT sebagai warna teks
fun neonButtonBackground(context: Context, radiusDp: Int = 10): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN)
    ).apply {
        cornerRadius = neonDp(context, radiusDp).toFloat()
    }

// Tombol sekunder (BATAL)
fun neonSecondaryButtonBackground(context: Context, radiusDp: Int = 10): GradientDrawable =
    GradientDrawable().apply {
        setColor(NeonTheme.SUB)
        cornerRadius = neonDp(context, radiusDp).toFloat()
    }

// Kartu
fun neonCardBackground(context: Context, radiusDp: Int = 10): GradientDrawable =
    GradientDrawable().apply {
        setColor(NeonTheme.CARD)
        setStroke(neonDp(context, 1), NeonTheme.BORDER)
        cornerRadius = neonDp(context, radiusDp).toFloat()
    }

// Garis tipis di bawah judul (ikut warna neon)
fun neonLineBackground(context: Context): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN)
    ).apply {
        cornerRadius = neonDp(context, 2).toFloat()
    }

// Tulisan "√- Flashmate" dengan gradasi neon.
// Dipanggil setelah teks diisi.
fun applyNeonText(view: TextView) {
    view.post {
        val width = view.paint.measureText(view.text.toString())
        view.paint.shader = LinearGradient(
            0f, 0f, width, 0f,
            intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN),
            null,
            Shader.TileMode.CLAMP
        )
        view.invalidate()
    }
}

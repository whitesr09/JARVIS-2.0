package com.nshd.jarvis2.ui

import android.graphics.Color as AndroidColor

/**
 * D3 — plain-Kotlin colour palettes for surfaces that cannot host
 * Compose (the floating overlay HUD in `FloatingOverlayService`).
 *
 * F4: the entries follow the orb-token ladders in [Theme.kt], which
 * remains the source of truth for the Compose UI. LIGHT / DARK / RED
 * keep the hand-picked HUD values carried over from the previous
 * ladders (panel / ink / accent pairs were contrast-checked when
 * picked: the weakest text pair is ≥ 7.1:1); SUNSET is new in F4;
 * CUSTOM derives from the stored hex by luminance, as before.
 */
data class HudPalette(
    /** Panel fill (the theme surface at ~97% alpha over other apps). */
    val background: Int,
    /** Hairline panel border (outlineVariant). */
    val border: Int,
    /** Goal line (onSurface). */
    val textPrimary: Int,
    /** Step lines (onSurfaceVariant). */
    val textSecondary: Int,
    /** Status line + progress fill (primary). */
    val accent: Int,
    /** Progress track (surfaceVariant). */
    val progressTrack: Int
)

object ThemePalette {

    private const val PANEL_ALPHA = 0xF7 // ~97%

    fun hudPalette(theme: AppTheme, customBgHex: String?): HudPalette {
        val dark = HudPalette(
            background = withAlpha(0x1C1830),
            border = 0xFF322C4E.toInt(),
            textPrimary = 0xFFEDEAF6.toInt(),
            textSecondary = 0xFFC2B8DC.toInt(),
            accent = 0xFFC4B5FD.toInt(),
            progressTrack = 0xFF272141.toInt()
        )
        return when (theme) {
            AppTheme.LIGHT -> HudPalette(
                background = withAlpha(0xFFFFFF),
                border = 0xFFE3E3E8.toInt(),
                textPrimary = 0xFF1B1B1F.toInt(),
                textSecondary = 0xFF4A4A55.toInt(),
                accent = 0xFF6D28D9.toInt(),
                progressTrack = 0xFFECECF1.toInt()
            )
            AppTheme.DARK -> dark
            AppTheme.RED -> HudPalette(
                background = withAlpha(0x221012),
                border = 0xFF3A2222.toInt(),
                textPrimary = 0xFFF6E9E6.toInt(),
                textSecondary = 0xFFE0B3A8.toInt(),
                accent = 0xFFFFB59D.toInt(),
                progressTrack = 0xFF2E1818.toInt()
            )
            AppTheme.SUNSET -> HudPalette(
                background = withAlpha(0xFFFFFF),
                border = 0xFFF3D3DC.toInt(),
                textPrimary = 0xFF4A2340.toInt(),
                textSecondary = 0xFF7A4566.toInt(),
                accent = 0xFFE83E8C.toInt(),
                progressTrack = 0xFFFFE7E1.toInt()
            )
            // CUSTOM derives from the stored hex by luminance; with no
            // parseable hex it falls back to the DARK palette.
            AppTheme.CUSTOM -> parseArgb(customBgHex)
                ?.let { deriveCustomHudPalette(it) } ?: dark
        }
    }

    /**
     * Mirrors `deriveCustomScheme` in Theme.kt: the user's hex sets only
     * the base; the panel surface is a lightness step of it and the ink /
     * accent follow the base's lightness template (light vs dark).
     */
    private fun deriveCustomHudPalette(base: Int): HudPalette {
        return if (AndroidColor.luminance(base) > 0.55f) {
            HudPalette(
                background = withAlphaRgb(blend(base, AndroidColor.WHITE, 0.60f)),
                border = blend(base, AndroidColor.WHITE, 0.35f),
                textPrimary = 0xFF1D1B26.toInt(),
                textSecondary = 0xFF4A3F61.toInt(),
                accent = 0xFF6D28D9.toInt(),
                progressTrack = blend(base, 0xFF6D28D9.toInt(), 0.10f)
            )
        } else {
            HudPalette(
                background = withAlphaRgb(blend(base, AndroidColor.WHITE, 0.07f)),
                border = blend(base, AndroidColor.WHITE, 0.22f),
                textPrimary = 0xFFF2EFFA.toInt(),
                textSecondary = 0xFFCFC6E8.toInt(),
                accent = 0xFFC4B5FD.toInt(),
                progressTrack = blend(base, AndroidColor.WHITE, 0.14f)
            )
        }
    }

    /** Packs a 0xRRGGBB literal with the panel alpha. */
    private fun withAlpha(rgb: Int): Int = withAlphaRgb(rgb)

    private fun withAlphaRgb(color: Int): Int =
        (PANEL_ALPHA shl 24) or (color and 0x00FFFFFF)

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val r = (AndroidColor.red(from) + (AndroidColor.red(to) - AndroidColor.red(from)) * fraction).toInt()
        val g = (AndroidColor.green(from) + (AndroidColor.green(to) - AndroidColor.green(from)) * fraction).toInt()
        val b = (AndroidColor.blue(from) + (AndroidColor.blue(to) - AndroidColor.blue(from)) * fraction).toInt()
        return AndroidColor.rgb(r, g, b)
    }

    private fun parseArgb(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try {
            AndroidColor.parseColor(hex.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

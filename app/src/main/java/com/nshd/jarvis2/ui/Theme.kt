package com.nshd.jarvis2.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nshd.jarvis2.R
import kotlin.math.abs

/**
 * F4 design system — theme tokens taken from the orb reference
 * (`orb.html` THEMES table): each named theme pairs a flat background /
 * surface ladder with the orb's three accent tones (t1/t2/t3 → primary /
 * secondary / tertiary). One typeface (Manrope), friendly rounded
 * geometry, no glass — the only glow in the app is the orb itself.
 *
 * Themes: LIGHT / DARK (default) / RED / SUNSET + CUSTOM, which derives
 * its scheme from the user's stored hex (JarvisSettings.customBgHex)
 * using the orb's HSL derivation. Stored values naming the retired
 * pre-F4 default theme are migrated in MainActivity.loadSettings.
 */
enum class AppTheme { LIGHT, DARK, RED, SUNSET, CUSTOM }

fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (e: IllegalArgumentException) {
        null
    }
}

// ── Typography: Manrope only (plan §3.2 scale, one family) ────────────

val JarvisManrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold)
)

private fun manropeStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight
) = TextStyle(
    fontFamily = JarvisManrope,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight
)

val JarvisTypography = Typography(
    displayLarge = manropeStyle(36, 44, FontWeight.ExtraBold),
    displayMedium = manropeStyle(30, 38, FontWeight.Bold),
    displaySmall = manropeStyle(24, 32, FontWeight.Bold),
    headlineLarge = manropeStyle(28, 36, FontWeight.Bold),
    headlineMedium = manropeStyle(24, 32, FontWeight.SemiBold),
    headlineSmall = manropeStyle(20, 28, FontWeight.SemiBold),
    titleLarge = manropeStyle(20, 28, FontWeight.SemiBold),
    titleMedium = manropeStyle(16, 24, FontWeight.SemiBold),
    titleSmall = manropeStyle(14, 20, FontWeight.SemiBold),
    bodyLarge = manropeStyle(16, 25, FontWeight.Normal),
    bodyMedium = manropeStyle(14, 22, FontWeight.Normal),
    bodySmall = manropeStyle(12, 18, FontWeight.Normal),
    labelLarge = manropeStyle(14, 20, FontWeight.SemiBold),
    labelMedium = manropeStyle(12, 16, FontWeight.SemiBold),
    labelSmall = manropeStyle(11, 14, FontWeight.Medium)
)

// ── Shapes: one radius ladder for the whole app (JarvisDimens) ───────
// extraSmall 8 (inline bits) · small 16 (buttons + text fields) ·
// medium 20 (cards) · large/extraLarge 24 (dialogs, sheets).
// Chips are NOT on this ladder — they stay fully rounded and set
// RoundedCornerShape(50) at their call sites.

val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(JarvisDimens.radiusField),
    medium = RoundedCornerShape(JarvisDimens.radiusCard),
    large = RoundedCornerShape(JarvisDimens.radiusDialog),
    extraLarge = RoundedCornerShape(JarvisDimens.radiusDialog)
)

// ── Colour schemes (orb.html THEMES tokens) ──────────────────────────

/**
 * LIGHT — orb "light": pale blue-grey background #F4F6FB, white surface,
 * ink #1D2340, muted #6B7390, accents #2F6BFF / #8B5CF6 / #C15DE0.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF8B5CF6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAE3FF),
    onSecondaryContainer = Color(0xFF33156E),
    tertiary = Color(0xFFC15DE0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6DFFB),
    onTertiaryContainer = Color(0xFF4A1060),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF1D2340),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D2340),
    surfaceVariant = Color(0xFFECEFF7),
    onSurfaceVariant = Color(0xFF6B7390),
    outline = Color(0xFFD8DCEA),
    outlineVariant = Color(0xFFE4E8F2),
    inverseSurface = Color(0xFF1D2340),
    inverseOnSurface = Color(0xFFF4F6FB),
    inversePrimary = Color(0xFFA9C2FF)
)

/**
 * DARK — orb "dark" (the app default): deep navy background #0A0D1F,
 * surface #151A36, ink #E8ECFF, muted #8F98C8, accents #5B8CFF /
 * #9B7BFF / #4FE3FF.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5B8CFF),
    onPrimary = Color(0xFF0A1836),
    primaryContainer = Color(0xFF1E3266),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFF9B7BFF),
    onSecondary = Color(0xFF1E0F45),
    secondaryContainer = Color(0xFF32245E),
    onSecondaryContainer = Color(0xFFE3D9FF),
    tertiary = Color(0xFF4FE3FF),
    onTertiary = Color(0xFF00323D),
    tertiaryContainer = Color(0xFF0E3A47),
    onTertiaryContainer = Color(0xFFC8F4FF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0A0D1F),
    onBackground = Color(0xFFE8ECFF),
    surface = Color(0xFF151A36),
    onSurface = Color(0xFFE8ECFF),
    surfaceVariant = Color(0xFF1C2242),
    onSurfaceVariant = Color(0xFF8F98C8),
    outline = Color(0xFF434B82),
    outlineVariant = Color(0xFF2A3160),
    inverseSurface = Color(0xFFE8ECFF),
    inverseOnSurface = Color(0xFF151A36),
    inversePrimary = Color(0xFF2F6BFF)
)

/**
 * RED — orb "red": near-black maroon background #120508, surface
 * #1F0A10, ink #FFE8EA, muted #C98A92, accents #FF4D5E / #FF2D6F /
 * #FF8A5C. Dark scheme (like DARK).
 */
private val RedScheme = darkColorScheme(
    primary = Color(0xFFFF4D5E),
    onPrimary = Color(0xFF25040A),
    primaryContainer = Color(0xFF4A1420),
    onPrimaryContainer = Color(0xFFFFD9DD),
    secondary = Color(0xFFFF2D6F),
    onSecondary = Color(0xFF2A0410),
    secondaryContainer = Color(0xFF47102A),
    onSecondaryContainer = Color(0xFFFFD3E2),
    tertiary = Color(0xFFFF8A5C),
    onTertiary = Color(0xFF331100),
    tertiaryContainer = Color(0xFF47220F),
    onTertiaryContainer = Color(0xFFFFE0D1),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF120508),
    onBackground = Color(0xFFFFE8EA),
    surface = Color(0xFF1F0A10),
    onSurface = Color(0xFFFFE8EA),
    surfaceVariant = Color(0xFF2E1218),
    onSurfaceVariant = Color(0xFFC98A92),
    outline = Color(0xFF5C3038),
    outlineVariant = Color(0xFF472028),
    inverseSurface = Color(0xFFFFE8EA),
    inverseOnSurface = Color(0xFF1F0A10),
    inversePrimary = Color(0xFFD7263D)
)

/**
 * SUNSET — orb "sunset": warm cream background #FFF6EF, white surface,
 * plum ink #4A2340, muted #98677F, accents #FF6A3D / #E83E8C / #8B5CF6.
 * Light scheme (like LIGHT).
 */
private val SunsetScheme = lightColorScheme(
    primary = Color(0xFFFF6A3D),
    onPrimary = Color(0xFF3D1503),
    primaryContainer = Color(0xFFFFE0D1),
    onPrimaryContainer = Color(0xFF5A1E05),
    secondary = Color(0xFFE83E8C),
    onSecondary = Color(0xFF33081D),
    secondaryContainer = Color(0xFFFFD9E8),
    onSecondaryContainer = Color(0xFF5C0F31),
    tertiary = Color(0xFF8B5CF6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEAE3FF),
    onTertiaryContainer = Color(0xFF33156E),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFF6EF),
    onBackground = Color(0xFF4A2340),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF4A2340),
    surfaceVariant = Color(0xFFFFE7E1),
    onSurfaceVariant = Color(0xFF98677F),
    outline = Color(0xFFE3B3C1),
    outlineVariant = Color(0xFFF3D3DC),
    inverseSurface = Color(0xFF4A2340),
    inverseOnSurface = Color(0xFFFFF6EF),
    inversePrimary = Color(0xFFFF9E7A)
)

// ── Custom hex derivation (orb.html `applyTheme(name, custom)`) ─────

/** sRGB → HSL, hue in turns (0..1), matching the orb's THREE.Color HSL. */
private fun Color.toHsl(): Triple<Float, Float, Float> {
    val maxC = maxOf(red, green, blue)
    val minC = minOf(red, green, blue)
    val l = (maxC + minC) / 2f
    if (maxC == minC) return Triple(0f, 0f, l)
    val d = maxC - minC
    val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
    val h = when (maxC) {
        red -> (green - blue) / d + if (green < blue) 6f else 0f
        green -> (blue - red) / d + 2f
        else -> (red - green) / d + 4f
    } / 6f
    return Triple(h, s, l)
}

/** HSL (hue in turns, wraps) → sRGB colour, the orb's `setHSL` rules. */
private fun hslColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 1f) + 1f) % 1f
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((hue * 6f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        hue < 1f / 6f -> Triple(c, x, 0f)
        hue < 2f / 6f -> Triple(x, c, 0f)
        hue < 3f / 6f -> Triple(0f, c, x)
        hue < 4f / 6f -> Triple(0f, x, c)
        hue < 5f / 6f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m)
}

/** Legible ink on a vivid derived accent: dark on light accents, white on deep ones. */
private fun inkFor(accent: Color): Color =
    if (accent.luminance() > 0.30f) Color(0xFF141126) else Color.White

/**
 * Custom hex: the user's hex sets ONLY the base. The ladder is derived
 * from it — surface and variant are lightness steps of the base (never
 * the same colour as the background), ink auto-picks by luminance
 * (light template above 0.55, dark template below), and the accent
 * family follows the orb's HSL derivation of the hex:
 * main = HSL(h, max(.85, s), clamp(l, .45, .65)),
 * acc1 = HSL(h + .08, .95, .65), acc2 = HSL(h − .08, .95, .70)
 * → primary / secondary / tertiary.
 */
private fun deriveCustomScheme(base: Color): androidx.compose.material3.ColorScheme {
    val (h, s, l) = base.toHsl()
    val main = hslColor(h, maxOf(0.85f, s), l.coerceIn(0.45f, 0.65f))
    val acc1 = hslColor(h + 0.08f, 0.95f, 0.65f)
    val acc2 = hslColor(h - 0.08f, 0.95f, 0.70f)
    return if (base.luminance() > 0.55f) {
        LightScheme.copy(
            primary = main,
            onPrimary = inkFor(main),
            primaryContainer = lerp(main, Color.White, 0.80f),
            onPrimaryContainer = hslColor(h, maxOf(s, 0.60f), 0.16f),
            secondary = acc1,
            onSecondary = inkFor(acc1),
            secondaryContainer = lerp(acc1, Color.White, 0.80f),
            onSecondaryContainer = hslColor(h + 0.08f, 0.95f, 0.16f),
            tertiary = acc2,
            onTertiary = inkFor(acc2),
            tertiaryContainer = lerp(acc2, Color.White, 0.80f),
            onTertiaryContainer = hslColor(h - 0.08f, 0.95f, 0.16f),
            background = base,
            surface = lerp(base, Color.White, 0.60f),
            surfaceVariant = lerp(base, main, 0.10f),
            onBackground = Color(0xFF1D1B26),
            onSurface = Color(0xFF1D1B26),
            onSurfaceVariant = Color(0xFF4A3F61),
            outlineVariant = lerp(base, Color.White, 0.35f)
        )
    } else {
        DarkScheme.copy(
            primary = main,
            onPrimary = inkFor(main),
            primaryContainer = lerp(main, Color.Black, 0.55f),
            onPrimaryContainer = hslColor(h, maxOf(s, 0.60f), 0.88f),
            secondary = acc1,
            onSecondary = inkFor(acc1),
            secondaryContainer = lerp(acc1, Color.Black, 0.55f),
            onSecondaryContainer = hslColor(h + 0.08f, 0.95f, 0.88f),
            tertiary = acc2,
            onTertiary = inkFor(acc2),
            tertiaryContainer = lerp(acc2, Color.Black, 0.55f),
            onTertiaryContainer = hslColor(h - 0.08f, 0.95f, 0.88f),
            background = base,
            surface = lerp(base, Color.White, 0.07f),
            surfaceVariant = lerp(base, Color.White, 0.14f),
            onBackground = Color(0xFFF2EFFA),
            onSurface = Color(0xFFF2EFFA),
            onSurfaceVariant = Color(0xFFCFC6E8),
            outlineVariant = lerp(base, Color.White, 0.22f)
        )
    }
}

@Composable
fun JarvisTheme(
    theme: AppTheme,
    customBgHex: String?,
    content: @Composable () -> Unit
) {
    val scheme = when (theme) {
        AppTheme.LIGHT -> LightScheme
        AppTheme.DARK -> DarkScheme
        AppTheme.RED -> RedScheme
        AppTheme.SUNSET -> SunsetScheme
        // CUSTOM derives from the stored hex; with no parseable hex it
        // falls back to the DARK default rather than crashing the UI.
        AppTheme.CUSTOM -> parseHexColor(customBgHex)
            ?.let { deriveCustomScheme(it) } ?: DarkScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content
    )
}

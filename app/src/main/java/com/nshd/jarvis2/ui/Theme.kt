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

/**
 * D1 design system — **Warm Minimal + Purple** (DESIGN-PLAN.md tokens and
 * type scale, Direction C treatment): FLAT white cards on calm tinted
 * backgrounds, very soft shadows, ONE typeface (Manrope), friendly
 * rounded geometry. No glass, no gradient ambience — the only glow in
 * the app is the soft purple radial behind the orb (drawn by OrbView
 * from `primary`/`secondary`).
 *
 * Themes: PURPLE (default) / LIGHT / DARK / RED + a user custom hex.
 * The pre-D1 SUNSET theme was retired in favour of PURPLE; stored
 * "SUNSET" values are migrated in MainActivity.loadSettings.
 */
enum class AppTheme { PURPLE, LIGHT, DARK, RED }

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

// ── Colour schemes (flat Warm Minimal ladders, OKLCH-stepped) ────────

/**
 * PURPLE — the default. Lavender-tinted calm background, white surfaces,
 * violet primary. #6D28D9 measures 7.10:1 on white (AA + AAA-large);
 * white on it is also 7.10:1, so button labels are safe at any size.
 */
private val PurpleScheme = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E2FB),
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Color(0xFF7C3AED), // 5.70:1 on white — accents, orb mid-tones
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0EAFB),
    onSecondaryContainer = Color(0xFF3B1470),
    tertiary = Color(0xFF9333EA),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E8FD),
    onTertiaryContainer = Color(0xFF430A78),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF1D1B26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1B26),
    surfaceVariant = Color(0xFFF0EBFA),
    onSurfaceVariant = Color(0xFF4A3F61),
    outline = Color(0xFFCFC8E3),
    outlineVariant = Color(0xFFE6E1F4),
    inverseSurface = Color(0xFF241A33), // deep plum — snackbar pill
    inverseOnSurface = Color(0xFFF4EFFC),
    inversePrimary = Color(0xFFC4B5FD)
)

/** LIGHT — neutral warm white; the purple accent carries the identity. */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E2FB),
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0EAFB),
    onSecondaryContainer = Color(0xFF3B1470),
    tertiary = Color(0xFF9333EA),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E8FD),
    onTertiaryContainer = Color(0xFF430A78),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFF8F8FA),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFF4A4A55),
    outline = Color(0xFFC6C6CD),
    outlineVariant = Color(0xFFE3E3E8),
    inverseSurface = Color(0xFF241A33),
    inverseOnSurface = Color(0xFFF4EFFC),
    inversePrimary = Color(0xFFC4B5FD)
)

/**
 * DARK — deep plum-charcoal, flat (no glass). The primary lightens to
 * lavender #C4B5FD: 10.1:1 on the background, and deep-plum text on it
 * is 8.9:1, so filled buttons stay legible both ways.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC4B5FD),
    onPrimary = Color(0xFF2E1065),
    primaryContainer = Color(0xFF3D2B66),
    onPrimaryContainer = Color(0xFFE4DBFF),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF2A1650),
    secondaryContainer = Color(0xFF2E2450),
    onSecondaryContainer = Color(0xFFDCD2FF),
    tertiary = Color(0xFFD8B4FE),
    onTertiary = Color(0xFF3A0A63),
    tertiaryContainer = Color(0xFF3D2B52),
    onTertiaryContainer = Color(0xFFF0E4FF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131022),
    onBackground = Color(0xFFEDEAF6),
    surface = Color(0xFF1C1830),
    onSurface = Color(0xFFEDEAF6),
    surfaceVariant = Color(0xFF272141),
    onSurfaceVariant = Color(0xFFC2B8DC),
    outline = Color(0xFF4A4370),
    outlineVariant = Color(0xFF322C4E),
    inverseSurface = Color(0xFFEDEAF6),
    inverseOnSurface = Color(0xFF241A33),
    inversePrimary = Color(0xFF6D28D9)
)

/** RED — calm warm red family, flat dark maroon (kept dark like pre-D1). */
private val RedScheme = darkColorScheme(
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF4A1503),
    primaryContainer = Color(0xFF5C2317),
    onPrimaryContainer = Color(0xFFFFDAD0),
    secondary = Color(0xFFFF8A75),
    onSecondary = Color(0xFF4A0E04),
    secondaryContainer = Color(0xFF47201A),
    onSecondaryContainer = Color(0xFFFFD9D0),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF3D2800),
    tertiaryContainer = Color(0xFF4A3208),
    onTertiaryContainer = Color(0xFFFFE9B8),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF170B0B),
    onBackground = Color(0xFFF6E9E6),
    surface = Color(0xFF221012),
    onSurface = Color(0xFFF6E9E6),
    surfaceVariant = Color(0xFF2E1818),
    onSurfaceVariant = Color(0xFFE0B3A8),
    outline = Color(0xFF5A3532),
    outlineVariant = Color(0xFF3A2222),
    inverseSurface = Color(0xFFF6E9E6),
    inverseOnSurface = Color(0xFF2B1210),
    inversePrimary = Color(0xFFA62B1F)
)

/**
 * Custom hex (plan rule, adapted): the user's hex sets ONLY the base.
 * The ladder is derived from it on the Warm Minimal tokens — surface and
 * variant are lightness steps of the base (never the same colour as the
 * background; the pre-D1 code collapsed both and killed all layering),
 * ink auto-picks by luminance, and the accent family follows the base's
 * lightness (purple template for light bases, dark template for dark).
 */
private fun deriveCustomScheme(base: Color): androidx.compose.material3.ColorScheme {
    return if (base.luminance() > 0.55f) {
        PurpleScheme.copy(
            background = base,
            surface = lerp(base, Color.White, 0.60f),
            surfaceVariant = lerp(base, PurpleScheme.primary, 0.10f),
            onBackground = Color(0xFF1D1B26),
            onSurface = Color(0xFF1D1B26),
            onSurfaceVariant = Color(0xFF4A3F61),
            outlineVariant = lerp(base, Color.White, 0.35f)
        )
    } else {
        DarkScheme.copy(
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
    val custom = parseHexColor(customBgHex)
    val scheme = if (custom != null) {
        deriveCustomScheme(custom)
    } else {
        when (theme) {
            AppTheme.PURPLE -> PurpleScheme
            AppTheme.LIGHT -> LightScheme
            AppTheme.DARK -> DarkScheme
            AppTheme.RED -> RedScheme
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content
    )
}

package com.nshd.jarvis2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Theme presets from the reference blueprint (Page 15) + custom background hex. */
enum class AppTheme { SUNSET, LIGHT, DARK, RED }

fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val SunsetScheme = lightColorScheme(
    primary = Color(0xFFCE470F), // deepened coral — passes contrast on peach (simulator WCAG fix)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9C7),
    onPrimaryContainer = Color(0xFF5C1E00),
    secondary = Color(0xFF8A5A3B),
    background = Color(0xFFFFF3EA),
    onBackground = Color(0xFF2B1608),
    surface = Color(0xFFFFF9F4),
    onSurface = Color(0xFF2B1608),
    surfaceVariant = Color(0xFFFFE4D3),
    onSurfaceVariant = Color(0xFF6B4A35)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFCE470F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9C7),
    onPrimaryContainer = Color(0xFF5C1E00),
    secondary = Color(0xFF5F6B7A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFF7F7F9),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFF5A5A63)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF8A5C),
    onPrimary = Color(0xFF331100),
    primaryContainer = Color(0xFF5C2A12),
    onPrimaryContainer = Color(0xFFFFD9C7),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0A0E1A),
    onBackground = Color(0xFFE8ECF5),
    surface = Color(0xFF141A2E),
    onSurface = Color(0xFFE8ECF5),
    surfaceVariant = Color(0xFF1E2742),
    onSurfaceVariant = Color(0xFFA9B4CC)
)

private val RedScheme = darkColorScheme(
    primary = Color(0xFFFF6B5E),
    onPrimary = Color(0xFF330D0A),
    primaryContainer = Color(0xFF5C1710),
    onPrimaryContainer = Color(0xFFFFD9D3),
    secondary = Color(0xFFFFB59E),
    background = Color(0xFF1A0505),
    onBackground = Color(0xFFF7E8E4),
    surface = Color(0xFF2A0B0B),
    onSurface = Color(0xFFF7E8E4),
    surfaceVariant = Color(0xFF3D1212),
    onSurfaceVariant = Color(0xFFD9A79E)
)

@Composable
fun JarvisTheme(
    theme: AppTheme,
    customBgHex: String?,
    content: @Composable () -> Unit
) {
    val base = when (theme) {
        AppTheme.SUNSET -> SunsetScheme
        AppTheme.LIGHT -> LightScheme
        AppTheme.DARK -> DarkScheme
        AppTheme.RED -> RedScheme
    }
    val custom = parseHexColor(customBgHex)
    val scheme = if (custom != null) {
        base.copy(background = custom, surface = custom)
    } else {
        base
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

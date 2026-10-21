package com.nshd.jarvis2.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the D1 refresh — **Warm Minimal** direction with the
 * purple palette (DESIGN-PLAN.md structure, Direction C treatment):
 * flat surfaces, calm neutrals, one typeface (Manrope), friendly rounded
 * geometry. Screens should take spacing/radius/elevation from here (or
 * from `MaterialTheme.shapes`, which mirrors the same scale) instead of
 * scattering literals — the audit found 7 ad-hoc radii and paddings from
 * 12–32dp across the pre-D1 UI.
 */
object JarvisDimens {
    // Spacing — 4pt scale (plan §3.3).
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 20.dp
    val space2xl = 24.dp
    val space3xl = 32.dp

    /** Standard screen horizontal padding. */
    val screenPadding = 20.dp

    /** Gap between sections on a scrolling screen. */
    val sectionGap = 24.dp

    /** Internal padding of a standard card. */
    val cardPadding = 16.dp

    // Radius scale — mirrored by MaterialTheme.shapes in JarvisTheme:
    // buttons/fields use shapes.small (16), cards shapes.medium (20),
    // dialogs shapes.extraLarge (24). Chips are fully rounded.
    val radiusButton = 16.dp
    val radiusField = 16.dp
    val radiusCard = 20.dp
    val radiusDialog = 24.dp

    // Elevation — Warm Minimal is FLAT: cards sit at a whisper of warm
    // shadow, never the old uniform 2dp grey. Only the FAB floats.
    val cardElevation = 1.dp
    val heroElevation = 2.dp
    val fabElevation = 6.dp
    val navElevation = 10.dp
}

/**
 * Semantic colours Material 3 has no roles for. Error uses the scheme's
 * error family; success/warning live here (plan §3.1 values, which pass
 * AA on the light surfaces; the *Bright variants are for dark themes).
 */
object JarvisSemanticColors {
    val success = Color(0xFF1E7B34) // ≈5.4:1 on white
    val successContainer = Color(0xFFDDF2E2)
    val warning = Color(0xFF9A6206) // ≈4.9:1 on white
    val warningContainer = Color(0xFFFBEED3)
    val successBright = Color(0xFF4ADE80)
    val warningBright = Color(0xFFFBBF24)
}

package com.nshd.jarvis2.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tone language for [JarvisPill] — one status vocabulary app-wide. */
enum class PillTone { SUCCESS, WARNING, ERROR, PRIMARY, NEUTRAL }

/**
 * Small rounded status pill (D2): tinted container + matching text +
 * optional leading icon. Status is never carried by colour alone —
 * callers pass a label, and usually an icon too. On dark themes the
 * success/warning tones switch to the *Bright semantic variants over a
 * translucent wash so they stay readable on plum/maroon surfaces.
 */
@Composable
fun JarvisPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.35f
    val colors: Pair<Color, Color> = when (tone) {
        PillTone.SUCCESS ->
            if (dark) {
                JarvisSemanticColors.successBright.copy(alpha = 0.18f) to
                    JarvisSemanticColors.successBright
            } else {
                JarvisSemanticColors.successContainer to JarvisSemanticColors.success
            }
        PillTone.WARNING ->
            if (dark) {
                JarvisSemanticColors.warningBright.copy(alpha = 0.18f) to
                    JarvisSemanticColors.warningBright
            } else {
                JarvisSemanticColors.warningContainer to JarvisSemanticColors.warning
            }
        PillTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        PillTone.PRIMARY -> scheme.primaryContainer to scheme.onPrimaryContainer
        PillTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = colors.first,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.second,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = colors.second
            )
        }
    }
}

/**
 * Section header style for the D2 screens: labelLarge, letterspaced,
 * muted — one rhythm for "Active provider", "Choose an avatar",
 * Settings groups, etc.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.9.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

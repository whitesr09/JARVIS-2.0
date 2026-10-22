package com.nshd.jarvis2.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
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

/**
 * F4 glass card: flat [androidx.compose.material3.ColorScheme.surface]
 * fill, 24dp corners, 1dp outlineVariant hairline border, 16dp inner
 * padding. The one card treatment for the restructured Settings and
 * Hands-Free screens.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/** 44dp rounded-square icon tile used at the start of glass rows. */
@Composable
fun IconSquare(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(13.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * One row inside a [GlassCard]: [IconSquare] + title/subtitle + either a
 * caller-supplied [trailing] slot or (when [onClick] is set) a chevron.
 * [showDivider] draws the hairline under the row, indented to the text.
 */
@Composable
fun GlassRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSquare(icon = icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 58.dp)
            )
        }
    }
}

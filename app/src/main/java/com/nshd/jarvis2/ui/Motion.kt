package com.nshd.jarvis2.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * D2 motion tokens (DESIGN-PLAN.md §6, adapted to Warm Minimal):
 * three durations + two easings, transform/opacity only. Every animated
 * surface in the app should take its timing from here instead of
 * scattering millisecond literals.
 */
object JarvisMotion {
    /** Press feedback, chip selection, small fades. */
    const val DUR_FAST = 150

    /** Dialogs, list entry, card state changes. */
    const val DUR_MED = 250

    /** Screen transitions. */
    const val DUR_SLOW = 400

    /** Material emphasized easing — decelerates into place. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** General-purpose standard easing. */
    val Standard: Easing = FastOutSlowInEasing

    fun <T> tweenFast() = tween<T>(DUR_FAST, easing = Emphasized)
    fun <T> tweenMed() = tween<T>(DUR_MED, easing = Emphasized)
    fun <T> tweenSlow() = tween<T>(DUR_SLOW, easing = Emphasized)
}

/**
 * True when the system animator duration scale is 0 (the user or an
 * accessibility service has turned animations off). Read once per
 * composition of the caller; the value only changes with a settings
 * change that restarts the activity in practice.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        scale == 0f
    }
}

/**
 * Cheap first-appearance animation for list items: fade in while rising
 * 8dp, with an optional per-index stagger (capped so long lists don't
 * cascade). Reduced motion renders the content statically.
 */
@Composable
fun AppearOnEntry(
    index: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (rememberReducedMotion()) {
        Box(modifier = modifier) { content() }
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, 7) * 30L)
        visible = true
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = JarvisMotion.tweenMed(),
        label = "appearOnEntry"
    )
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 8.dp.toPx()
        }
    ) {
        content()
    }
}

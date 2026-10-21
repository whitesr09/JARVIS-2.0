package com.nshd.jarvis2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Particle orb — Compose Canvas port of the simulator's state machine
 * (extracted from HomeScreen in Level 3 so Home and the Voice screen
 * share one orb):
 *  - idle      = slow breathing
 *  - listening = faster, larger pulse
 *  - speaking  = fastest phase + stronger wobble (waveform feel)
 *  - [amplitude] (0..1, live mic/playback RMS) swells the whole orb and
 *    fattens the particles, so it visibly reacts to the actual voice.
 */
@Composable
fun ParticleOrb(
    listening: Boolean,
    modifier: Modifier = Modifier.size(230.dp),
    amplitude: Float = 0f,
    speaking: Boolean = false
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(listening, speaking) {
        while (true) {
            withFrameMillis {
                phase += when {
                    speaking -> 0.06f
                    listening -> 0.045f
                    else -> 0.014f
                }
            }
        }
    }

    val points = remember {
        val goldenAngle = 2.399963f
        List(140) { i ->
            val frac = (i + 0.5f) / 140f
            Triple(i * goldenAngle, sqrt(frac), 1.4f + (i % 5) * 0.35f)
        }
    }

    val amp = amplitude.coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = size.minDimension / 2f * 0.92f
        val breathe = 1f + 0.035f * sin(phase * 1.6f)
        val pulse = if (listening) 1f + 0.05f * sin(phase * 4.2f) else 1f
        val ampSwell = 1f + amp * 0.22f

        // Soft core glow
        drawCircle(
            color = primary.copy(alpha = 0.10f + amp * 0.10f),
            radius = baseR * 0.62f * breathe * ampSwell,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = primary.copy(alpha = 0.14f + amp * 0.12f),
            radius = baseR * 0.38f * breathe * pulse * ampSwell,
            center = Offset(cx, cy)
        )

        points.forEachIndexed { index, (angle, frac, dotSize) ->
            val wobbleAmp = if (speaking) 0.09f else 0.045f
            val wobble = 1f + wobbleAmp * sin(phase * 2.2f + angle * 3f) + amp * 0.10f * sin(angle * 5f + phase * 3f)
            val r = baseR * frac * breathe * pulse * ampSwell * wobble
            val x = cx + (r * cos(angle + phase * 0.12f))
            val y = cy + (r * sin(angle + phase * 0.12f))
            val color = if (index % 4 == 0) secondary else primary
            val alpha = 0.25f + 0.65f * (1f - frac)
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dotSize * (if (listening) 1.25f else 1f) * (1f + amp * 0.6f),
                center = Offset(x, y)
            )
        }
    }
}

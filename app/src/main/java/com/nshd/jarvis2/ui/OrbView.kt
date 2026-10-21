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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Particle orb — Compose Canvas port of the simulator's state machine
 * (extracted from HomeScreen in Level 3 so Home and the Voice screen
 * share one orb):
 *  - idle      = slow breathing, primary at a calm alpha
 *  - listening = faster, larger pulse, primary at full strength
 *  - speaking  = fastest phase + stronger wobble (waveform feel),
 *    with more secondary/lavender in the mix
 *  - thinking  = slow swirl: faster rotation, tighter radius, tertiary mix
 *  - error     = every particle takes the scheme's error colour
 *  - [amplitude] (0..1, live mic/playback RMS) swells the whole orb and
 *    fattens the particles, so it visibly reacts to the actual voice.
 *
 * D3 polish: the amplitude is low-passed (fast attack, slow release) so
 * the orb breathes instead of jittering; a soft radial glow disc sits
 * behind the particles ([showGlow] — the one ambience Warm Minimal
 * allows); with system reduced motion on, the phase never advances and
 * the orb renders a static frame at half amplitude response.
 */
@Composable
fun ParticleOrb(
    listening: Boolean,
    modifier: Modifier = Modifier.size(230.dp),
    amplitude: Float = 0f,
    speaking: Boolean = false,
    thinking: Boolean = false,
    error: Boolean = false,
    showGlow: Boolean = true
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val reducedMotion = rememberReducedMotion()
    var phase by remember { mutableFloatStateOf(0f) }
    var smoothedAmp by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(listening, speaking, thinking, reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (true) {
            withFrameMillis {
                phase += when {
                    speaking -> 0.06f
                    listening -> 0.045f
                    thinking -> 0.03f
                    else -> 0.014f
                }
                // Low-pass the amplitude: chase a rising signal quickly
                // (attack 0.35) and let it fall slowly (release 0.08), so
                // the swell feels like breathing, not jitter.
                val target = amplitude.coerceIn(0f, 1f)
                val coeff = if (target > smoothedAmp) 0.35f else 0.08f
                smoothedAmp += (target - smoothedAmp) * coeff
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

    // Reduced motion: no loop runs, so respond to the raw amplitude at
    // half strength in a static frame.
    val amp = if (reducedMotion) amplitude.coerceIn(0f, 1f) * 0.5f else smoothedAmp
    val idle = !listening && !speaking && !thinking && !error

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = size.minDimension / 2f * 0.92f
        val breathe = 1f + 0.035f * sin(phase * 1.6f)
        val pulse = if (listening) 1f + 0.05f * sin(phase * 4.2f) else 1f
        val ampSwell = 1f + amp * 0.22f
        val tint = if (error) errorColor else primary

        // Soft radial glow disc behind everything (D3) — primary at 12%
        // fading to transparent; thinking/error tint it with their state.
        if (showGlow) {
            val glowRadius = baseR * 1.3f * breathe * ampSwell
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.12f + amp * 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = Offset(cx, cy)
            )
        }

        // Soft core glow
        drawCircle(
            color = tint.copy(alpha = 0.10f + amp * 0.10f),
            radius = baseR * 0.62f * breathe * ampSwell,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = tint.copy(alpha = 0.14f + amp * 0.12f),
            radius = baseR * 0.38f * breathe * pulse * ampSwell,
            center = Offset(cx, cy)
        )

        // Thinking swirls: quicker rotation on a tighter radius.
        val rotation = phase * if (thinking) 0.55f else 0.12f
        val radiusFactor = if (thinking) 0.82f else 1f
        val calmAlpha = if (idle) 0.85f else 1f

        points.forEachIndexed { index, (angle, frac, dotSize) ->
            val wobbleAmp = if (speaking) 0.09f else 0.045f
            val wobble = 1f + wobbleAmp * sin(phase * 2.2f + angle * 3f) + amp * 0.10f * sin(angle * 5f + phase * 3f)
            val r = baseR * frac * radiusFactor * breathe * pulse * ampSwell * wobble
            val x = cx + (r * cos(angle + rotation))
            val y = cy + (r * sin(angle + rotation))
            val color = when {
                error -> errorColor
                speaking -> if (index % 3 == 0) secondary else primary
                thinking -> if (index % 3 == 0) tertiary else primary
                else -> if (index % 4 == 0) secondary else primary
            }
            val alpha = (0.25f + 0.65f * (1f - frac)) * calmAlpha
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dotSize * (if (listening) 1.25f else 1f) * (1f + amp * 0.6f),
                center = Offset(x, y)
            )
        }
    }
}

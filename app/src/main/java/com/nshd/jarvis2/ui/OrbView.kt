package com.nshd.jarvis2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nshd.jarvis2.voice.VoiceStatus
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * F4 — the Jarvis orb, definitive Compose Canvas port of the supplied
 * `orb_0_1tbg.html` reference (this file replaces the deleted OpenGL
 * implementation wholesale; nothing GPU-based remains).
 *
 * What is ported, following the HTML update loop:
 *  - 2,400 particles on a Fibonacci sphere (golden angle 2.39996),
 *    shell 0.93–1.0, per-particle random; the vertex shader's
 *    sin-product noise displacement on the ROTATED direction, depth
 *    perspective (weak, f = 3.2), depth-based size / alpha and the
 *    BLUE→CYAN→violet→DEEP colour mixing chain with smoothstep.
 *  - The state machine rows {idle, listening, thinking, speaking}
 *    × {scale, speed, cyan, violet, bright, glow, ring, wob}, lerped
 *    with k = 1 − exp(−dt × 3.5). ERROR reuses the speaking row and
 *    mixes the whole palette toward error red by 0.72.
 *  - Audio level / bass / high smoothed with attack 18 /s, release
 *    6 /s. With a live feed ([JarvisOrb]'s liveAudio / bass / high)
 *    the caller's values are the targets; otherwise the HTML
 *    simulated per-state patterns run. Effective amp for particle
 *    displacement is level + wob × 0.25 (thinking wobble).
 *  - Radial glow disc (violet→blue→transparent), floor shadow ellipse,
 *    two tilted dashed rings with dynamic alphas and a bass-driven
 *    scale pulse, and 36 orbiting particles with the HTML orbit math.
 *  - Palettes for LIGHT / DARK / RED / SUNSET copied as raw RGB
 *    triples, plus the HTML custom-hex HSL derivation for CUSTOM.
 *
 * The frame loop is driven by [withFrameMillis]; it pauses while the
 * host lifecycle is not RESUMED, and with system reduced motion on it
 * renders a single static frame in the current state's pose. All
 * per-frame scratch lives in preallocated FloatArrays — the particle
 * loop performs zero heap allocations (Color / Offset are value
 * classes).
 */

// ── Public state ─────────────────────────────────────────────────────

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * Voice-session → orb mapping (CONNECTING renders as THINKING;
 * UNIVERSAL_ACTIVE is the Universal engine's listening state).
 */
fun VoiceStatus.toOrbState(): OrbState = when (this) {
    VoiceStatus.IDLE -> OrbState.IDLE
    VoiceStatus.CONNECTING -> OrbState.THINKING
    VoiceStatus.LISTENING, VoiceStatus.UNIVERSAL_ACTIVE -> OrbState.LISTENING
    VoiceStatus.SPEAKING -> OrbState.SPEAKING
    VoiceStatus.THINKING -> OrbState.THINKING
    VoiceStatus.ERROR -> OrbState.ERROR
}

/**
 * F7: THE orb-state mapping, single source for Home and Voice.
 * Voice-session states always win; with no live voice state, a
 * RUNNING task (TaskQueue) shows THINKING — background work is
 * visible on the Home orb even with no voice session — else IDLE.
 */
fun orbStateFor(status: VoiceStatus, taskRunning: Boolean): OrbState =
    when (status) {
        VoiceStatus.IDLE ->
            if (taskRunning) OrbState.THINKING else OrbState.IDLE
        else -> status.toOrbState()
    }

// ── Composable ───────────────────────────────────────────────────────

/**
 * The Canvas orb.
 *
 * @param state orb state machine row (see [OrbState]).
 * @param amplitude live audio level 0..1; treated as the smoothing
 *   target only when [liveAudio] is true or [bass] / [high] are
 *   supplied — otherwise the HTML simulated per-state audio runs.
 * @param theme app theme selecting the orb palette.
 * @param customHex user custom hex (e.g. "#7C3AED"); used by the
 *   [AppTheme.CUSTOM] palette derivation.
 * @param bass live bass band 0..1 (defaults to [amplitude]).
 * @param high live high band 0..1 (defaults to [amplitude]).
 */
@Composable
fun JarvisOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    amplitude: Float = 0f,
    theme: AppTheme = AppTheme.DARK,
    customHex: String? = null,
    bass: Float? = null,
    high: Float? = null,
    liveAudio: Boolean = false
) {
    val palette = remember(theme, customHex) { resolveOrbPalette(theme, customHex) }
    val reducedMotion = rememberReducedMotion()
    val scene = remember { OrbScene() }
    val anim = remember { OrbAnimState() }

    // Latest caller inputs, snapshotted for the frame loop without
    // restarting it on every recomposition.
    SideEffect {
        anim.setInputs(state, amplitude, bass, high, liveAudio)
    }

    // Pause the frame loop while the host is not RESUMED.
    var running by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> running = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> running = false
                else -> Unit
            }
        }
        running = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Frame clock: advancing [anim] then publishing the frame time
    // invalidates only the Canvas draw phase, never the composition.
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var lastMs = -1L
        while (true) {
            withFrameMillis { ms ->
                if (running) {
                    val dt = if (lastMs < 0L) {
                        0.016f
                    } else {
                        ((ms - lastMs) / 1000f).coerceIn(0f, 0.05f)
                    }
                    lastMs = ms
                    anim.advance(dt, scene)
                    frameMs = ms
                } else {
                    // Keep the clock fresh so resume has no dt jump.
                    lastMs = ms
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        // F5: hard-clip every frame to the canvas bounds — rings and
        // orbit particles physically cannot paint outside the square
        // (the QA screenshots showed ring lines escaping to the screen
        // edges).
        withTransform({ clipRect(0f, 0f, size.width, size.height) }) {
            drawOrbFrame(
                scene = scene,
                anim = anim,
                palette = palette,
                state = state,
                tick = frameMs,
                reducedMotion = reducedMotion,
                amplitude = amplitude,
                bass = bass,
                high = high
            )
        }
    }
}

// ── State rows (HTML STATES) ─────────────────────────────────────────

// Parameter indices inside the current/target rows.
private const val P_SCALE = 0
private const val P_SPEED = 1
private const val P_CYAN = 2
private const val P_VIOLET = 3
private const val P_BRIGHT = 4
private const val P_GLOW = 5
private const val P_RING = 6
private const val P_WOB = 7

/**
 * HTML STATES rows in {scale, speed, cyan, violet, bright, glow,
 * ring, wob} order:
 *  idle     {1.00, .35, .35, .45, 1.00, .50, .15, 0}
 *  listening{1.05, .85, 1.00, .25, 1.12, .95, .35, 0}
 *  thinking {1.00, 1.55, .30, 1.00, 1.05, .75, 1.00, 1}
 *  speaking {1.04, 1.15, .90, .90, 1.20, 1.00, .45, 0}
 * ERROR is not an HTML state — it reuses the speaking row.
 */
private fun targetParams(state: OrbState, out: FloatArray): FloatArray {
    when (state) {
        OrbState.IDLE -> {
            out[0] = 1.00f; out[1] = 0.35f; out[2] = 0.35f; out[3] = 0.45f
            out[4] = 1.00f; out[5] = 0.50f; out[6] = 0.15f; out[7] = 0f
        }
        OrbState.LISTENING -> {
            out[0] = 1.05f; out[1] = 0.85f; out[2] = 1.00f; out[3] = 0.25f
            out[4] = 1.12f; out[5] = 0.95f; out[6] = 0.35f; out[7] = 0f
        }
        OrbState.THINKING -> {
            out[0] = 1.00f; out[1] = 1.55f; out[2] = 0.30f; out[3] = 1.00f
            out[4] = 1.05f; out[5] = 0.75f; out[6] = 1.00f; out[7] = 1f
        }
        OrbState.SPEAKING, OrbState.ERROR -> {
            out[0] = 1.04f; out[1] = 1.15f; out[2] = 0.90f; out[3] = 0.90f
            out[4] = 1.20f; out[5] = 1.00f; out[6] = 0.45f; out[7] = 0f
        }
    }
    return out
}

// ── Palettes (HTML THEMES, raw RGB triples) ──────────────────────────

/** One orb palette: DEEP / BLUE / CYAN / violet + floor shadow. */
private class OrbPalette(
    val deep: FloatArray,
    val blue: FloatArray,
    val cyan: FloatArray,
    val purp: FloatArray,
    val shadowR: Float,
    val shadowG: Float,
    val shadowB: Float,
    val shadowAlpha: Float
)

private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

private val PALETTE_LIGHT = OrbPalette(
    deep = rgb(0.04f, 0.10f, 0.50f),
    blue = rgb(0.12f, 0.32f, 0.95f),
    cyan = rgb(0.10f, 0.78f, 1.00f),
    purp = rgb(0.55f, 0.28f, 0.95f),
    shadowR = 70f / 255f, shadowG = 80f / 255f, shadowB = 170f / 255f,
    shadowAlpha = 0.20f
)

private val PALETTE_DARK = OrbPalette(
    deep = rgb(0.10f, 0.20f, 0.80f),
    blue = rgb(0.25f, 0.50f, 1.00f),
    cyan = rgb(0.30f, 0.92f, 1.00f),
    purp = rgb(0.70f, 0.45f, 1.00f),
    shadowR = 0.02f, shadowG = 0.03f, shadowB = 0.10f,
    shadowAlpha = 0.35f
)

private val PALETTE_RED = OrbPalette(
    deep = rgb(0.55f, 0.05f, 0.10f),
    blue = rgb(0.95f, 0.15f, 0.20f),
    cyan = rgb(1.00f, 0.45f, 0.30f),
    purp = rgb(1.00f, 0.25f, 0.55f),
    shadowR = 0.08f, shadowG = 0.01f, shadowB = 0.02f,
    shadowAlpha = 0.35f
)

private val PALETTE_SUNSET = OrbPalette(
    deep = rgb(0.55f, 0.12f, 0.45f),
    blue = rgb(0.95f, 0.30f, 0.45f),
    cyan = rgb(1.00f, 0.55f, 0.20f),
    purp = rgb(0.60f, 0.30f, 0.90f),
    shadowR = 170f / 255f, shadowG = 70f / 255f, shadowB = 110f / 255f,
    shadowAlpha = 0.20f
)

/** Theme → palette. CUSTOM runs the HTML hex derivation. */
private fun resolveOrbPalette(theme: AppTheme, customHex: String?): OrbPalette =
    when (theme) {
        AppTheme.LIGHT -> PALETTE_LIGHT
        AppTheme.DARK -> PALETTE_DARK
        AppTheme.RED -> PALETTE_RED
        AppTheme.SUNSET -> PALETTE_SUNSET
        AppTheme.CUSTOM -> deriveCustomPalette(customHex)
    }

/**
 * The HTML custom-hex derivation (`applyTheme(name, custom)`):
 * luminance picks the light/dark base, near-grey hexes fall back to
 * the stock light/dark palettes, otherwise deep/main/acc1/acc2 are
 * derived in HSL around the hex's hue. Operates on raw sRGB values,
 * like the HTML arrays (no colour-management conversion).
 */
private fun deriveCustomPalette(customHex: String?): OrbPalette {
    val base = parseHexColor(customHex) ?: return PALETTE_DARK
    val r = base.red
    val g = base.green
    val b = base.blue
    val luminance = 0.299f * r + 0.587f * g + 0.114f * b
    val light = luminance > 0.55f
    val hsl = rgbToHsl(r, g, b)
    val h = hsl[0]
    val s = hsl[1]
    val l = hsl[2]
    if (s < 0.06f) {
        return if (light) PALETTE_LIGHT else PALETTE_DARK
    }
    val deep = hslToRgb(
        h,
        minOf(1f, maxOf(0.6f, s * 1.2f)),
        (l * 0.45f).coerceIn(0.10f, 0.35f)
    )
    val main = hslToRgb(
        h,
        maxOf(0.85f, s),
        l.coerceIn(0.45f, 0.65f)
    )
    val acc1 = hslToRgb((h + 0.08f) % 1f, 0.95f, 0.65f)
    val acc2 = hslToRgb((h - 0.08f + 1f) % 1f, 0.95f, 0.70f)
    val shadowBase = if (light) PALETTE_LIGHT else PALETTE_DARK
    return OrbPalette(
        deep = deep,
        blue = main,
        cyan = acc1,
        purp = acc2,
        shadowR = shadowBase.shadowR,
        shadowG = shadowBase.shadowG,
        shadowB = shadowBase.shadowB,
        shadowAlpha = shadowBase.shadowAlpha
    )
}

/** Standard RGB→HSL (same math as THREE.Color.getHSL), h/s/l 0..1. */
private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } / 6f
    return floatArrayOf(h, s, l)
}

/** Standard HSL→RGB (same math as THREE.Color.setHSL). */
private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
    if (s == 0f) return floatArrayOf(l, l, l)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    return floatArrayOf(
        hue2Rgb(p, q, h + 1f / 3f),
        hue2Rgb(p, q, h),
        hue2Rgb(p, q, h - 1f / 3f)
    )
}

private fun hue2Rgb(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 1f / 2f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
}

// ── Small math helpers ───────────────────────────────────────────────

/** GLSL smoothstep (edges in increasing order). */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).fin().coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

// F4.1 finite guards: Kotlin's coerceIn lets NaN straight through, and
// Compose's Color() / draw calls throw IllegalArgumentException on a
// NaN channel or coordinate — a process crash inside the frame loop.
// Every value that reaches a draw call is funnelled through these.

/** NaN / ±Inf → [fallback]; finite values pass through unchanged. */
private fun Float.fin(fallback: Float = 0f): Float =
    if (isFinite()) this else fallback

/** Finite AND clamped to the 0..1 colour-channel range. */
private fun Float.c01(): Float = fin().coerceIn(0f, 1f)

// ── Precomputed scene (particle field + orbit parameters) ───────────

private const val PARTICLE_COUNT = 2400
private const val ORBIT_COUNT = 36
private const val RING_SEGMENTS = 140

/**
 * Everything about the orb that never changes across frames: the
 * Fibonacci-sphere unit directions, shell factors, per-particle
 * randoms and the static colour/size weights derived from them, plus
 * the 36 orbit particles' parameters (HTML `ptData`). Deterministic
 * seed so every composition shows the same orb.
 */
private class OrbScene {
    val dirX = FloatArray(PARTICLE_COUNT)
    val dirY = FloatArray(PARTICLE_COUNT)
    val dirZ = FloatArray(PARTICLE_COUNT)
    val shell = FloatArray(PARTICLE_COUNT)
    val rnd = FloatArray(PARTICLE_COUNT)
    /** Static DEEP-mix weight: 0.5 + 0.5 × rnd. */
    val deepMix = FloatArray(PARTICLE_COUNT)
    /** Static point-size base: 0.006 + rnd × 0.011 (× R per frame). */
    val sizeBase = FloatArray(PARTICLE_COUNT)

    // Orbit particles (angles are mutated by the frame loop).
    val oAngle = FloatArray(ORBIT_COUNT)
    val oBaseR = FloatArray(ORBIT_COUNT)
    val oSpeed = FloatArray(ORBIT_COUNT)
    val oPhase = FloatArray(ORBIT_COUNT)
    val oTilt = FloatArray(ORBIT_COUNT)
    val oYOff = FloatArray(ORBIT_COUNT)
    val oFreq = FloatArray(ORBIT_COUNT)
    val oSizeRnd = FloatArray(ORBIT_COUNT)

    init {
        val random = Random(0x4A415256)
        val goldenAngle = 2.39996f
        for (i in 0 until PARTICLE_COUNT) {
            val y = 1f - 2f * (i + 0.5f) / PARTICLE_COUNT
            val r = sqrt(1f - y * y)
            val th = i * goldenAngle
            dirX[i] = cos(th) * r
            dirY[i] = y
            dirZ[i] = sin(th) * r
            shell[i] = 0.93f + 0.07f * random.nextFloat()
            val rv = random.nextFloat()
            rnd[i] = rv
            deepMix[i] = 0.5f + 0.5f * rv
            sizeBase[i] = 0.006f + rv * 0.011f
        }
        for (i in 0 until ORBIT_COUNT) {
            oAngle[i] = random.nextFloat() * 6.2831855f
            oBaseR[i] = 1.5f + random.nextFloat() * 0.55f
            oSpeed[i] = (0.08f + random.nextFloat() * 0.18f) *
                (if (random.nextFloat() < 0.5f) -1f else 1f)
            oPhase[i] = random.nextFloat() * 6.2831855f
            oTilt[i] = (random.nextFloat() - 0.5f) * 1.2f
            oYOff[i] = (random.nextFloat() - 0.5f) * 1.1f
            oFreq[i] = 0.2f + random.nextFloat() * 0.4f
            oSizeRnd[i] = random.nextFloat()
        }
    }
}

// ── Animation state (mutated by the frame loop only) ────────────────

/** Error red the palette mixes toward in ERROR (× 0.72). */
private const val ERR_R = 0.85f
private const val ERR_G = 0.15f
private const val ERR_B = 0.25f
private const val ERR_MIX = 0.72f

private class OrbAnimState {
    // Caller inputs (written from the UI thread via SideEffect).
    var inputState: OrbState = OrbState.IDLE
    var inputAmp: Float = 0f
    var inputBass: Float = 0f
    var inputHigh: Float = 0f
    var inputLive: Boolean = false

    // Dynamic state.
    val cur = FloatArray(8)
    val target = FloatArray(8)
    val drawParams = FloatArray(8)
    val sim = FloatArray(3)
    var time: Float = 0f
    var flow: Float = 0f
    var angleY: Float = 0f
    var ring1Angle: Float = 0f
    var ring2Angle: Float = 0f
    var level: Float = 0f
    var bassV: Float = 0f
    var highV: Float = 0f
    var tintMix: Float = 0f

    // Per-draw tint-mixed palette scratch.
    val effDeep = FloatArray(3)
    val effBlue = FloatArray(3)
    val effCyan = FloatArray(3)
    val effPurp = FloatArray(3)

    init {
        targetParams(OrbState.IDLE, cur)
    }

    fun setInputs(
        state: OrbState,
        amplitude: Float,
        bass: Float?,
        high: Float?,
        liveAudio: Boolean
    ) {
        inputState = state
        // F4.1: amplitude sources (audio RMS) must never inject NaN
        // into the animation state — c01() replaces it with 0.
        inputAmp = amplitude.c01()
        inputBass = (bass ?: amplitude).c01()
        inputHigh = (high ?: amplitude).c01()
        // Supplying bass/high counts as a live feed too (same rule
        // the previous implementation used).
        inputLive = liveAudio || bass != null || high != null
    }

    /** HTML updateOrb + readAudio + updateParticles angle advance. */
    fun advance(dt: Float, scene: OrbScene) {
        targetParams(inputState, target)
        val k = 1f - exp(-dt * 3.5f)
        for (i in 0 until 8) {
            cur[i] += (target[i] - cur[i]) * k
        }
        time += dt
        flow += dt * cur[P_SPEED]

        // Audio targets: live feed wins, else the HTML simulation.
        val tLevel: Float
        val tBass: Float
        val tHigh: Float
        if (inputLive) {
            tLevel = inputAmp
            tBass = inputBass
            tHigh = inputHigh
        } else {
            simulateAudio(time, inputState, sim)
            tLevel = sim[0]
            tBass = sim[1]
            tHigh = sim[2]
        }
        level = smoothAudio(level, tLevel, dt)
        bassV = smoothAudio(bassV, tBass, dt)
        highV = smoothAudio(highV, tHigh, dt)

        val tintTarget = if (inputState == OrbState.ERROR) ERR_MIX else 0f
        tintMix += (tintTarget - tintMix) * k

        angleY += dt * 0.18f * (0.5f + cur[P_SPEED])
        ring1Angle += dt * (0.12f + cur[P_RING] * 0.6f)
        ring2Angle -= dt * (0.08f + cur[P_RING] * 0.35f)

        // Orbit angles (HTML updateParticles boost).
        val boost = 0.6f + cur[P_SPEED] * 0.8f +
            (if (inputState == OrbState.THINKING) 1.2f else 0f)
        for (i in 0 until ORBIT_COUNT) {
            scene.oAngle[i] += scene.oSpeed[i] * dt * boost
        }
    }

    /** Attack 18 /s, release 6 /s — HTML `k = 1 − exp(−dt × rate)`. */
    private fun smoothAudio(current: Float, target: Float, dt: Float): Float {
        val rate = if (target > current) 18f else 6f
        return current + (target - current) * (1f - exp(-dt * rate))
    }
}

/** HTML simulateAudio(t) — verbatim per-state patterns. */
private fun simulateAudio(t: Float, state: OrbState, out: FloatArray) {
    var l: Float
    var b: Float
    var h: Float
    when (state) {
        OrbState.SPEAKING, OrbState.ERROR -> {
            val syl = abs(sin(t * 7.3f) * sin(t * 2.1f + 1f)).pow(0.8f)
            val gate = if (sin(t * 0.9f) > -0.7f) 1f else 0.15f
            l = (0.3f + 0.45f * syl) * gate
            b = l * (0.6f + 0.4f * sin(t * 5f))
            h = l * (0.5f + 0.5f * sin(t * 11f))
        }
        OrbState.LISTENING -> {
            l = 0.2f + 0.2f * abs(sin(t * 3.1f) * sin(t * 1.3f))
            b = l * 0.8f
            h = l * 0.5f
        }
        OrbState.THINKING -> {
            l = 0.12f + 0.07f * sin(t * 2.2f)
            b = l * 0.7f
            h = l * 0.6f
        }
        OrbState.IDLE -> {
            l = 0.05f + 0.03f * sin(t * 1.1f)
            b = l
            h = l * 0.5f
        }
    }
    out[0] = l
    out[1] = b
    out[2] = h
}

// ── Drawing ──────────────────────────────────────────────────────────

private const val TILT_X = 0.35f
private const val PERSP_F = 3.2f

private fun fillEffectivePalette(palette: OrbPalette, tintMix: Float, anim: OrbAnimState) {
    // Mix every channel toward the matching error-red channel.
    val keep = 1f - tintMix
    anim.effDeep[0] = palette.deep[0] * keep + ERR_R * tintMix
    anim.effDeep[1] = palette.deep[1] * keep + ERR_G * tintMix
    anim.effDeep[2] = palette.deep[2] * keep + ERR_B * tintMix
    anim.effBlue[0] = palette.blue[0] * keep + ERR_R * tintMix
    anim.effBlue[1] = palette.blue[1] * keep + ERR_G * tintMix
    anim.effBlue[2] = palette.blue[2] * keep + ERR_B * tintMix
    anim.effCyan[0] = palette.cyan[0] * keep + ERR_R * tintMix
    anim.effCyan[1] = palette.cyan[1] * keep + ERR_G * tintMix
    anim.effCyan[2] = palette.cyan[2] * keep + ERR_B * tintMix
    anim.effPurp[0] = palette.purp[0] * keep + ERR_R * tintMix
    anim.effPurp[1] = palette.purp[1] * keep + ERR_G * tintMix
    anim.effPurp[2] = palette.purp[2] * keep + ERR_B * tintMix
}

private fun DrawScope.drawOrbFrame(
    scene: OrbScene,
    anim: OrbAnimState,
    palette: OrbPalette,
    state: OrbState,
    tick: Long,
    reducedMotion: Boolean,
    amplitude: Float,
    bass: Float?,
    high: Float?
) {
    // [tick] is read by the caller inside the Canvas lambda to
    // subscribe this draw to the frame clock; nothing else needs it.
    @Suppress("UNUSED_VARIABLE")
    val frameTick = tick

    // F4.1: a non-finite or empty size must skip the frame entirely —
    // drawing with NaN geometry throws inside Canvas.
    if (!size.minDimension.isFinite() || size.minDimension <= 0f) return
    val cx = size.width / 2f
    val cy = size.height / 2f
    // Sphere radius in px: R = min(w,h)/2 × 0.66 (the F5 fit rule) —
    // rings reach 1.39 R and the glow 1.28 R, and orbit particles are
    // additionally clamped, so the whole composition stays inside the
    // square canvas.
    val radius = size.minDimension * 0.33f
    if (!radius.isFinite() || radius <= 0f) return

    // Effective per-frame values: animated state, or one static pose
    // for reduced motion (state row applied directly, half amplitude).
    val curP: FloatArray
    val time: Float
    val flow: Float
    val angleY: Float
    val ring1Angle: Float
    val ring2Angle: Float
    val level: Float
    val bassV: Float
    val tintMix: Float
    if (reducedMotion) {
        curP = targetParams(state, anim.drawParams)
        time = 0f
        flow = 0f
        angleY = 0.6f
        ring1Angle = 0.35f
        ring2Angle = -0.25f
        level = amplitude.c01() * 0.5f
        bassV = (bass ?: amplitude).c01() * 0.5f
        tintMix = if (state == OrbState.ERROR) ERR_MIX else 0f
    } else {
        curP = anim.cur
        time = anim.time
        flow = anim.flow
        angleY = anim.angleY
        ring1Angle = anim.ring1Angle
        ring2Angle = anim.ring2Angle
        level = anim.level
        bassV = anim.bassV
        tintMix = anim.tintMix
    }
    val cyanP = curP[P_CYAN]
    val violetP = curP[P_VIOLET]
    val glowP = curP[P_GLOW]
    val ringP = curP[P_RING]
    val wobP = curP[P_WOB]

    val idlePulse = maxOf(0f, sin(time * 0.5f)).pow(12f) * 0.15f * (1f - wobP)
    // HTML orb scale: breathing + level swell + thinking wobble.
    val s = curP[P_SCALE] *
        (1f + 0.018f * sin(time * 1.2f) + level * 0.07f + wobP * 0.025f * sin(time * 3.6f))
    // Effective amp (displacement / point size) gains the wobble.
    val ampEff = level + wobP * 0.25f
    val brightEff = curP[P_BRIGHT] + idlePulse
    // Glow strength per the HTML update loop.
    val glowStrength = glowP + level * 0.6f + idlePulse * 2f
    val glowAlpha = (glowStrength * 0.16f + level * 0.05f).fin().coerceIn(0f, 0.5f)

    fillEffectivePalette(palette, tintMix, anim)
    val effDeep = anim.effDeep
    val effBlue = anim.effBlue
    val effCyan = anim.effCyan
    val effPurp = anim.effPurp

    val cosY = cos(angleY)
    val sinY = sin(angleY)
    val cosT = cos(TILT_X)
    val sinT = sin(TILT_X)

    // ── Floor shadow (HTML #shadow div): width 1.5 units × s at
    // cy + 1.2 units, height 0.12 of the width, soft falloff.
    // F5: drawn as nested SOLID ellipses — no Brush anywhere in this
    // file anymore (see the glow note below for why).
    val shadowW = 1.5f * radius * s
    val shadowCy = cy + 1.2f * radius
    withTransform({
        translate(cx, shadowCy)
        scale(1f, 0.12f, Offset.Zero)
    }) {
        for (i in 3 downTo 1) {
            val f = i / 3f
            drawCircle(
                color = Color(
                    red = palette.shadowR.c01(),
                    green = palette.shadowG.c01(),
                    blue = palette.shadowB.c01(),
                    alpha = (palette.shadowAlpha * 0.38f).c01()
                ),
                radius = shadowW / 2f * f,
                center = Offset.Zero
            )
        }
    }

    // ── Glow disc: violet → blue → transparent, radius 1.28 R.
    // F5 ROOT-CAUSE FIX for the "square panel" in the QA screenshots:
    // the only translucent full-area painter in this composable was
    // this glow, and it was a Brush.radialGradient. On the user's GPU
    // the gradient shader bled to its rectangular bounds, painting a
    // visible SQUARE behind the sphere (the same class of driver
    // artifact that made the F3 GL orb render as an opaque square).
    // The glow is now 16 concentric solid-alpha circles — a radial
    // falloff that is circular by construction, with a fully
    // transparent outer edge. Nothing in the orb paints a rectangle.
    if (glowAlpha > 0.003f) {
        val glowRadius = 1.28f * radius * s
        val glowSteps = 16
        for (i in glowSteps downTo 1) {
            val f = i.toFloat() / glowSteps // 1 = outer edge
            val a = (glowAlpha * (1f - f) * (1f - f)).c01()
            if (a <= 0.002f) continue
            drawCircle(
                color = Color(
                    red = mix(effBlue[0], effPurp[0], 1f - f).c01(),
                    green = mix(effBlue[1], effPurp[1], 1f - f).c01(),
                    blue = mix(effBlue[2], effPurp[2], 1f - f).c01(),
                    alpha = a
                ),
                radius = glowRadius * f,
                center = Offset(cx, cy)
            )
        }
    }

    // ── Rings: dynamic alphas + bass-driven scale pulse.
    val ringPulse = ringP * 0.5f * (0.5f + 0.5f * sin(time * 4f)) + bassV * 0.8f
    val ringStroke = 1.5.dp.toPx()
    drawOrbRing(
        cx = cx, cy = cy, radius = radius,
        // F5: 0.95 pull-in — at 1.39 units × perspective the far ring
        // could cross the canvas edge; the frame clip is the backstop.
        ringScale = s * (1f + ringPulse * 0.03f) * 0.95f,
        ringRadiusUnits = 1.26f, tiltX = -0.32f,
        phase = ring1Angle, segs = 2f, seed = 0f,
        baseAlpha = 0.4f + ringP * 0.3f + level * 0.35f,
        cosY = cosY, sinY = sinY,
        effCyan = effCyan, effBlue = effBlue, effPurp = effPurp,
        strokePx = ringStroke
    )
    drawOrbRing(
        cx = cx, cy = cy, radius = radius,
        ringScale = s * (1f + ringPulse * 0.02f) * 0.95f,
        ringRadiusUnits = 1.39f, tiltX = 0.22f,
        phase = ring2Angle, segs = 3f, seed = 2.1f,
        baseAlpha = 0.22f + ringP * 0.2f + level * 0.2f,
        cosY = cosY, sinY = sinY,
        effCyan = effCyan, effBlue = effBlue, effPurp = effPurp,
        strokePx = ringStroke
    )

    // ── Sphere particles.
    val sizeAmp = 1f + ampEff * 0.5f
    for (i in 0 until PARTICLE_COUNT) {
        val dx0 = scene.dirX[i]
        val dy0 = scene.dirY[i]
        val dz0 = scene.dirZ[i]
        // Rotate: Y spin, then the fixed X tilt (0.35 rad).
        val x1 = dx0 * cosY + dz0 * sinY
        val z1 = -dx0 * sinY + dz0 * cosY
        val y2 = dy0 * cosT - z1 * sinT
        val z2 = dy0 * sinT + z1 * cosT
        val rv = scene.rnd[i]
        // HTML vertex noise on the ROTATED direction (uTime = flow).
        val n = sin(x1 * 3f + flow * 0.8f + rv * 6f) *
            sin(y2 * 3f - flow * 0.6f) *
            sin(z2 * 3f + flow * 0.5f)
        val rUnits = scene.shell[i] *
            (1f + n * 0.05f + ampEff * 0.1f * rv + 0.015f * sin(flow * 2f + rv * 20f))
        val zPos = z2 * rUnits
        val depth01 = smoothstep(-1f, 1f, zPos)
        val persp = PERSP_F / (PERSP_F - zPos)
        val rr = rUnits * radius * s * persp
        val px = cx + x1 * rr
        val py = cy + y2 * rr

        // Colour chain (HTML): BLUE→CYAN by height+noise, then
        // →violet by direction+random, then DEEP mixed back by the
        // precomputed static weight; brightness × depth shading.
        val wCyan = smoothstep(-0.5f, 0.9f, y2 + n) * cyanP
        var cr = mix(effBlue[0], effCyan[0], wCyan)
        var cg = mix(effBlue[1], effCyan[1], wCyan)
        var cb = mix(effBlue[2], effCyan[2], wCyan)
        val wPurp = smoothstep(0.1f, 0.9f, x1 * 0.6f - y2 * 0.3f + rv * 0.4f) * violetP
        cr = mix(cr, effPurp[0], wPurp)
        cg = mix(cg, effPurp[1], wPurp)
        cb = mix(cb, effPurp[2], wPurp)
        val dm = scene.deepMix[i]
        cr = mix(effDeep[0], cr, dm)
        cg = mix(effDeep[1], cg, dm)
        cb = mix(effDeep[2], cb, dm)
        val mult = brightEff * (0.6f + 0.4f * depth01)

        // maxOf() lets NaN WIN over the floor — sanitise first, and
        // skip the particle entirely if its geometry went non-finite.
        val pointRadius = maxOf(
            radius * scene.sizeBase[i] * (0.85f + 0.35f * depth01) * sizeAmp,
            0.7f
        ).fin(0.7f)
        if (!px.isFinite() || !py.isFinite()) continue
        drawCircle(
            color = Color(
                red = (cr * mult).c01(),
                green = (cg * mult).c01(),
                blue = (cb * mult).c01(),
                alpha = (0.22f + 0.78f * depth01).c01()
            ),
            radius = pointRadius,
            center = Offset(px, py)
        )
    }

    // ── Orbit particles (HTML updateParticles positions, contained).
    // F5: the HTML orbit radius runs 1.5–2.05 units — at R = 0.33 ×
    // min dimension with perspective, those particles landed OUTSIDE
    // the canvas (the stray dots at the screen edges in the QA
    // screenshots). The orbit is scaled to 0.8× and every particle is
    // radially clamped inside the canvas as a hard guarantee.
    val thinking = state == OrbState.THINKING
    for (j in 0 until ORBIT_COUNT) {
        val a = scene.oAngle[j]
        val fs = scene.oFreq[j]
        val ph = scene.oPhase[j]
        val rU = (scene.oBaseR[j] +
            sin(time * fs + ph) * 0.12f -
            (if (thinking) 0.15f else 0f) +
            level * 0.1f) * 0.8f
        val x = cos(a) * rU
        val z = sin(a) * rU * 0.6f
        val y = scene.oYOff[j] * 0.8f + sin(a * 1.3f + ph) * 0.18f + x * scene.oTilt[j] * 0.25f
        val persp = PERSP_F / (PERSP_F - z)
        var px = cx + x * radius * persp
        var py = cy + y * radius * persp
        val depth01 = smoothstep(-1.3f, 1.3f, z)
        val alphaO = ((0.5f + 0.5f * sin(time * fs * 1.6f + ph)).pow(1.5f) *
            (0.55f + glowP * 0.3f)).c01()
        // Diameter 1–3.2 dp (radius half of that), depth-scaled.
        val sizePx = (0.5f + scene.oSizeRnd[j] * 1.1f) * density * (0.7f + 0.6f * depth01)
        if (!px.isFinite() || !py.isFinite() || !sizePx.isFinite()) continue
        // Radial clamp: the whole particle stays inside the canvas.
        val odx = px - cx
        val ody = py - cy
        val oDist = sqrt(odx * odx + ody * ody)
        val oMax = size.minDimension / 2f - sizePx - 1f
        if (oDist > oMax && oDist > 0f && oMax > 0f) {
            val k = oMax / oDist
            px = cx + odx * k
            py = cy + ody * k
        }
        val colArr = when (j % 3) {
            0 -> effCyan
            1 -> effBlue
            else -> effPurp
        }
        drawCircle(
            color = Color(
                red = colArr[0].c01(),
                green = colArr[1].c01(),
                blue = colArr[2].c01(),
                alpha = alphaO
            ),
            radius = sizePx,
            center = Offset(px, py)
        )
    }
}

/**
 * One dashed ring: 140 segments along a circle in the XZ plane,
 * tilted around X by [tiltX], then given the orb's Y rotation and
 * weak perspective. The dash mask and angle-mixed colour follow the
 * HTML ring shader; segments under mask 0.03 are skipped.
 */
private fun DrawScope.drawOrbRing(
    cx: Float,
    cy: Float,
    radius: Float,
    ringScale: Float,
    ringRadiusUnits: Float,
    tiltX: Float,
    phase: Float,
    segs: Float,
    seed: Float,
    baseAlpha: Float,
    cosY: Float,
    sinY: Float,
    effCyan: FloatArray,
    effBlue: FloatArray,
    effPurp: FloatArray,
    strokePx: Float
) {
    val cosTilt = cos(tiltX)
    val sinTilt = sin(tiltX)
    val step = (2.0 * Math.PI / RING_SEGMENTS).toFloat()
    var prevX = 0f
    var prevY = 0f
    for (j in 0..RING_SEGMENTS) {
        val a = j * step
        val lx = cos(a) * ringRadiusUnits
        val lz = sin(a) * ringRadiusUnits
        // Tilt around X (point starts in the XZ plane, y = 0).
        val y1 = -lz * sinTilt
        val z1 = lz * cosTilt
        // Orb Y rotation + weak perspective.
        val x2 = lx * cosY + z1 * sinY
        val z2 = -lx * sinY + z1 * cosY
        val persp = PERSP_F / (PERSP_F - z2)
        val px = cx + x2 * radius * ringScale * persp
        val py = cy + y1 * radius * ringScale * persp
        if (j > 0) {
            val am = a - step / 2f
            val m = smoothstep(-0.25f, 0.35f, sin(am * segs + phase)) *
                smoothstep(-0.9f, -0.2f, sin(am + seed * 0.7f))
            // NaN mask fails this comparison too (NaN >= x is false),
            // but the endpoints still get an explicit finite check.
            if (m >= 0.03f && px.isFinite() && py.isFinite() &&
                prevX.isFinite() && prevY.isFinite()
            ) {
                // HTML ring colour: mix(mix(CYAN, BLUE, s1), violet, s2).
                val w1 = 0.5f + 0.5f * sin(am + seed)
                val w2 = 0.5f + 0.5f * sin(am * 2f - seed)
                val cr = mix(mix(effCyan[0], effBlue[0], w1), effPurp[0], w2)
                val cg = mix(mix(effCyan[1], effBlue[1], w1), effPurp[1], w2)
                val cb = mix(mix(effCyan[2], effBlue[2], w1), effPurp[2], w2)
                drawLine(
                    color = Color(
                        red = cr.c01(),
                        green = cg.c01(),
                        blue = cb.c01(),
                        alpha = (m * baseAlpha).c01()
                    ),
                    start = Offset(prevX, prevY),
                    end = Offset(px, py),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
            }
        }
        prevX = px
        prevY = py
    }
}

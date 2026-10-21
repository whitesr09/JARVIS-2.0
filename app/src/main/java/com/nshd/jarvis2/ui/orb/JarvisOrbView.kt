package com.nshd.jarvis2.ui.orb

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nshd.jarvis2.ui.AppTheme
import com.nshd.jarvis2.ui.ParticleOrb
import com.nshd.jarvis2.ui.parseHexColor
import com.nshd.jarvis2.voice.VoiceStatus
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * F3 — OpenGL ES 2.0 port of the Three.js particle orb in
 * `orb_0_1tbg.html` (the file Nshd supplied as the visual reference).
 *
 * What is ported, 1:1 where GLSL ES 1.00 allows:
 *  - 4,500 particles (HTML: 6,000) on a Fibonacci sphere, shell
 *    0.93–1.0, per-particle `aRnd`; the HTML vertex shader's
 *    sin-product noise displacement, `radius += uAmp * .1 * aRnd`,
 *    depth-based point size / alpha and BLUE→CYAN→PURP→DEEP colour
 *    mixing are copied constant-for-constant.
 *  - The soft-dot fragment shader (radial falloff over gl_PointCoord).
 *  - The outer-glow plane shader: halo only beyond 0.68 of the plane
 *    radius, angular colour mix animated by uTime.
 *  - Two dashed rings (r 1.255–1.272, 2 segments, alpha ~0.5 and
 *    r 1.380–1.388, 3 segments, alpha ~0.3) counter-rotating at
 *    speeds scaled by the state's `ring` parameter.
 *  - 36 orbiting particles on tilted orbits with per-particle size,
 *    alpha pulse and CYAN/BLUE/PURP colour cycling (CPU-updated per
 *    frame exactly like the HTML `updateParticles`).
 *  - The state machine STATES {idle, listening, thinking, speaking}
 *    × {scale, speed, cyan, purple, bright, glow, ring, wob}, lerped
 *    with k = 1 − exp(−dt × 3.5).
 *  - Audio level/bass/high smoothed with attack 18 / release 6 per
 *    second; when no live feed is fresh (< 220 ms, same window as
 *    the HTML `setExternalAudio`), the HTML simulated per-state
 *    patterns run verbatim.
 *  - Drag-to-rotate with velocity + decay, global rotation
 *    `rotation.y += dt * .18 * (.5 + speed)`, idle pulse, soft floor
 *    shadow ellipse with the HTML 5 s float animation.
 *  - Palettes: light / dark / red / sunset copied as raw RGB triples,
 *    plus the HTML custom-hex HSL derivation (deep/main/acc1/acc2).
 *    Additive blending on the dark palettes, normal on light ones.
 *
 * Two deliberate GLSL adaptations (mathematically identical, noted
 * for the reviewer): the HTML uses `smoothstep` with edge0 > edge1
 * in three places, which GLSL ES 1.00 leaves *undefined*; those are
 * written as `1.0 - smoothstep(lo, hi, x)` with the edges in order.
 * And THREE's built-in `position`/`modelViewMatrix` become explicit
 * `aPosition` / `uMV` / `uProj` declarations.
 *
 * ERROR is not an HTML state: it reuses the SPEAKING geometry /
 * state row and shifts the whole palette toward error red through
 * a `uTint`/`uTintMix` uniform override present in every shader.
 *
 * Fallback: if the GL surface or any shader/program fails to init,
 * [JarvisOrb] swaps to the existing Canvas [ParticleOrb] with the
 * state mapped onto its boolean flags — the orb never disappears.
 */

// ── Public state ─────────────────────────────────────────────────────

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * Voice-session → orb mapping for callers (F3 spec: CONNECTING is
 * rendered as THINKING; UNIVERSAL_ACTIVE is the Universal engine's
 * listening state).
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
 * Small mutable holder for a live audio feed, for callers that would
 * rather push values than recompose with them (e.g. a collector that
 * forwards `VoiceSessionManager.amplitude`):
 *
 * ```
 * val feed = remember { JarvisOrbAudioState() }
 * LaunchedEffect(Unit) {
 *     VoiceSessionManager.amplitude.collect { feed.update(it) }
 * }
 * JarvisOrb(state = s, audioState = feed, theme = settings.theme)
 * ```
 *
 * [update] stamps the feed live; while [live] is true and the values
 * keep changing, the renderer uses them as uAmp/uBass/uHigh. Stop
 * updating (or call [goSimulated]) and the HTML simulated per-state
 * patterns take over again after the 220 ms freshness window.
 */
class JarvisOrbAudioState {
    var amplitude by mutableFloatStateOf(0f)
        private set
    var bass by mutableFloatStateOf(0f)
        private set
    var high by mutableFloatStateOf(0f)
        private set
    var live by mutableStateOf(false)
        private set

    fun update(level: Float, bass: Float = level, high: Float = level) {
        amplitude = level.coerceIn(0f, 1f)
        this.bass = bass.coerceIn(0f, 1f)
        this.high = high.coerceIn(0f, 1f)
        live = true
    }

    fun goSimulated() {
        live = false
    }
}

// ── Composable ───────────────────────────────────────────────────────

/**
 * The GL orb.
 *
 * @param state orb state machine row (see [OrbState]).
 * @param amplitude live audio level 0..1. Only treated as a *live*
 *   feed when [liveAudio] is true or [bass]/[high] are supplied —
 *   a bare amplitude leaves the renderer on the HTML simulated
 *   patterns, exactly like the web page before `setExternalAudio`
 *   is called. (Voice screens: pass the collected
 *   `VoiceSessionManager.amplitude` value with `liveAudio = true`,
 *   or use the `State<Float>` / [JarvisOrbAudioState] overloads.)
 * @param theme app theme; PURPLE and LIGHT use the HTML light
 *   palette, DARK the dark palette, RED the red palette.
 * @param customHex user custom hex (e.g. "#7C3AED"); when it parses
 *   it wins over [theme], via the HTML HSL palette derivation.
 * @param bass live bass band 0..1 (defaults to [amplitude]).
 * @param high live high band 0..1 (defaults to [amplitude]).
 */
@Composable
fun JarvisOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    amplitude: Float = 0f,
    theme: AppTheme = AppTheme.PURPLE,
    customHex: String? = null,
    bass: Float? = null,
    high: Float? = null,
    liveAudio: Boolean = false
) {
    val glFailedState = remember { mutableStateOf(false) }
    val renderer = remember {
        JarvisOrbRenderer(onGlError = { glFailedState.value = true })
    }
    val palette = remember(theme, customHex) { resolveOrbPalette(theme, customHex) }
    var glView by remember { mutableStateOf<OrbGlSurfaceView?>(null) }

    // Pause rendering with the host lifecycle (and on dispose);
    // GLSurfaceView itself stops its GL thread when detached.
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    DisposableEffect(activity, glView) {
        val view = glView
        val lifecycle = activity?.lifecycle
        if (view == null || lifecycle == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                view.onPause()
            }
        }
    }

    SideEffect {
        renderer.targetState = state
        renderer.setPalette(palette)
        if (liveAudio || bass != null || high != null) {
            renderer.setExternalAudio(
                level = amplitude,
                bass = bass ?: amplitude,
                high = high ?: amplitude
            )
        }
    }

    if (glFailedState.value) {
        // GL init failed (no ES 2.0, shader compile, EGL config) —
        // fall back to the Canvas orb so the surface never goes blank.
        ParticleOrb(
            listening = state == OrbState.LISTENING,
            speaking = state == OrbState.SPEAKING,
            thinking = state == OrbState.THINKING,
            error = state == OrbState.ERROR,
            amplitude = amplitude,
            modifier = modifier
        )
        return
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                try {
                    OrbGlSurfaceView(ctx, renderer).also { glView = it }
                } catch (e: Exception) {
                    // Surface construction itself failed — report
                    // through the same channel; a plain View holds
                    // the slot for this frame and the fallback above
                    // takes over on the next recomposition.
                    renderer.reportFailure()
                    View(ctx)
                }
            },
            onRelease = { view ->
                (view as? OrbGlSurfaceView)?.onPause()
                if (glView === view) glView = null
            },
            modifier = Modifier
                .fillMaxSize()
                // Drag-to-rotate, observed on the Initial pass and
                // never consumed, so a caller's own clickable on the
                // passed-in modifier (Home orb → open Voice) still
                // fires for taps. Deltas are density-normalised so
                // the HTML 0.012-per-CSS-px velocity gain matches.
                .pointerInput(renderer) {
                    awaitPointerEventScope {
                        var dragging = false
                        var lastX = 0f
                        var lastY = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                if (!dragging) {
                                    dragging = true
                                    lastX = change.position.x
                                    lastY = change.position.y
                                    renderer.setDragging(true)
                                } else {
                                    val dx = (change.position.x - lastX) / density
                                    val dy = (change.position.y - lastY) / density
                                    if (dx != 0f || dy != 0f) {
                                        renderer.addDrag(dx, dy)
                                    }
                                    lastX = change.position.x
                                    lastY = change.position.y
                                }
                            } else if (dragging) {
                                dragging = false
                                renderer.setDragging(false)
                            }
                        }
                    }
                }
        )
    }
}

/**
 * Overload taking Compose [State] holders — e.g. the value of
 * `VoiceSessionManager.amplitude.collectAsState()`. Anything passed
 * here counts as a live feed (see [JarvisOrb]).
 */
@Composable
fun JarvisOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    amplitudeState: State<Float>,
    theme: AppTheme = AppTheme.PURPLE,
    customHex: String? = null,
    bassState: State<Float>? = null,
    highState: State<Float>? = null
) {
    JarvisOrb(
        state = state,
        modifier = modifier,
        amplitude = amplitudeState.value,
        theme = theme,
        customHex = customHex,
        bass = bassState?.value,
        high = highState?.value,
        liveAudio = true
    )
}

/** Overload taking a [JarvisOrbAudioState] holder (push-style feed). */
@Composable
fun JarvisOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    audioState: JarvisOrbAudioState,
    theme: AppTheme = AppTheme.PURPLE,
    customHex: String? = null
) {
    JarvisOrb(
        state = state,
        modifier = modifier,
        amplitude = audioState.amplitude,
        theme = theme,
        customHex = customHex,
        bass = if (audioState.live) audioState.bass else null,
        high = if (audioState.live) audioState.high else null,
        liveAudio = audioState.live
    )
}

// ── Palettes (HTML THEMES, raw RGB triples) ──────────────────────────

/** One orb palette: DEEP / BLUE / CYAN / PURP + blend + shadow. */
class OrbPaletteData(
    val deep: FloatArray,
    val blue: FloatArray,
    val cyan: FloatArray,
    val purp: FloatArray,
    val additive: Boolean,
    val shadowRgb: FloatArray,
    val shadowAlpha: Float
) {
    fun sameAs(other: OrbPaletteData): Boolean =
        additive == other.additive &&
            shadowAlpha == other.shadowAlpha &&
            deep.contentEquals(other.deep) &&
            blue.contentEquals(other.blue) &&
            cyan.contentEquals(other.cyan) &&
            purp.contentEquals(other.purp) &&
            shadowRgb.contentEquals(other.shadowRgb)
}

private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

/** HTML LIGHTPAL — also what PURPLE maps to (both are light themes). */
private val ORB_PALETTE_LIGHT = OrbPaletteData(
    deep = rgb(0.04f, 0.10f, 0.50f),
    blue = rgb(0.12f, 0.32f, 0.95f),
    cyan = rgb(0.10f, 0.78f, 1.00f),
    purp = rgb(0.55f, 0.28f, 0.95f),
    additive = false,
    shadowRgb = rgb(70f / 255f, 80f / 255f, 170f / 255f),
    shadowAlpha = 0.22f
)

private val ORB_PALETTE_DARK = OrbPaletteData(
    deep = rgb(0.10f, 0.20f, 0.80f),
    blue = rgb(0.25f, 0.50f, 1.00f),
    cyan = rgb(0.30f, 0.92f, 1.00f),
    purp = rgb(0.70f, 0.45f, 1.00f),
    additive = true,
    shadowRgb = rgb(80f / 255f, 120f / 255f, 255f / 255f),
    shadowAlpha = 0.20f
)

private val ORB_PALETTE_RED = OrbPaletteData(
    deep = rgb(0.55f, 0.05f, 0.10f),
    blue = rgb(0.95f, 0.15f, 0.20f),
    cyan = rgb(1.00f, 0.45f, 0.30f),
    purp = rgb(1.00f, 0.25f, 0.55f),
    additive = true,
    shadowRgb = rgb(1.00f, 60f / 255f, 80f / 255f),
    shadowAlpha = 0.20f
)

/**
 * HTML sunset palette, kept for parity with the reference — no
 * current [AppTheme] maps to it (SUNSET was retired in D1), so this
 * is only reachable again if a sunset theme returns.
 */
@Suppress("unused")
private val ORB_PALETTE_SUNSET = OrbPaletteData(
    deep = rgb(0.55f, 0.12f, 0.45f),
    blue = rgb(0.95f, 0.30f, 0.45f),
    cyan = rgb(1.00f, 0.55f, 0.20f),
    purp = rgb(0.60f, 0.30f, 0.90f),
    additive = false,
    shadowRgb = rgb(170f / 255f, 70f / 255f, 110f / 255f),
    shadowAlpha = 0.22f
)

/**
 * Theme → palette. A parseable [customHex] wins over [theme],
 * mirroring `JarvisTheme` (custom hex overrides the named scheme).
 */
private fun resolveOrbPalette(theme: AppTheme, customHex: String?): OrbPaletteData {
    val custom = parseHexColor(customHex)
    if (custom != null) return deriveCustomOrbPalette(custom)
    return when (theme) {
        AppTheme.PURPLE, AppTheme.LIGHT -> ORB_PALETTE_LIGHT
        AppTheme.DARK -> ORB_PALETTE_DARK
        AppTheme.RED -> ORB_PALETTE_RED
    }
}

/**
 * The HTML custom-hex derivation (`applyTheme(name, custom)`):
 * luminance picks the light/dark base, near-grey hexes fall back to
 * the stock light/dark palettes, otherwise deep/main/acc1/acc2 are
 * derived in HSL around the hex's hue. Operates on raw sRGB values,
 * like the HTML arrays (no colour-management conversion).
 */
private fun deriveCustomOrbPalette(base: Color): OrbPaletteData {
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
        return if (light) ORB_PALETTE_LIGHT else ORB_PALETTE_DARK
    }
    val deep = hslToRgb(
        h,
        min(1f, maxOf(0.6f, s * 1.2f)),
        maxOf(0.1f, min(0.35f, l * 0.45f))
    )
    val main = hslToRgb(
        h,
        maxOf(0.85f, s),
        maxOf(0.45f, min(0.65f, l))
    )
    val acc1 = hslToRgb((h + 0.08f) % 1f, 0.95f, 0.65f)
    val acc2 = hslToRgb((h - 0.08f + 1f) % 1f, 0.95f, 0.70f)
    val basePalette = if (light) ORB_PALETTE_LIGHT else ORB_PALETTE_DARK
    return OrbPaletteData(
        deep = deep,
        blue = main,
        cyan = acc1,
        purp = acc2,
        additive = !light,
        shadowRgb = basePalette.shadowRgb,
        shadowAlpha = basePalette.shadowAlpha
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

// ── Shaders (HTML sources, adapted only where ES 1.00 requires) ─────

private const val PARTICLE_VERTEX = """
precision highp float;
attribute vec3 aPosition;
attribute float aRnd;
uniform mat4 uMV;
uniform mat4 uProj;
uniform float uTime;
uniform float uAmp;
uniform float uBass;
uniform float uHigh;
uniform float uCyan;
uniform float uPurple;
uniform float uBright;
uniform float uPx;
uniform float uCamZ;
uniform vec3 DEEP;
uniform vec3 BLUE;
uniform vec3 CYAN;
uniform vec3 PURP;
uniform vec3 uTint;
uniform float uTintMix;
varying vec3 vC;
varying float vA;
void main(){
 float base = length(aPosition);
 vec3 d = aPosition / base;
 float t = uTime;
 float n = sin(d.x*3.0 + t*0.8 + aRnd*6.0) * sin(d.y*3.0 - t*0.6) * sin(d.z*3.0 + t*0.5);
 float r = base * (1.0 + n*0.05 + uAmp*0.1*aRnd + 0.015*sin(t*2.0 + aRnd*20.0));
 vec4 mv = uMV * vec4(d * r, 1.0);
 float depth = smoothstep(-1.0, 1.0, mv.z + uCamZ);
 vA = mix(0.22, 1.0, depth);
 vec3 c = mix(BLUE, CYAN, smoothstep(-0.5, 0.9, d.y + n) * uCyan);
 c = mix(c, PURP, smoothstep(0.1, 0.9, d.x*0.6 - d.y*0.3 + aRnd*0.4) * uPurple);
 c = mix(DEEP, c, 0.5 + 0.5*aRnd);
 vec3 col = c * uBright * mix(0.6, 1.0, depth);
 vC = mix(col, uTint * uBright * mix(0.6, 1.0, depth), uTintMix);
 gl_PointSize = max((0.009 + aRnd*0.017) * (1.0 + uAmp*0.5) * uPx / (-mv.z) * mix(0.85, 1.2, depth), 1.7);
 gl_Position = uProj * mv;
}
"""

/** HTML dotF, shared by the sphere particles and the orbit points. */
private const val DOT_FRAGMENT = """
precision highp float;
varying vec3 vC;
varying float vA;
void main(){
 float d = length(gl_PointCoord - vec2(0.5));
 gl_FragColor = vec4(vC, (1.0 - smoothstep(0.05, 0.5, d)) * vA);
}
"""

private const val QUAD_VERTEX = """
precision highp float;
attribute vec3 aPosition;
attribute vec2 aUv;
uniform mat4 uMV;
uniform mat4 uProj;
varying vec2 vUv;
void main(){
 vUv = aUv;
 gl_Position = uProj * uMV * vec4(aPosition, 1.0);
}
"""

/** HTML glow shader: halo only, beyond 0.68 of the plane radius. */
private const val GLOW_FRAGMENT = """
precision highp float;
varying vec2 vUv;
uniform float uTime;
uniform float uStrength;
uniform float uMix;
uniform vec3 uA;
uniform vec3 uB;
uniform vec3 uC;
uniform vec3 uTint;
uniform float uTintMix;
void main(){
 vec2 c = vUv - vec2(0.5);
 float d = length(c) * 2.0;
 float a = atan(c.y, c.x);
 float g = (d > 0.68 ? exp(-(d - 0.68) * 6.5) * 0.75 : 0.0) * (1.0 - smoothstep(0.78, 1.0, d));
 vec3 col = mix(mix(uA, uB, 0.5 + 0.5 * sin(a + uTime * 0.4)), uC, uMix * (0.5 + 0.5 * sin(a * 2.0 - uTime * 0.6)));
 col = mix(col, uTint, uTintMix * 0.6);
 gl_FragColor = vec4(col, clamp(g * uStrength * 0.5, 0.0, 0.55));
}
"""

private const val RING_VERTEX = """
precision highp float;
attribute vec3 aPosition;
uniform mat4 uMV;
uniform mat4 uProj;
varying vec2 vP;
void main(){
 vP = aPosition.xy;
 gl_Position = uProj * uMV * vec4(aPosition, 1.0);
}
"""

/** HTML ring shader: dashed segments via sin(a * uSegs + uSeed). */
private const val RING_FRAGMENT = """
precision highp float;
varying vec2 vP;
uniform float uAlpha;
uniform float uSeed;
uniform float uSegs;
uniform vec3 uA;
uniform vec3 uB;
uniform vec3 uC;
uniform vec3 uTint;
uniform float uTintMix;
void main(){
 float a = atan(vP.y, vP.x);
 float m = smoothstep(-0.25, 0.35, sin(a * uSegs + uSeed)) * smoothstep(-0.9, -0.2, sin(a + uSeed * 0.7));
 vec3 col = mix(mix(uC, uA, 0.5 + 0.5 * sin(a + uSeed)), uB, 0.5 + 0.5 * sin(a * 2.0 - uSeed));
 col = mix(col, uTint, uTintMix * 0.6);
 gl_FragColor = vec4(col, m * uAlpha);
}
"""

private const val ORBIT_VERTEX = """
precision highp float;
attribute vec3 aPosition;
attribute float aSize;
attribute float aAlpha;
attribute vec3 aColor;
uniform mat4 uMV;
uniform mat4 uProj;
uniform float uPx;
uniform vec3 uTint;
uniform float uTintMix;
varying vec3 vC;
varying float vA;
void main(){
 vA = aAlpha;
 vC = mix(aColor, uTint, uTintMix * 0.7);
 vec4 mv = uMV * vec4(aPosition, 1.0);
 gl_PointSize = max(aSize * uPx / (-mv.z), 1.0);
 gl_Position = uProj * mv;
}
"""

/** Floor shadow: soft radial ellipse (the HTML DOM #shadow div). */
private const val SHADOW_FRAGMENT = """
precision highp float;
varying vec2 vUv;
uniform vec3 uColor;
uniform float uAlpha;
void main(){
 vec2 c = (vUv - vec2(0.5)) * 2.0;
 float d = length(c);
 float a = 1.0 - smoothstep(0.0, 1.0, d);
 gl_FragColor = vec4(uColor, a * a * uAlpha);
}
"""

// ── GL plumbing ──────────────────────────────────────────────────────

private class OrbGlException(message: String) : Exception(message)

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    if (shader == 0) throw OrbGlException("glCreateShader failed")
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        throw OrbGlException("shader compile failed: $log")
    }
    return shader
}

private class GlProgram(vertexSource: String, fragmentSource: String) {
    val handle: Int
    private val attribLocations = HashMap<String, Int>()
    private val uniformLocations = HashMap<String, Int>()

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        if (program == 0) throw OrbGlException("glCreateProgram failed")
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw OrbGlException("program link failed: $log")
        }
        handle = program
    }

    fun use() = GLES20.glUseProgram(handle)

    fun attrib(name: String): Int =
        attribLocations.getOrPut(name) { GLES20.glGetAttribLocation(handle, name) }

    fun uniform(name: String): Int =
        uniformLocations.getOrPut(name) { GLES20.glGetUniformLocation(handle, name) }

    fun setFloat(name: String, value: Float) {
        val loc = uniform(name)
        if (loc >= 0) GLES20.glUniform1f(loc, value)
    }

    fun setVec3(name: String, v: FloatArray) {
        val loc = uniform(name)
        if (loc >= 0) GLES20.glUniform3f(loc, v[0], v[1], v[2])
    }

    fun setMat4(name: String, m: FloatArray) {
        val loc = uniform(name)
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, m, 0)
    }

    fun bindAttrib(name: String, size: Int, buffer: Buffer) {
        val loc = attrib(name)
        if (loc >= 0) {
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, 0, buffer)
        }
    }

    fun unbindAttrib(name: String) {
        val loc = attrib(name)
        if (loc >= 0) GLES20.glDisableVertexAttribArray(loc)
    }
}

private fun floatBufferOf(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(data)
            position(0)
        }

private fun rewriteBuffer(buffer: FloatBuffer, data: FloatArray) {
    buffer.clear()
    buffer.put(data)
    buffer.position(0)
}

/** One row of the HTML STATES table. ERROR reuses the speaking row. */
private class OrbParams(
    var scale: Float,
    var speed: Float,
    var cyan: Float,
    var purple: Float,
    var bright: Float,
    var glow: Float,
    var ring: Float,
    var wob: Float
) {
    fun lerpTo(target: OrbParams, k: Float) {
        scale += (target.scale - scale) * k
        speed += (target.speed - speed) * k
        cyan += (target.cyan - cyan) * k
        purple += (target.purple - purple) * k
        bright += (target.bright - bright) * k
        glow += (target.glow - glow) * k
        ring += (target.ring - ring) * k
        wob += (target.wob - wob) * k
    }

    companion object {
        // HTML: idle     {1.00, .35, .35, .45, 1.00, .50, .15, 0}
        //       listening{1.05, .85, 1.00, .25, 1.12, .95, .35, 0}
        //       thinking {1.00, 1.55, .30, 1.00, 1.05, .75, 1.00, 1}
        //       speaking {1.04, 1.15, .90, .90, 1.20, 1.00, .45, 0}
        private val IDLE = OrbParams(1.00f, 0.35f, 0.35f, 0.45f, 1.00f, 0.50f, 0.15f, 0f)
        private val LISTENING = OrbParams(1.05f, 0.85f, 1.00f, 0.25f, 1.12f, 0.95f, 0.35f, 0f)
        private val THINKING = OrbParams(1.00f, 1.55f, 0.30f, 1.00f, 1.05f, 0.75f, 1.00f, 1f)
        private val SPEAKING = OrbParams(1.04f, 1.15f, 0.90f, 0.90f, 1.20f, 1.00f, 0.45f, 0f)

        fun targetFor(state: OrbState): OrbParams = when (state) {
            OrbState.IDLE -> IDLE
            OrbState.LISTENING -> LISTENING
            OrbState.THINKING -> THINKING
            OrbState.SPEAKING, OrbState.ERROR -> SPEAKING
        }
    }
}

// ── Renderer ─────────────────────────────────────────────────────────

/**
 * The Three.js scene, redrawn with raw GLES20:
 *
 * draw order (HTML renderOrder, depth test/write off throughout —
 * every HTML material is transparent with depthWrite:false and the
 * glow also depthTest:false, so painter's order is the port):
 * shadow quad → glow plane → ring1 → ring2 → sphere points (inside
 * the rotating/scaling "orb group") → orbit points.
 */
class JarvisOrbRenderer(
    private val onGlError: () -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val PARTICLE_COUNT = 4500 // HTML 6000, capped per F3 spec
        private const val ORBIT_COUNT = 36
        private const val FOV_DEG = 45f
        private const val VIS = 3.1f
        private const val EXTERNAL_FRESH_MS = 220L
        private val ERROR_TINT = floatArrayOf(0.98f, 0.20f, 0.26f)
        private const val ERROR_TINT_MIX = 0.72f
    }

    // Written from the UI thread, read on the GL thread.
    @Volatile
    var targetState: OrbState = OrbState.IDLE

    @Volatile
    private var palette: OrbPaletteData = ORB_PALETTE_LIGHT

    @Volatile
    private var paletteDirty = true

    @Volatile
    private var dragging = false

    @Volatile
    private var velX = 0f

    @Volatile
    private var velY = 0f

    @Volatile
    private var extLevel = 0f

    @Volatile
    private var extBass = 0f

    @Volatile
    private var extHigh = 0f

    @Volatile
    private var extTimeMs = Long.MIN_VALUE / 2

    /** Live audio feed (HTML setExternalAudio): clamps to 0..1. */
    fun setExternalAudio(level: Float, bass: Float, high: Float) {
        extLevel = level.coerceIn(0f, 1f)
        extBass = bass.coerceIn(0f, 1f)
        extHigh = high.coerceIn(0f, 1f)
        extTimeMs = SystemClock.uptimeMillis()
    }

    fun setPalette(newPalette: OrbPaletteData) {
        if (!palette.sameAs(newPalette)) {
            palette = newPalette
            paletteDirty = true
        }
    }

    fun setDragging(value: Boolean) {
        dragging = value
    }

    /** HTML pointermove: velocity += delta * 0.012 (per CSS px ≈ dp). */
    fun addDrag(dxDp: Float, dyDp: Float) {
        velY += dxDp * 0.012f
        velX += dyDp * 0.012f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var errorNotified = false

    /** Surface-construction failure route (called from the factory). */
    fun reportFailure() = notifyError()

    private fun notifyError() {
        if (errorNotified) return
        errorNotified = true
        mainHandler.post { onGlError() }
    }

    // GL-thread-only state below.
    private var ready = false
    private var particleProgram: GlProgram? = null
    private var glowProgram: GlProgram? = null
    private var ringProgram: GlProgram? = null
    private var orbitProgram: GlProgram? = null
    private var shadowProgram: GlProgram? = null

    private var particlePosBuffer: FloatBuffer? = null
    private var particleRndBuffer: FloatBuffer? = null
    private var glowQuadBuffer: FloatBuffer? = null
    private var glowUvBuffer: FloatBuffer? = null
    private var shadowQuadBuffer: FloatBuffer? = null
    private var shadowUvBuffer: FloatBuffer? = null
    private var ring1Buffer: FloatBuffer? = null
    private var ring2Buffer: FloatBuffer? = null
    private var orbitPosBuffer: FloatBuffer? = null
    private var orbitSizeBuffer: FloatBuffer? = null
    private var orbitAlphaBuffer: FloatBuffer? = null
    private var orbitColorBuffer: FloatBuffer? = null

    private val orbitPos = FloatArray(ORBIT_COUNT * 3)
    private val orbitAlpha = FloatArray(ORBIT_COUNT)
    private val orbitColors = FloatArray(ORBIT_COUNT * 3)
    private val orbitAngle = FloatArray(ORBIT_COUNT)
    private val orbitRadius = FloatArray(ORBIT_COUNT)
    private val orbitSpeed = FloatArray(ORBIT_COUNT)
    private val orbitPhase = FloatArray(ORBIT_COUNT)
    private val orbitTilt = FloatArray(ORBIT_COUNT)
    private val orbitY = FloatArray(ORBIT_COUNT)
    private val orbitFreq = FloatArray(ORBIT_COUNT)

    private val cur = OrbParams(1.00f, 0.35f, 0.35f, 0.45f, 1.00f, 0.50f, 0.15f, 0f)
    private var tintMix = 0f
    private var time = 0f
    private var flow = 0f
    private var lastFrameNs = 0L
    private var rotX = 0f
    private var rotY = 0f
    private var ring1Angle = 0f
    private var ring2Angle = 0f
    private var audioLevel = 0f
    private var audioBass = 0f
    private var audioHigh = 0f

    private var glWidth = 1
    private var glHeight = 1
    private var camZ = 6f
    private var uPx = 300f

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)

    private val simAudio = FloatArray(3)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            particleProgram = GlProgram(PARTICLE_VERTEX, DOT_FRAGMENT)
            glowProgram = GlProgram(QUAD_VERTEX, GLOW_FRAGMENT)
            ringProgram = GlProgram(RING_VERTEX, RING_FRAGMENT)
            orbitProgram = GlProgram(ORBIT_VERTEX, DOT_FRAGMENT)
            shadowProgram = GlProgram(QUAD_VERTEX, SHADOW_FRAGMENT)
            buildGeometry()
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            ready = true
        } catch (e: Exception) {
            ready = false
            notifyError()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glWidth = width.coerceAtLeast(1)
        glHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, glWidth, glHeight)
        // HTML handleResize: visH = VIS / min(1, w/h);
        // camera.z = visH / (2 * tan(FOV/2)); uPx = bufferH / (2 * tan).
        val aspect = glWidth.toFloat() / glHeight.toFloat()
        val visH = VIS / min(1f, aspect)
        val tanHalf = tan(Math.toRadians((FOV_DEG / 2f).toDouble())).toFloat()
        camZ = visH / (2f * tanHalf)
        uPx = glHeight / (2f * tanHalf)
        Matrix.perspectiveM(projMatrix, 0, FOV_DEG, aspect, 0.1f, 50f)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, camZ, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) return
        try {
            drawFrame()
        } catch (e: Exception) {
            ready = false
            notifyError()
        }
    }

    /** HTML buildParticles / createParticles / geometry construction. */
    private fun buildGeometry() {
        val random = Random()

        // Fibonacci sphere, shell 0.93–1.0 (HTML constant 2.39996).
        val positions = FloatArray(PARTICLE_COUNT * 3)
        val rnd = FloatArray(PARTICLE_COUNT)
        for (i in 0 until PARTICLE_COUNT) {
            val y = 1f - 2f * (i + 0.5f) / PARTICLE_COUNT
            val r = sqrt(1f - y * y)
            val th = i * 2.39996f
            val shell = 0.93f + 0.07f * random.nextFloat()
            positions[i * 3] = cos(th) * r * shell
            positions[i * 3 + 1] = y * shell
            positions[i * 3 + 2] = sin(th) * r * shell
            rnd[i] = random.nextFloat()
        }
        particlePosBuffer = floatBufferOf(positions)
        particleRndBuffer = floatBufferOf(rnd)

        // Glow plane: PlaneGeometry(VIS, VIS) centred at the origin.
        val half = VIS / 2f
        glowQuadBuffer = floatBufferOf(
            floatArrayOf(
                -half, -half, 0f, half, -half, 0f,
                -half, half, 0f, half, half, 0f
            )
        )
        glowUvBuffer = floatBufferOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        // Shadow quad: unit plane, sized by the model matrix per frame.
        shadowQuadBuffer = floatBufferOf(
            floatArrayOf(
                -0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f,
                -0.5f, 0.5f, 0f, 0.5f, 0.5f, 0f
            )
        )
        shadowUvBuffer = floatBufferOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        ring1Buffer = floatBufferOf(ringGeometry(1.255f, 1.272f))
        ring2Buffer = floatBufferOf(ringGeometry(1.380f, 1.388f))

        // Orbiting particles (HTML createParticles ptData).
        val sizes = FloatArray(ORBIT_COUNT)
        for (i in 0 until ORBIT_COUNT) {
            sizes[i] = 0.02f + random.nextFloat() * 0.045f
            orbitAngle[i] = random.nextFloat() * 6.283f
            orbitRadius[i] = 1.5f + random.nextFloat() * 0.55f
            orbitSpeed[i] =
                (0.08f + random.nextFloat() * 0.18f) * (if (random.nextFloat() < 0.5f) -1f else 1f)
            orbitPhase[i] = random.nextFloat() * 6.283f
            orbitTilt[i] = (random.nextFloat() - 0.5f) * 1.2f
            orbitY[i] = (random.nextFloat() - 0.5f) * 1.1f
            orbitFreq[i] = 0.2f + random.nextFloat() * 0.4f
        }
        orbitSizeBuffer = floatBufferOf(sizes)
        orbitPosBuffer = floatBufferOf(orbitPos)
        orbitAlphaBuffer = floatBufferOf(orbitAlpha)
        orbitColorBuffer = floatBufferOf(orbitColors)
        paletteDirty = true
    }

    /** RingGeometry(r0, r1, 128) as a triangle strip (inner/outer pairs). */
    private fun ringGeometry(r0: Float, r1: Float): FloatArray {
        val segments = 128
        val out = FloatArray((segments + 1) * 2 * 3)
        var o = 0
        for (i in 0..segments) {
            val a = (i.toFloat() / segments) * (Math.PI.toFloat() * 2f)
            val ca = cos(a)
            val sa = sin(a)
            out[o++] = ca * r0; out[o++] = sa * r0; out[o++] = 0f
            out[o++] = ca * r1; out[o++] = sa * r1; out[o++] = 0f
        }
        return out
    }

    private fun drawFrame() {
        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs == 0L) {
            0.016f
        } else {
            min(((nowNs - lastFrameNs) / 1e9f), 0.05f)
        }
        lastFrameNs = nowNs

        // HTML updateOrb: state lerp k = 1 − exp(−dt × 3.5).
        val stateKind = targetState
        val target = OrbParams.targetFor(stateKind)
        val k = 1f - exp(-dt * 3.5f)
        cur.lerpTo(target, k)
        val tintTarget = if (stateKind == OrbState.ERROR) ERROR_TINT_MIX else 0f
        tintMix += (tintTarget - tintMix) * k

        time += dt
        flow += dt * cur.speed

        updateAudio(dt, stateKind)

        val idlePulse = maxOf(0f, sin(time * 0.5f)).pow(12) * 0.15f * (1f - cur.wob)
        val scale = cur.scale *
            (1f + 0.018f * sin(time * 1.2f) + audioLevel * 0.07f +
                cur.wob * 0.025f * sin(time * 3.6f))

        // Rotation + drag velocity (HTML formulas verbatim).
        val dragStep = min(dt * 20f, 1f) * 0.5f
        rotY += dt * 0.18f * (0.5f + cur.speed) + velY * dragStep
        rotX = (rotX + velX * dragStep).coerceIn(-1.2f, 1.2f)
        val velDecay = exp(-dt * 5f)
        velX *= velDecay
        velY *= velDecay
        if (!dragging && abs(rotX) > 0.001f) rotX *= exp(-dt * 0.6f)

        ring1Angle += dt * (0.12f + cur.ring * 0.6f)
        ring2Angle -= dt * (0.08f + cur.ring * 0.35f)

        if (paletteDirty) {
            // Orbit colours cycle [CYAN, BLUE, PURP] of the palette.
            val pal = palette
            val cycle = arrayOf(pal.cyan, pal.blue, pal.purp)
            for (i in 0 until ORBIT_COUNT) {
                val c = cycle[i % 3]
                orbitColors[i * 3] = c[0]
                orbitColors[i * 3 + 1] = c[1]
                orbitColors[i * 3 + 2] = c[2]
            }
            orbitColorBuffer?.let { rewriteBuffer(it, orbitColors) }
            paletteDirty = false
        }
        updateOrbitParticles(dt, stateKind)

        val pal = palette
        GLES20.glViewport(0, 0, glWidth, glHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)

        drawShadow(scale)
        drawGlow(pal, idlePulse)
        val ringPulse = cur.ring * 0.5f * (0.5f + 0.5f * sin(time * 4f)) + audioBass * 0.8f
        drawRing(
            ring1Buffer, ring1Angle, scale * (1f + ringPulse * 0.03f),
            alpha = 0.4f + cur.ring * 0.3f + audioLevel * 0.35f,
            seed = 0f, segs = 2f, pal = pal
        )
        drawRing(
            ring2Buffer, ring2Angle, scale * (1f + ringPulse * 0.02f),
            alpha = 0.22f + cur.ring * 0.2f + audioLevel * 0.2f,
            seed = 2.1f, segs = 3f, pal = pal
        )
        drawParticles(pal, scale, idlePulse)
        drawOrbitParticles(pal)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** HTML readAudio: fresh external feed wins, else simulation. */
    private fun updateAudio(dt: Float, stateKind: OrbState) {
        val targetTriple: FloatArray =
            if (SystemClock.uptimeMillis() - extTimeMs < EXTERNAL_FRESH_MS) {
                simAudio[0] = extLevel; simAudio[1] = extBass; simAudio[2] = extHigh
                simAudio
            } else {
                simulateAudio(time, stateKind)
            }
        audioLevel = smoothAudio(audioLevel, targetTriple[0], dt)
        audioBass = smoothAudio(audioBass, targetTriple[1], dt)
        audioHigh = smoothAudio(audioHigh, targetTriple[2], dt)
    }

    /** Attack 18 /s, release 6 /s — HTML `k = 1 − exp(−dt × rate)`. */
    private fun smoothAudio(current: Float, target: Float, dt: Float): Float {
        val rate = if (target > current) 18f else 6f
        return current + (target - current) * (1f - exp(-dt * rate))
    }

    /** HTML simulateAudio(t) — verbatim per-state patterns. */
    private fun simulateAudio(t: Float, stateKind: OrbState): FloatArray {
        var l: Float
        var b: Float
        var h: Float
        when (stateKind) {
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
        simAudio[0] = l; simAudio[1] = b; simAudio[2] = h
        return simAudio
    }

    /** HTML updateParticles: tilted orbits, alpha pulse per particle. */
    private fun updateOrbitParticles(dt: Float, stateKind: OrbState) {
        val thinking = stateKind == OrbState.THINKING
        val boost = 0.6f + cur.speed * 0.8f + (if (thinking) 1.2f else 0f)
        for (i in 0 until ORBIT_COUNT) {
            orbitAngle[i] += orbitSpeed[i] * dt * boost
            val a = orbitAngle[i]
            val r = orbitRadius[i] +
                sin(time * orbitFreq[i] + orbitPhase[i]) * 0.12f -
                (if (thinking) 0.15f else 0f) +
                audioLevel * 0.1f
            val x = cos(a) * r
            val z = sin(a) * r * 0.6f
            val y = orbitY[i] * 0.8f + sin(a * 1.3f + orbitPhase[i]) * 0.18f + x * orbitTilt[i] * 0.25f
            orbitPos[i * 3] = x
            orbitPos[i * 3 + 1] = y
            orbitPos[i * 3 + 2] = z
            orbitAlpha[i] =
                (0.5f + 0.5f * sin(time * orbitFreq[i] * 1.6f + orbitPhase[i])).pow(1.5f) *
                    (0.55f + cur.glow * 0.3f)
        }
        orbitPosBuffer?.let { rewriteBuffer(it, orbitPos) }
        orbitAlphaBuffer?.let { rewriteBuffer(it, orbitAlpha) }
    }

    private fun applyBlend(additive: Boolean) {
        if (additive) {
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        } else {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    /** Model = view-space transform for one draw; returns via [mvMatrix]. */
    private fun setModel(setup: () -> Unit) {
        Matrix.setIdentityM(modelMatrix, 0)
        setup()
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    }

    private fun radToDeg(rad: Float): Float = (rad * 180.0 / Math.PI).toFloat()

    private fun drawShadow(scale: Float) {
        val prog = shadowProgram ?: return
        val pos = shadowQuadBuffer ?: return
        val uv = shadowUvBuffer ?: return
        val pal = palette
        // HTML #shadow float keyframes (5 s): opacity → .6, scaleX → .9
        // at the midpoint; width 1.5 world units × orb scale, centred
        // 1.2 units below the orb centre.
        val floatPhase = 0.5f + 0.5f * sin(time * (2f * Math.PI.toFloat() / 5f) - Math.PI.toFloat() / 2f)
        val scaleX = 1f - 0.1f * floatPhase
        val alpha = pal.shadowAlpha * (1f - 0.4f * floatPhase)
        applyBlend(additive = false)
        prog.use()
        setModel {
            Matrix.translateM(modelMatrix, 0, 0f, -1.2f, -0.1f)
            Matrix.scaleM(modelMatrix, 0, 1.5f * scale * scaleX, 0.13f, 1f)
        }
        prog.setMat4("uMV", mvMatrix)
        prog.setMat4("uProj", projMatrix)
        prog.setVec3("uColor", pal.shadowRgb)
        prog.setFloat("uAlpha", alpha)
        prog.bindAttrib("aPosition", 3, pos)
        prog.bindAttrib("aUv", 2, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        prog.unbindAttrib("aPosition")
        prog.unbindAttrib("aUv")
    }

    private fun drawGlow(pal: OrbPaletteData, idlePulse: Float) {
        val prog = glowProgram ?: return
        val pos = glowQuadBuffer ?: return
        val uv = glowUvBuffer ?: return
        applyBlend(pal.additive)
        prog.use()
        setModel {
            // HTML glow plane sits at z = −0.05 behind the orb.
            Matrix.translateM(modelMatrix, 0, 0f, 0f, -0.05f)
        }
        prog.setMat4("uMV", mvMatrix)
        prog.setMat4("uProj", projMatrix)
        prog.setFloat("uTime", time)
        prog.setFloat("uStrength", cur.glow + audioLevel * 0.6f + idlePulse * 2f)
        prog.setFloat("uMix", cur.cyan)
        prog.setVec3("uA", pal.blue)
        prog.setVec3("uB", pal.purp)
        prog.setVec3("uC", pal.cyan)
        prog.setVec3("uTint", ERROR_TINT)
        prog.setFloat("uTintMix", tintMix)
        prog.bindAttrib("aPosition", 3, pos)
        prog.bindAttrib("aUv", 2, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        prog.unbindAttrib("aPosition")
        prog.unbindAttrib("aUv")
    }

    private fun drawRing(
        buffer: FloatBuffer?,
        angleRad: Float,
        scale: Float,
        alpha: Float,
        seed: Float,
        segs: Float,
        pal: OrbPaletteData
    ) {
        val prog = ringProgram ?: return
        val buf = buffer ?: return
        applyBlend(pal.additive)
        prog.use()
        setModel {
            Matrix.rotateM(modelMatrix, 0, radToDeg(angleRad), 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
        }
        prog.setMat4("uMV", mvMatrix)
        prog.setMat4("uProj", projMatrix)
        prog.setFloat("uAlpha", alpha)
        prog.setFloat("uSeed", seed)
        prog.setFloat("uSegs", segs)
        prog.setVec3("uA", pal.blue)
        prog.setVec3("uB", pal.purp)
        prog.setVec3("uC", pal.cyan)
        prog.setVec3("uTint", ERROR_TINT)
        prog.setFloat("uTintMix", tintMix)
        prog.bindAttrib("aPosition", 3, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 129 * 2)
        prog.unbindAttrib("aPosition")
    }

    private fun drawParticles(pal: OrbPaletteData, scale: Float, idlePulse: Float) {
        val prog = particleProgram ?: return
        val pos = particlePosBuffer ?: return
        val rnd = particleRndBuffer ?: return
        applyBlend(pal.additive)
        prog.use()
        setModel {
            // The HTML "orb group": X tilt then Y spin then scale.
            Matrix.rotateM(modelMatrix, 0, radToDeg(rotX), 1f, 0f, 0f)
            Matrix.rotateM(modelMatrix, 0, radToDeg(rotY), 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
        }
        prog.setMat4("uMV", mvMatrix)
        prog.setMat4("uProj", projMatrix)
        prog.setFloat("uTime", flow)
        prog.setFloat("uAmp", audioLevel + cur.wob * 0.25f)
        // Set like the HTML shared uniforms; the particle shader
        // declares but does not read them (bass drives the ring
        // pulse on the CPU side, high is carried for parity).
        prog.setFloat("uBass", audioBass)
        prog.setFloat("uHigh", audioHigh)
        prog.setFloat("uCyan", cur.cyan)
        prog.setFloat("uPurple", cur.purple)
        prog.setFloat("uBright", cur.bright + idlePulse)
        prog.setFloat("uPx", uPx)
        prog.setFloat("uCamZ", camZ)
        prog.setVec3("DEEP", pal.deep)
        prog.setVec3("BLUE", pal.blue)
        prog.setVec3("CYAN", pal.cyan)
        prog.setVec3("PURP", pal.purp)
        prog.setVec3("uTint", ERROR_TINT)
        prog.setFloat("uTintMix", tintMix)
        prog.bindAttrib("aPosition", 3, pos)
        prog.bindAttrib("aRnd", 1, rnd)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, PARTICLE_COUNT)
        prog.unbindAttrib("aPosition")
        prog.unbindAttrib("aRnd")
    }

    private fun drawOrbitParticles(pal: OrbPaletteData) {
        val prog = orbitProgram ?: return
        val pos = orbitPosBuffer ?: return
        val size = orbitSizeBuffer ?: return
        val alpha = orbitAlphaBuffer ?: return
        val color = orbitColorBuffer ?: return
        applyBlend(pal.additive)
        prog.use()
        setModel { /* scene space, like the HTML orbit points */ }
        prog.setMat4("uMV", mvMatrix)
        prog.setMat4("uProj", projMatrix)
        prog.setFloat("uPx", uPx)
        prog.setVec3("uTint", ERROR_TINT)
        prog.setFloat("uTintMix", tintMix)
        prog.bindAttrib("aPosition", 3, pos)
        prog.bindAttrib("aSize", 1, size)
        prog.bindAttrib("aAlpha", 1, alpha)
        prog.bindAttrib("aColor", 3, color)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, ORBIT_COUNT)
        prog.unbindAttrib("aPosition")
        prog.unbindAttrib("aSize")
        prog.unbindAttrib("aAlpha")
        prog.unbindAttrib("aColor")
    }
}

// ── Surface view ─────────────────────────────────────────────────────

/**
 * Transparent GLSurfaceView hosting [JarvisOrbRenderer] (HTML
 * `alpha: true` + clear alpha 0). Rendering resolution is capped at
 * a pixel ratio of 2 (HTML `setPixelRatio(min(devicePixelRatio,2))`)
 * by fixing the surface size on high-density displays.
 */
class OrbGlSurfaceView(
    context: Context,
    renderer: JarvisOrbRenderer
) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        runCatching {
            val density = resources.displayMetrics.density
            val ratioCap = min(1f, 2f / density)
            if (ratioCap < 0.999f && w > 0 && h > 0) {
                holder.setFixedSize(
                    (w * ratioCap).roundToInt().coerceAtLeast(1),
                    (h * ratioCap).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }
}

package com.nshd.jarvis2.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.CrashLogger
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.ExecutionLedger
import com.nshd.jarvis2.brain.ModelDiscovery
import com.nshd.jarvis2.brain.PersonaPrompts
import com.nshd.jarvis2.brain.ProviderPresets
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.brain.TaskQueue
import com.nshd.jarvis2.brain.ToolEvent
import com.nshd.jarvis2.brain.ToolEventKind
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.brain.tools.ToolRegistry
import com.nshd.jarvis2.brain.tools.SendAnswer
import com.nshd.jarvis2.brain.tools.SendAsk
import com.nshd.jarvis2.brain.tools.ToolResult
import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.service.ScreenShareState
import com.nshd.jarvis2.ui.AppTheme
import com.nshd.jarvis2.ui.FriendlyErrors
import com.nshd.jarvis2.service.WakeWordControl
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class VoiceMode { NONE, LIVE }

enum class VoiceStatus {
    IDLE, CONNECTING, LISTENING, SPEAKING, THINKING, ERROR
}

/** Which engine the user's Voice Engine setting asks for (F2). */
enum class VoiceEngine { AUTO, NATIVE }

enum class CaptionKind { USER, ASSISTANT, TOOL, SYSTEM }

data class VoiceCaption(
    val fromUser: Boolean,
    val text: String,
    val kind: CaptionKind = if (fromUser) CaptionKind.USER else CaptionKind.ASSISTANT,
    /** Still streaming in (partial STT / growing reply) — rendered dimmed. */
    val partial: Boolean = false
)

data class VoiceUiState(
    val status: VoiceStatus = VoiceStatus.IDLE,
    val mode: VoiceMode = VoiceMode.NONE,
    val voiceName: String = "",
    val liveModel: String = "",
    /** Mode-badge text: "LIVE • Kore". */
    val engineLabel: String = "",
    val micMuted: Boolean = false,
    /**
     * F5 (was `notice`): transient recognizer hint ("Didn't catch
     * that"…). Cleared when a new listen cycle starts, when speech
     * begins, on a final result, and when a turn completes — it must
     * never linger under a later "speaking" state.
     */
    val sttHint: String? = null,
    val errorMessage: String? = null
)

/**
 * F8 (#1): Live self-diagnosis for the Voice screen's "Why?" dialog —
 * the last Live failure, explained in plain language, with the recent
 * Live log trail and one concrete next step.
 */
data class LiveDiagnosis(
    val headline: String,
    val rawDetail: String?,
    val logLines: List<String>,
    val nextStep: String,
    val failedModel: String
)

/**
 * App-level voice session owner. ONE engine (v2.0.3): NATIVE LIVE
 * (Gemini key + a native-audio Live model): GeminiLiveClient
 * WebSocket + 16 kHz capture uplink + 24 kHz playback, server VAD
 * turn-taking, barge-in flush, session resumption across the ~10 min
 * server limit. The v2.0.2 system-voice (Universal) fallback engine
 * is REMOVED: if Live setup/connection fails, the session ENDS with
 * the friendly error + the Voice screen's "Why?" diagnosis — it never
 * switches to a system SpeechRecognizer/TTS mode.
 *
 * Engine choice: JarvisSettings.voiceEngine — AUTO behaves as NATIVE
 * (a stored "UNIVERSAL" value migrates to NATIVE on load); NATIVE
 * forces Live. A non-Gemini provider has no Live engine, so its
 * session fails with the diagnosis instead of silently degrading.
 *
 * Finished turns on both paths are persisted into MemoryDb chat
 * history. Tool activity renders as status chips (CaptionKind.TOOL),
 * never as raw result text. In Silent Execution mode nothing is
 * spoken — a subtle chime marks each finished turn.
 */
object VoiceSessionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _captions = MutableStateFlow<List<VoiceCaption>>(emptyList())
    val captions: StateFlow<List<VoiceCaption>> = _captions.asStateFlow()

    @Volatile
    private var active = false

    // Session inputs, kept so retry() can rebuild the session.
    private var appContext: Context? = null
    private var settings: JarvisSettings? = null
    private var providerStore: ProviderStore? = null
    private var memoryDb: MemoryDb? = null

    private var client: GeminiLiveClient? = null
    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null
    private var chime: ChimePlayer? = null

    // Turn accumulation (transcripts / streamed replies arrive incrementally).
    private val userTurn = StringBuilder()
    private val assistantTurn = StringBuilder()
    private var userCaptionIndex = -1
    private var assistantCaptionIndex = -1

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var reconnecting = false


    /** F5: full reason of the last Native Live failure this session. */
    @Volatile
    private var lastLiveFailureReason: String? = null

    /**
     * F5.1: the current Live session's resumption handle — IN-MEMORY
     * ONLY, never persisted. Handles live only minutes; a stored one
     * sent on a fresh connect makes Google reject the setup before
     * the model is reached ("session expired / not found / invalid
     * session handle" — the user's repeated Live failures).
     */
    @Volatile
    private var liveSessionHandle: String? = null

    /** F5.1: the one clean-session retry for this session start is spent. */
    @Volatile
    private var liveCleanRetried = false

    // ── F8 (#2) Live model fallback chain ─────────────────────────────
    // When a Live start fails with a MODEL-class error, the next
    // candidate model is tried (fresh client/session each) BEFORE the
    // session is failed. A working fallback is remembered in-memory
    // for this app run only — the user's setting is never rewritten.

    /** Model of the CURRENT Live attempt (may be a fallback candidate). */
    @Volatile
    private var liveAttemptModel: String = ""

    /** Candidate Live models for this session start, in try order. */
    @Volatile
    private var liveCandidates: List<String> = emptyList()

    /** Index into [liveCandidates] of the current attempt. */
    @Volatile
    private var liveCandidateIndex = 0

    /** True once setup completed for the current Live attempt. */
    @Volatile
    private var liveSetupDone = false

    /**
     * v2.0.1: Live setup paths Gemini rejected this app run ("Invalid
     * value at 'setup.…'" — e.g. the invalid START_SENSITIVITY_MED
     * enum v1.8.0 sent). ONE set instance is handed to every
     * GeminiLiveClient built here: the client strips + remembers
     * paths in it (its self-healing setup), and every later connect
     * this run — chain attempts and goAway reconnects alike — builds
     * its setup without them.
     */
    private val liveStrippedSetupPaths: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /** Live model that last connected OK — in-memory, app run only. */
    @Volatile
    private var rememberedWorkingLiveModel: String? = null

    // ── F8 (#13) weak-network guard ───────────────────────────────────
    // A slow setup (> 6 s) or two consecutive network-class Live
    // failures this app run marks the network as weak: the NEXT fresh
    // Live start gets a 6 s setup cap (instead of 12 s) so the
    // the failure surfaces fast instead of hanging.

    @Volatile
    private var liveConnectStartMs = 0L

    @Volatile
    private var lastLiveSetupMs = -1L

    @Volatile
    private var consecutiveLiveNetworkFailures = 0

    /** The current fresh start is running under the weak-network cap. */
    @Volatile
    private var liveWeakNetworkStart = false

    // F3 Live tool-call dedupe: Gemini toolCall ids already processed in
    // this session, mapped to the exact response that was sent. A
    // duplicate id is answered with the cached response, never
    // re-executed.
    private val liveProcessedToolCalls = mutableMapOf<String, JSONObject>()

    // F3 per-Live-turn execution ledger (reset at every finalizeLiveTurn).
    @Volatile
    private var liveLedger = ExecutionLedger()

    // F6: Live tool turns register in the TaskQueue + TaskState so
    // the overlay HUD lists them. Before F6 this path executed tools
    // directly and produced NO queue/HUD data at all — the HUD sat at
    // "0 of 0 done — idle" while Live voice tasks (e.g. Spotify via
    // generic taps) were visibly running. A "task window" spans from
    // the first tool call of a Live turn to finalizeLiveTurn.
    @Volatile
    private var liveTaskOpen = false

    @Volatile
    private var liveTaskFailed = false

    @Volatile
    private var liveTaskJob: Job? = null

    // F4.1 stage-log one-shot flags (reset per engine start).
    @Volatile
    private var liveFirstFrameLogged = false

    /**
     * F3 voice auto-listen guard: set when a media-play tool succeeds.
     * v2.0.3: kept for the mic-tap lift in setMicMuted; Live listens
     * continuously via server VAD, so nothing auto-restarts.
     */
    @Volatile
    private var mediaListenBlocked = false

    /**
     * v1.8 (#8): "Stop reading" was tapped for the CURRENT turn —
     * speech is halted and any sentences the pipeline still streams
     * for this turn are swallowed instead of spoken. Cleared when a
     * new turn is dispatched.
     */
    @Volatile
    private var readingSuppressed = false

    // ── v1.8 (#11) phone-call auto-pause / resume ────────────────────
    // While a session is active, a call-state watch (registered only
    // when READ_PHONE_STATE is already granted — never requested)
    // pauses the session when a call rings/connects and resumes the
    // same engine when the call ends.

    /** Session is currently paused for a phone call. */
    @Volatile
    private var callPaused = false

    /** A resume is owed when the call returns to IDLE. */
    @Volatile
    private var callResumePending = false

    /** Engine that was running when the call interrupted. */
    @Volatile
    private var callPausedMode: VoiceMode = VoiceMode.NONE

    /** TelephonyCallback (API 31+) or PhoneStateListener, held as Any. */
    private var callWatch: Any? = null

    private var callWatchManager: TelephonyManager? = null

    /** v2.0.0: pending Live session time warning (see onSetupComplete). */
    private var liveTimeWarningJob: Job? = null

    /** Startup / Live setup coroutine; cancelled whenever the session is stopped. */
    private var sessionJob: Job? = null

    /** Delayed reconnect; cancelled when the session ends or is replaced. */
    private var reconnectJob: Job? = null

    private var pulseJob: Job? = null

    /** Screen-share → Live video forwarder (Level 5), Live path only. */
    private var shareJob: Job? = null

    private val isSilent: Boolean
        get() {
            if (settings?.silentExecution == true) return true
            // v1.9.0 (#87): DND respect — while the phone is in
            // Do-Not-Disturb, voice sessions behave as if Silent
            // Execution were on (a subtle chime still marks turns).
            val s = settings ?: return false
            if (!s.dndRespect) return false
            val ctx = appContext ?: return false
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return false
            return nm.currentInterruptionFilter ==
                android.app.NotificationManager.INTERRUPTION_FILTER_NONE ||
                nm.currentInterruptionFilter ==
                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                nm.currentInterruptionFilter ==
                android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
        }

    // ── v1.9.0 (#14): push-to-talk ───────────────────────────────────
    // Hold = talk now; release = finish this utterance. Live: press
    // unmutes the mic, release mutes it again.

    fun pttDown() {
        if (!active) return
        mediaListenBlocked = false
        when (_uiState.value.mode) {
            VoiceMode.LIVE -> setMicMuted(false)
            VoiceMode.NONE -> Unit
        }
    }

    fun pttUp() {
        if (!active) return
        when (_uiState.value.mode) {
            VoiceMode.LIVE -> setMicMuted(true)
            VoiceMode.NONE -> Unit
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * F4 hardening: engine start must never crash the caller (the
     * Voice screen's LaunchedEffect, retry, or the wake-word service).
     * Any exception from session setup is routed to [fail] — the
     * existing error-posting helper, which tears down capture /
     * player / recognizer partial state (teardownLive + Universal
     * shutdown) and posts the ERROR UiState; the raw message is kept
     * so the UI renders it through FriendlyErrors with its Details
     * expander. Nothing propagates out of this function.
     */
    fun start(
        context: Context,
        settings: JarvisSettings,
        providerStore: ProviderStore,
        memoryDb: MemoryDb
    ) {
        try {
            startSession(context, settings, providerStore, memoryDb)
        } catch (e: Exception) {
            runCatching { fail(startFailureMessage(e)) }
        }
    }

    private fun startSession(
        context: Context,
        settings: JarvisSettings,
        providerStore: ProviderStore,
        memoryDb: MemoryDb
    ) {
        stopInternal(resetUi = true)
        this.appContext = context.applicationContext
        this.settings = settings
        this.providerStore = providerStore
        this.memoryDb = memoryDb
        this.chime = ChimePlayer()
        active = true
        reconnectAttempts = 0
        lastLiveFailureReason = null
        liveSessionHandle = null
        liveCleanRetried = false
        // F5.1: drop any legacy persisted resumption handle (builds
        // ≤1.4.0 stored one in SharedPreferences). It is never read
        // again — handles are in-memory only now — and removing it
        // keeps the stale value out of the app entirely.
        runCatching {
            appContext?.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.remove(KEY_RESUMPTION_HANDLE)?.apply()
        }
        synchronized(liveProcessedToolCalls) { liveProcessedToolCalls.clear() }
        liveLedger = ExecutionLedger()
        mediaListenBlocked = false
        readingSuppressed = false
        callPaused = false
        callResumePending = false
        callPausedMode = VoiceMode.NONE
        _captions.value = emptyList()
        CrashLogger.stage(TAG, "session start (engine setting=${settings.voiceEngine})")
        AppLog.i(
            "Voice",
            "session start engine=${settings.voiceEngine} persona=${settings.persona} " +
                "language=${settings.language}"
        )
        // v1.8 (#11): watch for phone calls so the session can pause
        // for the call and resume after it (no-op without permission).
        registerCallWatch()
        // F4.1 mic arbitration handshake: the wake-word service must
        // have FULLY released the microphone (Vosk SpeechService stop
        // is asynchronous) before any engine touches it — two live mic
        // consumers racing is a classic OEM-stack crash. The engine
        // starts only after acquireForSession confirms the mic is free
        // (or the short timeout elapses, which is logged).
        sessionJob = scope.launch {
            CrashLogger.stage(TAG, "mic acquire: begin")
            try {
                val acquired = WakeWordControl.acquireForSession(MIC_ACQUIRE_TIMEOUT_MS)
                CrashLogger.stage(TAG, "mic acquire: acquired=$acquired")
                if (!active || !currentCoroutineContext().isActive) return@launch
                beginEngine()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active) fail(startFailureMessage(e))
            }
        }
    }

    /** Engine selection — runs on Main, after the mic handshake. */
    private fun beginEngine() {
        val store = providerStore ?: return
        val s = settings ?: return
        val engine = parseEngine(s.voiceEngine)
        CrashLogger.stage(TAG, "engine chosen: $engine")
        val activeConfig = store.orderedConfigs().firstOrNull()
        AppLog.i(
            "Voice",
            "engine decision: $engine provider=${activeConfig?.id ?: "none"} " +
                "chatModel=${activeConfig?.chatModel?.ifBlank { "(none)" } ?: "(none)"} " +
                "liveModel=${s.liveModel}"
        )
        // v2.0.3: AUTO behaves as NATIVE — Live is the only engine.
        // A stored UNIVERSAL value never reaches here (parseEngine
        // migrates it); a non-Gemini provider fails in startLive's
        // connect and ends with the "Why?" diagnosis via fail().
        when (engine) {
            VoiceEngine.NATIVE, VoiceEngine.AUTO -> startLive()
        }
    }

    private fun parseEngine(raw: String): VoiceEngine {
        // v2.0.3 migration: a stored UNIVERSAL value becomes NATIVE.
        if (raw.equals("UNIVERSAL", ignoreCase = true)) return VoiceEngine.NATIVE
        return try {
            VoiceEngine.valueOf(raw.uppercase())
        } catch (e: IllegalArgumentException) {
            VoiceEngine.AUTO
        }
    }

    /**
     * Background session start for the wake-word service (F3): builds
     * ProviderStore / MemoryDb / settings from the APPLICATION context
     * only — no Activity is involved or retained. WakeWordService calls
     * exactly this after "Hey Jarvis" fires. A session that is already
     * active is left untouched. Stopping stays [stop] as before.
     */
    fun startFromWake(context: Context) {
        if (active) return
        // F4 hardening: the settings/store construction below runs
        // BEFORE start() is entered, so it needs its own guard — a
        // failure here must surface as the ERROR state via fail(),
        // never crash the wake-word service.
        try {
            val appCtx = context.applicationContext
            start(
                context = appCtx,
                settings = loadSettingsForWake(appCtx),
                providerStore = ProviderStore(appCtx),
                memoryDb = MemoryDb(appCtx)
            )
        } catch (e: Exception) {
            runCatching { fail(startFailureMessage(e)) }
        }
    }

    /** Raw message for [fail]; the UI maps it through FriendlyErrors. */
    private fun startFailureMessage(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: FriendlyErrors.map(null).headline

    /**
     * Reads JarvisSettings from the same SharedPreferences file
     * MainActivity owns ("jarvis_settings", same keys/defaults, same
     * F4 theme migration) so a wake-started session behaves exactly
     * like one started from the Voice screen.
     */
    private fun loadSettingsForWake(appContext: Context): JarvisSettings {
        val prefs = appContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        // F4 migration (mirrors MainActivity): a missing value defaults
        // to DARK, and any stored name that is not a current theme —
        // including the retired pre-F4 default — fails valueOf and
        // reads as DARK. SUNSET is a real theme again and parses.
        val rawTheme = prefs.getString("theme", AppTheme.DARK.name) ?: AppTheme.DARK.name
        val theme = try {
            AppTheme.valueOf(rawTheme)
        } catch (e: IllegalArgumentException) {
            AppTheme.DARK
        }
        return JarvisSettings(
            assistantName = prefs.getString("assistant_name", "Jarvis") ?: "Jarvis",
            callsign = prefs.getString("callsign", "Nishad") ?: "Nishad",
            theme = theme,
            customBgHex = prefs.getString("custom_bg", null),
            language = prefs.getString("language", "Auto") ?: "Auto",
            persona = prefs.getString("persona", "Jarvis Pro") ?: "Jarvis Pro",
            wakeAlwaysOn = prefs.getBoolean("wake_always_on", false),
            silentExecution = prefs.getBoolean("silent_execution", false),
            voiceName = prefs.getString("voice_name", "Kore") ?: "Kore",
            customVoiceName = prefs.getString("custom_voice_name", "") ?: "",
            liveModel = prefs.getString(
                "live_model", "gemini-2.5-flash-native-audio-preview-12-2025"
            ) ?: "gemini-2.5-flash-native-audio-preview-12-2025",
            voiceEngine = prefs.getString("voice_engine", "AUTO") ?: "AUTO",
            avatarId = prefs.getString("avatar_id", "droid") ?: "droid",
            biometricsEnrolled = prefs.getBoolean("biometrics_enrolled", false),
            biometricsSampleCount = prefs.getInt("biometrics_sample_count", 0),
            biometricsEnrolledDate = prefs.getString("biometrics_enrolled_date", "") ?: "",
            // v1.9.0: wake-started sessions honour the same new
            // settings as UI-started ones.
            customPersonaName = prefs.getString("custom_persona_name", "") ?: "",
            customPersonaPrompt = prefs.getString("custom_persona_prompt", "") ?: "",
            dndRespect = prefs.getBoolean("dnd_respect", true),
            pttMode = prefs.getBoolean("ptt_mode", false),
            wakeSensitivity = prefs.getString("wake_sensitivity", "NORMAL") ?: "NORMAL",
            // v2.0.0 (#78): wake-started sessions honour the persona
            // schedule + any manual override, like UI-started ones.
            personaScheduleEnabled = prefs.getBoolean("persona_schedule_enabled", false),
            personaDayPersona = prefs.getString("persona_day_persona", "Jarvis Pro")
                ?: "Jarvis Pro",
            personaNightPersona = prefs.getString("persona_night_persona", "Girlfriend Mode")
                ?: "Girlfriend Mode",
            personaDayStartHour = prefs.getInt("persona_day_start_hour", 9),
            personaDayEndHour = prefs.getInt("persona_day_end_hour", 18),
            personaOverride = prefs.getString("persona_override", "") ?: "",
            personaOverrideDay = prefs.getString("persona_override_day", "") ?: "",
            customWakePhrase = prefs.getString("custom_wake_phrase", "") ?: ""
        )
    }

    /**
     * A media-play tool just succeeded in this voice session (F3).
     * v2.0.3: Live listens continuously via server VAD, so there is
     * no recognizer auto-listen resume to block — the flag is kept
     * only so a mic tap still clears it (see setMicMuted).
     */
    private fun noteMediaPlayed() {
        if (mediaListenBlocked) return
        mediaListenBlocked = true
    }

    /** Rebuild the session with the same inputs (error-screen retry). */
    fun retry() {
        val ctx = appContext ?: return
        val s = settings ?: return
        val store = providerStore ?: return
        val db = memoryDb ?: return
        start(ctx, s, store, db)
    }

    fun stop() {
        AppLog.i("Voice", "session stop")
        stopInternal(resetUi = true)
        runCatching { WakeWordControl.setSessionActive(false) }
    }

    fun setMicMuted(muted: Boolean) {
        // A mic tap is the user's explicit "talk to me again" signal —
        // it lifts the media-play auto-listen block (F3).
        mediaListenBlocked = false
        _uiState.value = _uiState.value.copy(micMuted = muted)
        if (muted) _amplitude.value = 0f
        // Live only: the mic is the AudioCapture stream; muting stops
        // frames reaching the server (see startLive's onChunk guard).
    }

    /**
     * v1.8 (#8): "Stop reading" (Voice screen chip, shown while
     * SPEAKING). v2.0.3: queued Live model audio is flushed and the
     * rest of this turn's text is suppressed.
     */
    fun stopReading() {
        if (!active) return
        AppLog.i("Voice", "stop reading requested (mode=${_uiState.value.mode})")
        readingSuppressed = true
        pulseJob?.cancel()
        when (_uiState.value.mode) {
            VoiceMode.LIVE -> {
                player?.flush()
                if (_uiState.value.status == VoiceStatus.SPEAKING) {
                    setStatus(VoiceStatus.LISTENING)
                }
                _amplitude.value = 0f
            }
            VoiceMode.NONE -> Unit
        }
    }

    // ── v1.8 (#11) phone-call auto-pause / resume ────────────────────

    /**
     * Register the call-state watch for this session. Everything is
     * best-effort: READ_PHONE_STATE is checked (never requested) and
     * the whole registration is runCatching-wrapped — on devices or
     * versions where the permission is absent the feature silently
     * does not exist. API 31+ uses TelephonyCallback on the main
     * executor; older versions use the legacy PhoneStateListener.
     */
    private fun registerCallWatch() {
        unregisterCallWatch()
        val ctx = appContext ?: return
        runCatching {
            if (ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.d(
                    "Voice",
                    "call auto-resume unavailable — READ_PHONE_STATE not granted (skipping)"
                )
                return
            }
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                as? TelephonyManager ?: return
            callWatchManager = tm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object :
                    TelephonyCallback(),
                    TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChange(state)
                    }
                }
                tm.registerTelephonyCallback(
                    ContextCompat.getMainExecutor(ctx), callback
                )
                callWatch = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallStateChange(state)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                callWatch = listener
            }
            AppLog.i("Voice", "call auto-resume watch registered")
        }.onFailure { AppLog.w("Voice", "call watch registration failed: ${it.message}") }
    }

    /** Unregister the call watch and clear any pending resume. Idempotent. */
    private fun unregisterCallWatch() {
        val watch = callWatch
        callWatch = null
        val tm = callWatchManager
        callWatchManager = null
        callPaused = false
        callResumePending = false
        callPausedMode = VoiceMode.NONE
        if (watch == null || tm == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                watch is TelephonyCallback
            ) {
                tm.unregisterTelephonyCallback(watch)
            } else if (watch is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(watch, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    /**
     * Call-state transitions (always on the main thread/executor).
     * RINGING/OFFHOOK during an active session pauses it — Live is
     * torn down (its in-memory resumption handle survives for the
     * reconnect), Universal just stops listening/speaking. IDLE with
     * a pending resume restarts the SAME engine and says so in a
     * SYSTEM caption.
     */
    private fun handleCallStateChange(state: Int) {
        if (!active) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (callPaused) return
                callPaused = true
                callResumePending = true
                callPausedMode = _uiState.value.mode
                AppLog.i(
                    "Voice",
                    "phone call active (state=$state) — pausing session (mode=$callPausedMode)"
                )
                pulseJob?.cancel()
                _amplitude.value = 0f
                when (callPausedMode) {
                    VoiceMode.LIVE -> teardownLive()
                    VoiceMode.NONE -> Unit
                }
                setStatus(VoiceStatus.IDLE)
                _uiState.value = _uiState.value.copy(
                    sttHint = "Paused for your call — I'll pick up where we left off."
                )
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (!callResumePending) return
                callResumePending = false
                callPaused = false
                val mode = callPausedMode
                callPausedMode = VoiceMode.NONE
                AppLog.i("Voice", "call ended — resuming session (mode=$mode)")
                _uiState.value = _uiState.value.copy(sttHint = null)
                when (mode) {
                    VoiceMode.LIVE -> startLive(liveSessionHandle)
                    VoiceMode.NONE -> return
                }
                addCaption(
                    VoiceCaption(
                        fromUser = false,
                        text = "Resumed after your call.",
                        kind = CaptionKind.SYSTEM
                    )
                )
            }
        }
    }

    private fun stopInternal(resetUi: Boolean) {
        active = false
        sessionJob?.cancel()
        sessionJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = false
        // v1.8 (#11): the call watch lives only while a session does.
        unregisterCallWatch()
        // F6: a stopped session closes any open Live task window so
        // the HUD doesn't keep a phantom RUNNING item.
        closeLiveTaskWindow()
        synchronized(liveProcessedToolCalls) { liveProcessedToolCalls.clear() }
        mediaListenBlocked = false
        pulseJob?.cancel()
        pulseJob = null
        teardownLive()
        userTurn.setLength(0)
        assistantTurn.setLength(0)
        userCaptionIndex = -1
        assistantCaptionIndex = -1
        _amplitude.value = 0f
        if (resetUi) _uiState.value = VoiceUiState()
    }

    /** Stops only the Live pieces (used by stop and by failure paths). */
    private fun teardownLive() {
        // F6: tearing Live down (stop, or a failure)
        // closes the task window. Idempotent — see stopInternal.
        closeLiveTaskWindow()
        liveTimeWarningJob?.cancel()
        liveTimeWarningJob = null
        shareJob?.cancel()
        shareJob = null
        capture?.stop()
        capture = null
        client?.close()
        client = null
        player?.stop()
        player = null
    }

    private fun setStatus(status: VoiceStatus) {
        _uiState.value = _uiState.value.copy(status = status)
    }

    private fun fail(message: String) {
        CrashLogger.stage(TAG, "fail: $message")
        unregisterCallWatch()
        pulseJob?.cancel()
        teardownLive()
        active = false
        runCatching { WakeWordControl.setSessionActive(false) }
        _amplitude.value = 0f
        _uiState.value = _uiState.value.copy(
            status = VoiceStatus.ERROR,
            errorMessage = message,
            sttHint = null
        )
    }

    // ── LIVE path ──────────────────────────────────────────────────────

    /**
     * Starts the Native Live engine. [resumeHandle] is non-null ONLY
     * for the in-session goAway reconnect (F5.1) — every fresh start
     * passes null so the setup asks for a brand-new session.
     * [modelOverride] is set ONLY by the F8 (#2) model fallback chain
     * advancing to its next candidate.
     */
    private fun startLive(resumeHandle: String? = null, modelOverride: String? = null) {
        val store = providerStore ?: return
        val s = settings ?: return
        val db = memoryDb ?: return
        val gemini = store.loadConfig(ProviderPresets.GEMINI.id)
        val voice = s.customVoiceName.ifBlank { s.voiceName }
        liveFirstFrameLogged = false
        // F8 (#2): a FRESH start (not a reconnect, not a chain step)
        // rebuilds the candidate list and re-evaluates the #13
        // weak-network guard for this start.
        if (resumeHandle == null && modelOverride == null) {
            liveCandidates = buildLiveCandidates(s)
            liveCandidateIndex = 0
            liveWeakNetworkStart =
                lastLiveSetupMs > WEAK_SETUP_THRESHOLD_MS ||
                    consecutiveLiveNetworkFailures >= 2
            if (liveWeakNetworkStart) {
                AppLog.w(
                    "Live",
                    "weak-network guard: lastSetup=${lastLiveSetupMs}ms " +
                        "consecutiveNetFailures=$consecutiveLiveNetworkFailures — " +
                        "capping this Live start's setup wait at " +
                        "${WEAK_SETUP_CAP_MS / 1000}s (was 12s)"
                )
            }
        }
        val attemptModel = modelOverride
            ?: liveCandidates.getOrNull(liveCandidateIndex)
            ?: s.liveModel
        liveAttemptModel = attemptModel
        liveSetupDone = false
        liveConnectStartMs = SystemClock.elapsedRealtime()
        CrashLogger.stage(TAG, "live: connect begin model=$attemptModel")

        _uiState.value = VoiceUiState(
            status = VoiceStatus.CONNECTING,
            mode = VoiceMode.LIVE,
            voiceName = voice,
            liveModel = attemptModel,
            engineLabel = "LIVE • $voice",
            micMuted = _uiState.value.micMuted
        )

        // On goAway reconnects startLive runs again — release the old player.
        player?.stop()
        player = null
        val playback = AudioPlayer { amp ->
            if (_uiState.value.status == VoiceStatus.SPEAKING) _amplitude.value = amp
        }
        playback.muted = isSilent
        playback.start()
        player = playback
        CrashLogger.stage(TAG, "live: audio player started")

        sessionJob?.cancel()
        sessionJob = scope.launch {
            try {
                val facts = withContext(Dispatchers.IO) { db.allFacts(null, null) }
                if (!active || !currentCoroutineContext().isActive) return@launch
                val prompt = PersonaPrompts.build(s, facts, codeMode = false)
            // F5.1: NO persisted handle is read here anymore — the
            // only handle a connect may carry is the in-memory one
            // from THIS session's goAway reconnect (resumeHandle).
            val liveClient = GeminiLiveClient(
                apiKey = gemini.apiKey,
                model = attemptModel,
                voiceName = voice,
                systemPrompt = prompt,
                resumptionHandle = resumeHandle,
                toolDeclarations = ToolRegistry.liveFunctionDeclarations(),
                languageCode = UniversalVoice.liveLanguageCode(s.language),
                providerBaseUrl = gemini.baseUrl,
                // F8 (#13): weak-network starts get the 6 s setup cap.
                setupTimeoutMs = if (liveWeakNetworkStart) {
                    WEAK_SETUP_CAP_MS
                } else {
                    LIVE_SETUP_TIMEOUT_MS
                },
                // v1.8 (#4): the user's barge-in sensitivity drives
                // the server VAD's start-of-speech sensitivity.
                bargeInSensitivity = s.bargeInSensitivity,
                // v2.0.1: the app-run set of Gemini-rejected setup
                // paths (self-healing setup strips live in here).
                strippedSetupPaths = liveStrippedSetupPaths,
                listener = LiveListener()
            )
            client = liveClient
            liveClient.connect()
            CrashLogger.stage(TAG, "live: ws connect issued")
            // connect() can fail synchronously (blank key) and fail
            // the session — only forward frames if the session is
            // still on the Live engine.
                if (_uiState.value.mode == VoiceMode.LIVE) startShareForwarder()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active) fail(startFailureMessage(e))
            }
        }
    }

    /**
     * While a Live session runs and screen share is active, forward the
     * freshest shared frame to Gemini Live as video input, throttled to
     * ~1 FPS (the share service itself already publishes at 1 FPS; the
     * poll + sequence check keeps us at or below that rate).
     */
    private fun startShareForwarder() {
        shareJob?.cancel()
        shareJob = scope.launch {
            var lastSeq = 0L
            while (active) {
                if (ScreenShareState.active.value) {
                    val seq = ScreenShareState.frameSeq.value
                    if (seq != lastSeq) {
                        lastSeq = seq
                        ScreenShareState.latestJpeg.value?.let { jpeg ->
                            client?.sendVideoFrame(jpeg)
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    /**
     * v2.0.1: server wording for a rejected Live SETUP value —
     * "Invalid value at 'setup.<path>' …". Distinct from a session
     * rejection (F5.1) and from a model problem: the setup itself
     * carried a field/value this model revision refuses. The client
     * strips it and retries automatically (self-healing setup).
     */
    private fun isSetupRejection(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("invalid value at 'setup.") ||
            lower.contains("invalid value at \"setup.")
    }

    /** F5.1: server wording for a rejected Live session/resumption handle. */
    private fun isSessionRejection(message: String): Boolean {
        val lower = message.lowercase()
        if (!lower.contains("session")) return false
        return lower.contains("handle") ||
            lower.contains("not found") ||
            lower.contains("expired")
    }

    /**
     * F8 (#2): the ordered Live-model candidate list for one fresh
     * session start: [the working model remembered from earlier in
     * this app run, when it isn't the configured one] → the user's
     * configured model → other NATIVE_AUDIO models from the Gemini
     * discovery cache → known alternates. De-duplicated. The chain
     * itself never exceeds [MAX_LIVE_MODEL_ATTEMPTS] attempts.
     */
    private fun buildLiveCandidates(s: JarvisSettings): List<String> {
        val store = providerStore
        val discovered = if (store == null) {
            emptyList()
        } else {
            runCatching {
                store.cachedModelIds(ProviderPresets.GEMINI.id)
                    .filter {
                        ModelDiscovery.classify(it) == ModelDiscovery.Kind.NATIVE_AUDIO
                    }
            }.getOrDefault(emptyList())
        }
        val ordered = LinkedHashSet<String>()
        val remembered = rememberedWorkingLiveModel
        if (!remembered.isNullOrBlank() && remembered != s.liveModel) {
            // It connected earlier in THIS app run while the configured
            // model didn't — don't waste 12 s re-proving that.
            ordered += remembered
        }
        ordered += s.liveModel
        ordered += discovered
        ordered += KNOWN_LIVE_ALTERNATES
        return ordered.filter { it.isNotBlank() }
    }

    /**
     * F8 (#2): is this failure about the MODEL (unknown / unavailable
     * / unsupported model)? Only model-class failures advance the
     * fallback chain — key rejections and network failures must not
     * (trying another model fixes neither). Session rejections are
     * excluded: they carry their own F5.1 clean-retry.
     */
    private fun isModelClassFailure(message: String): Boolean {
        if (isSessionRejection(message)) return false
        if (FriendlyErrors.shortReason(message) == "model not found") return true
        val lower = message.lowercase()
        if (!lower.contains("model")) return false
        return lower.contains("not found") ||
            lower.contains("not_found") ||
            lower.contains("not available") ||
            lower.contains("not supported") ||
            lower.contains("invalid model") ||
            lower.contains("unknown model")
    }

    /** F8 (#13): network-class Live failures feed the weak-network guard. */
    private fun isNetworkClassFailure(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("unable to resolve") ||
            lower.contains("setup timeout") ||
            lower.contains("timed out") ||
            lower.contains("timeout") ||
            lower.contains("network") ||
            lower.contains("socket") ||
            lower.contains("failed to connect") ||
            lower.contains("connection reset") ||
            lower.contains("connection closed")
    }

    /**
     * F8 (#1): the Voice screen's "Why?" diagnosis — the last Live
     * failure explained via FriendlyErrors, the last ~8 "Live" AppLog
     * lines, and one concrete next step. Null when Live never failed
     * in this app run.
     */
    fun liveDiagnosis(): LiveDiagnosis? {
        val reason = lastLiveFailureReason ?: return null
        val mapped = FriendlyErrors.map(reason)
        val lines = AppLog.entries.value
            .filter { it.tag == "Live" }
            .takeLast(8)
            .map { AppLog.formatLine(it) }
        val nextStep = when {
            // v2.0.1: the server rejected a setup VALUE (invalid enum
            // or field), not the model/key/network. The client heals
            // this itself — strip + same-model retry, and the field
            // stays stripped for the rest of the app run.
            isSetupRejection(reason) ->
                "Nothing to fix by hand: Jarvis auto-adjusted its Live " +
                    "settings (it stopped sending the option this model " +
                    "rejected) and retries on its own. If you're reading " +
                    "this after a fallback, just tap Retry."
            isModelClassFailure(reason) -> {
                val next = liveCandidates.getOrNull(liveCandidateIndex + 1)
                    ?: KNOWN_LIVE_ALTERNATES.firstOrNull { it != liveAttemptModel }
                if (next != null) {
                    "Try Live model: $next (Settings → API & Connection → Live model)."
                } else {
                    "Pick a different Live model in Settings → API & Connection."
                }
            }
            FriendlyErrors.shortReason(reason) == "API key rejected" ->
                "Check the Gemini key is an AI Studio key " +
                    "(Settings → API & Connection) — gateway keys can't run native Live."
            isSessionRejection(reason) ->
                "Nothing to fix: Jarvis already retries once with a clean " +
                    "session. If it repeats, restart the app."
            isNetworkClassFailure(reason) ->
                "Check your connection, then tap Retry — Live is the only " +
                    "voice engine now, so there is no fallback mode."
            else ->
                "Tap Retry; if it keeps failing, the full trail is in " +
                    "Settings → App Logs (tag: Live)."
        }
        return LiveDiagnosis(
            headline = mapped.headline,
            rawDetail = mapped.rawDetail,
            logLines = lines,
            nextStep = nextStep,
            failedModel = liveAttemptModel
        )
    }

    /**
     * Native Live died (setup rejected, socket failure, dead close).
     * v2.0.3: after the clean-session retry and the model fallback
     * chain below, the session ENDS with the friendly error + the
     * Voice screen's "Why?" diagnosis. There is no Universal fallback
     * anymore — no system SpeechRecognizer/TTS mode is ever started.
     */
    private fun handleLiveFailure(message: String) {
        // F4.1: this is called from GeminiLiveClient callbacks, which
        // run on OkHttp WebSocket threads — the whole failure path is
        // marshalled to Main before touching session state.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch { handleLiveFailure(message) }
            return
        }
        CrashLogger.stage(TAG, "live failure: $message")
        // F5: the FULL reason goes to the log; the caption carries the
        // short FriendlyErrors cause so the user can see WHY Live died.
        AppLog.e("Live", "native live failed: $message")
        lastLiveFailureReason = message
        // F8 (#13): network-class failures feed the weak-network
        // guard (reset by the next successful setup).
        if (isNetworkClassFailure(message)) {
            consecutiveLiveNetworkFailures++
            AppLog.w(
                "Live",
                "network-class Live failure #$consecutiveLiveNetworkFailures this app run"
            )
        }
        if (!active || reconnecting) return
        // F5.1: a session/handle rejection means the server refused
        // the resumption handle — not the model, not the key. Retry
        // ONCE with a guaranteed-clean session (fresh client, no
        // handle) before failing the session.
        if (!liveCleanRetried && isSessionRejection(message)) {
            liveCleanRetried = true
            liveSessionHandle = null
            AppLog.w("Live", "session rejected — retrying with a clean session")
            teardownLive()
            pulseJob?.cancel()
            _amplitude.value = 0f
            setStatus(VoiceStatus.CONNECTING)
            // F8: stay on the SAME candidate model — a plain
            // startLive() would rebuild the chain from candidate 0.
            startLive(
                modelOverride = liveAttemptModel.ifBlank { null }
            )
            return
        }
        // F8 (#2): a MODEL-class failure during setup advances the
        // fallback chain — the next candidate model gets a fresh
        // client/session BEFORE Live is given up. The
        // F5.1 clean retry above applies per attempt (liveCleanRetried
        // is per session start, and a session rejection for THIS
        // attempt still retries clean once).
        if (!liveSetupDone && isModelClassFailure(message)) {
            val nextIndex = liveCandidateIndex + 1
            if (nextIndex < liveCandidates.size &&
                nextIndex < MAX_LIVE_MODEL_ATTEMPTS
            ) {
                val next = liveCandidates[nextIndex]
                AppLog.w(
                    "Live",
                    "model fallback: '$liveAttemptModel' failed " +
                        "(${FriendlyErrors.shortReason(message)}) — trying '$next' " +
                        "(attempt ${nextIndex + 1}/" +
                        "${minOf(liveCandidates.size, MAX_LIVE_MODEL_ATTEMPTS)})"
                )
                liveCandidateIndex = nextIndex
                // F8/F5.1: the clean-session retry applies PER ATTEMPT.
                liveCleanRetried = false
                teardownLive()
                pulseJob?.cancel()
                _amplitude.value = 0f
                setStatus(VoiceStatus.CONNECTING)
                startLive(modelOverride = next)
                return
            }
            AppLog.w(
                "Live",
                "model fallback chain exhausted after ${liveCandidateIndex + 1} attempt(s)"
            )
        }
        // v2.0.3: no fallback engine — the failure IS the outcome.
        // The friendly error + liveDiagnosis() ("Why?") carry the
        // full reason; the caption names it in plain language.
        addCaption(
            VoiceCaption(
                fromUser = false,
                text = "Live unavailable (${FriendlyErrors.shortReason(message)}). " +
                    "Tap Why? for the diagnosis, or Retry to reconnect.",
                kind = CaptionKind.SYSTEM
            )
        )
        fail(message)
    }

    /**
     * v2.0.3 Live spoken-answer helpers (replace the deleted Universal
     * STT host). Input-transcription chunks are incremental words, so
     * a strict whole-message parse is tried first and a leading
     * yes/no word (≤4 words, e.g. "yes, send it") second. Chunks that
     * are neither are CONSUMED (return true) but do not answer —
     * they are thinking-aloud, not the answer, and must not pollute
     * the user turn. Returns true when the chunk was consumed.
     */
    private fun answerLiveSendAsk(chunk: String): Boolean {
        val normalized = chunk.trim().lowercase()
            .trimEnd('.', '!', '?', ' ').trim()
        if (normalized.isBlank()) return true
        when (com.nshd.jarvis2.brain.tools.SendAsk.parseAnswer(normalized)) {
            com.nshd.jarvis2.brain.tools.SendAnswer.YES,
            com.nshd.jarvis2.brain.tools.SendAnswer.NO -> {
                com.nshd.jarvis2.brain.tools.SendAsk.answer(normalized)
                return true
            }
            com.nshd.jarvis2.brain.tools.SendAnswer.NON_ANSWER -> Unit
        }
        val words = normalized.split(Regex("\\s+"))
        if (words.size <= 4) {
            when (words.first()) {
                "yes", "yeah", "send", "confirm", "ok", "okay" -> {
                    com.nshd.jarvis2.brain.tools.SendAsk.answer(words.first())
                    return true
                }
                "no", "cancel", "stop", "don't", "dont" -> {
                    com.nshd.jarvis2.brain.tools.SendAsk.answer(words.first())
                    return true
                }
            }
        }
        return true
    }

    /** Same leading-word rule for a non-send ConfirmGate in Live. */
    private fun answerLiveGate(chunk: String, gateId: Long): Boolean {
        val normalized = chunk.trim().lowercase()
            .trimEnd('.', '!', '?', ' ').trim()
        if (normalized.isBlank()) return true
        val first = normalized.split(Regex("\\s+")).first()
        val approved = when (first) {
            "yes", "yeah", "send", "confirm", "ok", "okay" -> true
            "no", "cancel", "stop", "don't", "dont" -> false
            else -> return true // consumed, not an answer
        }
        // A gate's prompt is a single decision; only a short reply
        // (≤4 words) counts, so a long sentence that merely starts
        // with "yes" can never approve a payment/destructive action.
        if (normalized.split(Regex("\\s+")).size > 4) return true
        com.nshd.jarvis2.brain.tools.ConfirmGate.complete(gateId, approved)
        AppLog.i("Gate", "Live spoken reply ${if (approved) "approved" else "denied"} gate #$gateId")
        return true
    }

    private class LiveListener : GeminiLiveClient.Listener {
        override fun onSetupComplete() {
            if (!active) return
            CrashLogger.stage(TAG, "live: setupComplete received")
            reconnectAttempts = 0
            reconnecting = false
            // F8 (#13): record the setup duration + clear the
            // weak-network failure counter — this start was healthy.
            liveSetupDone = true
            // v2.0.0: Live session time warning — Gemini rotates
            // Live sessions at roughly 10 minutes (the goAway
            // reconnect handles it), but a user mid-conversation
            // deserves a heads-up BEFORE the rotation, not after a
            // surprise drop. Fires once per setup, ~9 minutes in.
            liveTimeWarningJob?.cancel()
            liveTimeWarningJob = scope.launch {
                delay(LIVE_TIME_WARNING_MS)
                if (active && _uiState.value.mode == VoiceMode.LIVE &&
                    liveSetupDone
                ) {
                    AppLog.i(
                        "Live",
                        "session time warning shown (~9 min into the session)"
                    )
                    addCaption(
                        VoiceCaption(
                            fromUser = false,
                            text = "This Live session has been running " +
                                "about 9 minutes — Google may rotate it " +
                                "soon. If it drops, I'll reconnect or " +
                                "reconnect automatically, or end with a diagnosis.",
                            kind = CaptionKind.SYSTEM
                        )
                    )
                }
            }
            if (liveConnectStartMs > 0) {
                lastLiveSetupMs = SystemClock.elapsedRealtime() - liveConnectStartMs
                AppLog.i(
                    "Live",
                    "setup took ${lastLiveSetupMs}ms (model=$liveAttemptModel)"
                )
            }
            consecutiveLiveNetworkFailures = 0
            // F8 (#2): a fallback model connected — remember it for
            // this app run (never persisted over the user's setting)
            // and SAY SO once via a system caption.
            val configuredModel = settings?.liveModel
            if (liveAttemptModel.isNotBlank()) {
                rememberedWorkingLiveModel = liveAttemptModel
                if (configuredModel != null && liveAttemptModel != configuredModel) {
                    addCaption(
                        VoiceCaption(
                            fromUser = false,
                            text = "Live connected with $liveAttemptModel " +
                                "(your set model $configuredModel didn't respond)",
                            kind = CaptionKind.SYSTEM
                        )
                    )
                }
            }
            val captureDevice = AudioCapture(
                onChunk = { chunk ->
                    if (!_uiState.value.micMuted) client?.sendAudioChunk(chunk)
                },
                onAmplitude = { amp ->
                    // v1.8 (#3): while the player is actively
                    // outputting model audio, the OUTPUT level (via
                    // AudioPlayer.onAmplitude) owns the orb — the mic
                    // hears the loudspeaker and would swamp it.
                    if (!_uiState.value.micMuted && player?.outputActive != true) {
                        _amplitude.value = amp
                    }
                },
                onError = { message -> if (active) fail(message) }
            )
            capture = captureDevice
            CrashLogger.stage(TAG, "live: audio capture start")
            if (captureDevice.start()) {
                CrashLogger.stage(TAG, "live: capture running — LISTENING")
                setStatus(VoiceStatus.LISTENING)
            } else if (active) {
                // AudioCapture already reported via onError; if it returns
                // false silently, surface a generic mic failure.
                if (_uiState.value.status != VoiceStatus.ERROR) {
                    fail("Microphone could not be started")
                }
            }
        }

        override fun onUserTranscript(text: String) {
            if (!active) return
            // v2.0.3 Live send/gate answer intercept: a SendAsk or a
            // non-send ConfirmGate suspended inside a Live tool turn
            // can only be answered by SPOKEN yes/no in this session
            // (the old Universal STT host is gone, and the typed-chat
            // intercept never runs here). The chunk is the answer —
            // it must not also accumulate into the user turn, or the
            // next turn would carry a stale "yes". A non-answer chunk
            // lets SendAsk re-ask once, exactly like typed chat.
            if (com.nshd.jarvis2.brain.tools.SendAsk.isPending) {
                if (answerLiveSendAsk(text)) return
            } else {
                val pendingGate =
                    com.nshd.jarvis2.brain.tools.ConfirmGate.pending.value
                if (pendingGate != null && answerLiveGate(text, pendingGate.id)) return
            }
            synchronized(userTurn) { userTurn.append(text) }
            setTurnCaption(fromUser = true, text = userTurn.toString(), partial = true)
        }

        override fun onAssistantTranscript(text: String) {
            if (!active) return
            synchronized(assistantTurn) { assistantTurn.append(text) }
            setTurnCaption(fromUser = false, text = assistantTurn.toString(), partial = true)
            // v2.0.3: text arriving before the first audio frame is
            // THINKING; once audio is flowing the status is SPEAKING
            // (set by onAudioChunk) and must not flip back here.
            if (_uiState.value.status != VoiceStatus.SPEAKING) {
                setStatus(VoiceStatus.THINKING)
            }
        }

        override fun onAudioChunk(bytes: ByteArray) {
            if (!active) return
            if (!liveFirstFrameLogged) {
                liveFirstFrameLogged = true
                CrashLogger.stage(TAG, "live: first audio frame received")
            }
            // v2.0.3 RESTORE (reverts the v2.0.2 discard): Live audio
            // deltas are enqueued to the AudioPlayer again, so Live
            // voice sessions SPEAK. The setup JSON modality is
            // deliberately untouched (changing it risks another
            // setup-rejection class like the v2.0.1 sensitivity bug).
            // Silent Execution mutes at the player (enqueue drops).
            player?.enqueue(bytes)
            setStatus(VoiceStatus.SPEAKING)
        }

        override fun onInterrupted() {
            if (!active) return
            // Barge-in: stop speaking NOW and discard the partial reply.
            player?.flush()
            synchronized(assistantTurn) { assistantTurn.setLength(0) }
            removeTurnCaption(fromUser = false)
            assistantCaptionIndex = -1
            setStatus(VoiceStatus.LISTENING)
        }

        override fun onTurnComplete() {
            if (!active) return
            finalizeLiveTurn()
            if (isSilent) chime?.playSuccess()
            setStatus(VoiceStatus.LISTENING)
        }

        override fun onSessionHandle(handle: String) {
            // F5.1: in-memory only — used solely by the goAway
            // reconnect within THIS session's lifetime. Persisting it
            // was the bug: by the next app launch it is always stale.
            liveSessionHandle = handle
        }

        override fun onSessionEnding() {
            if (!active || reconnecting) return
            // goAway: the server is about to close — reconnect WITH
            // the in-memory handle from this same session so the
            // conversation survives the ~10-minute session limit
            // instead of losing context (F5.1: never a stored one).
            if (reconnectAttempts >= MAX_RECONNECTS) return
            reconnecting = true
            reconnectAttempts++
            capture?.stop()
            capture = null
            client?.close()
            client = null
            setStatus(VoiceStatus.CONNECTING)
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                try {
                    delay(600)
                    if (active && currentCoroutineContext().isActive) {
                        startLive(liveSessionHandle)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } finally {
                    reconnectJob = null
                }
            }
        }

        override fun onError(message: String) {
            if (!active) return
            AppLog.w("Live", "session error notice: $message")
            _uiState.value = _uiState.value.copy(sttHint = message)
        }

        override fun onFailure(message: String) {
            if (!active || reconnecting) return
            handleLiveFailure(message)
        }

        override fun onClosed(reason: String) {
            if (!active || reconnecting) return
            handleLiveFailure(reason)
        }

        override fun onToolCall(id: String, name: String, args: JSONObject) {
            if (!active) return
            val ctx = appContext ?: return
            val db = memoryDb ?: return
            // F3 dedupe: this Gemini toolCall id was already processed
            // in this session — answer with the cached response and
            // NEVER re-execute the phone action.
            synchronized(liveProcessedToolCalls) { liveProcessedToolCalls[id] }?.let { cached ->
                client?.sendToolResponse(id, name, cached)
                return
            }
            scope.launch(Dispatchers.IO) {
                // Re-check inside the coroutine: a racing duplicate may
                // have completed while this one was queued.
                synchronized(liveProcessedToolCalls) { liveProcessedToolCalls[id] }
                    ?.let { cached ->
                        if (active) client?.sendToolResponse(id, name, cached)
                        return@launch
                    }
                // F6: first REAL execution of this Live turn opens the
                // TaskQueue/TaskState window (cached duplicates above
                // don't). The window's TaskState job is THIS coroutine,
                // so an overlay abort cancels the in-flight tool work
                // without killing the voice session.
                val myJob = currentCoroutineContext()[Job] ?: Job()
                openLiveTaskWindow(myJob)
                TaskState.step("🔧 $name")
                // v2.0.3: a send tool's just-in-time ask must reach
                // this Live session as a plain caption (the typed-chat
                // pipeline sets askSink only for its own turns, and a
                // Live turn has no typed turn). Panel line is published
                // by SendAsk itself; this sink adds the caption.
                com.nshd.jarvis2.brain.tools.SendAsk.askSink = { line ->
                    addCaption(
                        VoiceCaption(
                            fromUser = false,
                            text = line,
                            kind = CaptionKind.ASSISTANT
                        )
                    )
                }
                val result: ToolResult = try {
                    // Per-turn ledger: identical repeat calls inside this
                    // Live turn return the cached result (F3).
                    ToolExecutor(ctx, db, liveLedger).execute(name, args)
                } catch (e: Exception) {
                    ToolResult(
                        false,
                        "Tool $name failed safely: ${e.message ?: "unexpected error"}"
                    )
                } finally {
                    // The ask sink lives only for this tool call's
                    // window — a later typed turn must never deliver
                    // an ask into a stale Live caption.
                    com.nshd.jarvis2.brain.tools.SendAsk.askSink = null
                }
                if (!result.success) liveTaskFailed = true
                TaskState.step(
                    (if (result.success) "✅ " else "❌ ") + name + ": " +
                        result.message.lineSequence().firstOrNull().orEmpty()
                )
                if (result.success && name in ToolRegistry.MEDIA_PLAY_TOOLS) {
                    noteMediaPlayed()
                }
                // F3 terminal rule (Live): the tool response carries a
                // system note so the model stops calling tools for this
                // request once media/open succeeded.
                val responseMessage = if (
                    result.success && name in ToolRegistry.TERMINAL_TOOLS
                ) {
                    result.message +
                        "\nTask complete. Do not call any more tools for this request."
                } else {
                    result.message
                }
                val response = JSONObject()
                    .put("success", result.success)
                    .put("message", responseMessage)
                synchronized(liveProcessedToolCalls) { liveProcessedToolCalls[id] = response }
                if (active) {
                    // Status chip only — the raw result goes back to the
                    // model, never into the caption text (F2).
                    val event = ToolEvent(
                        kind = if (result.success) ToolEventKind.SUCCESS else ToolEventKind.FAILURE,
                        toolName = name,
                        message = result.message
                    )
                    addCaption(
                        VoiceCaption(
                            fromUser = false,
                            text = event.chipText,
                            kind = CaptionKind.TOOL
                        )
                    )
                    client?.sendToolResponse(id, name, response)
                }
            }
        }
    }

    /**
     * F6: open the TaskQueue + TaskState window for a Live tool turn
     * (first executed tool call of the turn only; later calls in the
     * same turn reuse it). Goal = the user's transcribed words when
     * the Live client has already provided them, else a plain label.
     */
    private fun openLiveTaskWindow(job: Job) {
        if (liveTaskOpen) return
        liveTaskOpen = true
        liveTaskFailed = false
        liveTaskJob = job
        val goal = synchronized(userTurn) { userTurn.toString().trim() }
            .take(60)
            .ifBlank { "Voice task" }
        TaskQueue.beginTask(goal)
        TaskState.begin(goal = goal, job = job, maxSteps = 10)
        AppLog.i("Queue", "Live tool turn opened task window: '$goal'")
    }

    /** F6: close the Live task window (turn end / session teardown). Idempotent. */
    private fun closeLiveTaskWindow() {
        if (!liveTaskOpen) return
        liveTaskOpen = false
        liveTaskJob = null
        val failed = liveTaskFailed
        TaskQueue.finishRunning(!failed)
        if (TaskState.active.value) {
            TaskState.finish(!failed, if (failed) "Task failed" else "Task done")
        }
        AppLog.i("Queue", "Live task window closed (failed=$failed)")
    }

    private fun finalizeLiveTurn() {
        // F6: the Live turn ended — its tool task window closes with
        // the outcome the tools produced.
        closeLiveTaskWindow()
        // A new Live turn begins — fresh execution ledger (F3).
        liveLedger = ExecutionLedger()
        markTurnCaptionsFinal()
        val userText = synchronized(userTurn) { userTurn.toString().trim() }
        val assistantText = synchronized(assistantTurn) { assistantTurn.toString().trim() }
        synchronized(userTurn) { userTurn.setLength(0) }
        synchronized(assistantTurn) { assistantTurn.setLength(0) }
        userCaptionIndex = -1
        assistantCaptionIndex = -1
        if (userText.isBlank() && assistantText.isBlank()) return
        val db = memoryDb ?: return
        scope.launch(Dispatchers.IO) {
            if (userText.isNotBlank()) db.addMessage("user", userText)
            if (assistantText.isNotBlank()) db.addMessage("assistant", assistantText)
        }
    }

    private fun shortModelName(model: String): String {
        val short = model.substringAfterLast('/').trim()
        if (short.isBlank()) return "chat model"
        return if (short.length > 26) short.take(25) + "…" else short
    }

    // ── Caption bookkeeping ────────────────────────────────────────────

    private fun addCaption(caption: VoiceCaption) {
        _captions.value = _captions.value + caption
    }

    /**
     * Live turns update their caption in place while transcripts/replies
     * stream ([partial] = true); once a turn finalizes, the next caption
     * starts a fresh entry.
     */
    private fun setTurnCaption(
        fromUser: Boolean,
        text: String,
        partial: Boolean = false
    ) {
        if (text.isBlank()) return
        val kind = if (fromUser) CaptionKind.USER else CaptionKind.ASSISTANT
        val list = _captions.value.toMutableList()
        val index = if (fromUser) userCaptionIndex else assistantCaptionIndex
        if (index in list.indices && list[index].fromUser == fromUser) {
            list[index] = VoiceCaption(fromUser, text, kind, partial)
        } else {
            list.add(VoiceCaption(fromUser, text, kind, partial))
            if (fromUser) userCaptionIndex = list.lastIndex else assistantCaptionIndex = list.lastIndex
        }
        _captions.value = list
    }

    /** Streaming is over for the current turn — clear the dimmed style. */
    private fun markTurnCaptionsFinal() {
        val list = _captions.value.toMutableList()
        var changed = false
        for (index in listOf(userCaptionIndex, assistantCaptionIndex)) {
            if (index in list.indices && list[index].partial) {
                list[index] = list[index].copy(partial = false)
                changed = true
            }
        }
        if (changed) _captions.value = list
    }

    private fun removeTurnCaption(fromUser: Boolean) {
        val index = if (fromUser) userCaptionIndex else assistantCaptionIndex
        val list = _captions.value.toMutableList()
        if (index in list.indices && list[index].fromUser == fromUser) {
            list.removeAt(index)
            if (assistantCaptionIndex > index) assistantCaptionIndex--
            if (userCaptionIndex > index) userCaptionIndex--
            _captions.value = list
        }
    }

    /** F8 (#2): max Live model attempts per fresh session start. */
    private const val MAX_LIVE_MODEL_ATTEMPTS = 3

    /** F8 (#2): known native-audio alternates, tried after the cache. */
    private val KNOWN_LIVE_ALTERNATES = listOf(
        "gemini-2.5-flash-native-audio-preview-12-2025",
        "gemini-live-2.5-flash-preview",
        "gemini-2.0-flash-live-001"
    )

    /** F8 (#13): a setup slower than this marks the network weak. */
    private const val WEAK_SETUP_THRESHOLD_MS = 6_000L

    /** F8 (#13): setup-wait cap for starts under the weak-network guard. */
    private const val WEAK_SETUP_CAP_MS = 6_000L

    /** Default Live setup wait (mirrors GeminiLiveClient's default). */
    private const val LIVE_SETUP_TIMEOUT_MS = 12_000L

    private const val LIVE_PREFS = "jarvis_live_session"
    /** F5.1: retained ONLY so the legacy persisted handle can be removed. */
    private const val KEY_RESUMPTION_HANDLE = "resumption_handle"
    private const val SETTINGS_PREFS = "jarvis_settings"
    /** v1.9.0 (#6): last STT language that produced a clean final. */
    private const val KEY_STT_LAST_CLEAN = "stt_last_clean_language"
    private const val MAX_RECONNECTS = 3
    private const val TAG = "voice"
    private const val MIC_ACQUIRE_TIMEOUT_MS = 900L

    /** v2.0.0: warn ~1 minute before the typical Live rotation. */
    private const val LIVE_TIME_WARNING_MS = 9 * 60 * 1_000L
}

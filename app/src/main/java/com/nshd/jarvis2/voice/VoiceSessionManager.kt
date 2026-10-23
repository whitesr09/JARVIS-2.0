package com.nshd.jarvis2.voice

import android.content.Context
import android.os.Looper
import com.nshd.jarvis2.CrashLogger
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.ExecutionLedger
import com.nshd.jarvis2.brain.PersonaPrompts
import com.nshd.jarvis2.brain.ProviderPresets
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.brain.TaskQueue
import com.nshd.jarvis2.brain.ToolEvent
import com.nshd.jarvis2.brain.ToolEventKind
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.brain.tools.ToolRegistry
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
import kotlinx.coroutines.withContext

enum class VoiceMode { NONE, LIVE, UNIVERSAL }

enum class VoiceStatus {
    IDLE, CONNECTING, LISTENING, SPEAKING, THINKING, UNIVERSAL_ACTIVE, ERROR
}

/** Which engine the user's Voice Engine setting asks for (F2). */
enum class VoiceEngine { AUTO, NATIVE, UNIVERSAL }

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
    /** Mode-badge text: "LIVE • Kore" / "UNIVERSAL • <chat model>". */
    val engineLabel: String = "",
    val micMuted: Boolean = false,
    /**
     * F5 (was `notice`): transient recognizer hint ("Didn't catch
     * that"…). Cleared when a new listen cycle starts, when speech
     * begins, on a final result, and when a turn completes — it must
     * never linger under a later "speaking" state.
     */
    val sttHint: String? = null,
    /** F5: device-TTS voice actually in use (Universal mode only). */
    val ttsVoiceName: String = "",
    val errorMessage: String? = null
)

/**
 * App-level voice session owner. Two engines (F2):
 *
 *  - NATIVE LIVE (Gemini key + a native-audio Live model):
 *    GeminiLiveClient WebSocket + 16 kHz capture uplink + 24 kHz
 *    playback, server VAD turn-taking, barge-in flush, session
 *    resumption across the ~10 min server limit. If setup/connection
 *    fails, the session auto-switches to Universal in the same tap —
 *    the user never lands on a dead error screen while Universal runs.
 *  - UNIVERSAL (works with ANY provider/model): SpeechRecognizer with
 *    partial results → AgentPipeline (the Chat pipeline) with streamed
 *    deltas → sentence-buffered TTS, so the first audio starts at the
 *    first sentence boundary. Listen → think → speak → listen until
 *    the user ends the session.
 *
 * Engine choice: JarvisSettings.voiceEngine — AUTO picks Native Live
 * only when the active provider is Gemini, the configured Live model
 * looks native-audio capable, and a Gemini key is set; NATIVE forces
 * Live (with the Universal safety net); UNIVERSAL forces Universal.
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
    private var universal: UniversalVoice? = null
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

    /** Set once a failed Live attempt has fallen back to Universal. */
    @Volatile
    private var liveSwitchedToUniversal = false

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

    @Volatile
    private var universalFirstPartialLogged = false

    /**
     * F3 voice auto-listen guard: set when a media-play tool succeeds.
     * While set, UniversalVoice must NOT auto-restart the recognizer —
     * listening resumes only when the user taps the mic (setMicMuted)
     * or wakes the assistant again.
     */
    @Volatile
    private var mediaListenBlocked = false

    // Universal-turn bookkeeping: listening resumes only when BOTH the
    // pipeline has returned AND the TTS queue has drained.
    @Volatile
    private var pipelineRunning = false

    @Volatile
    private var pipelineDone = true

    @Volatile
    private var ttsSpeaking = false

    // ── F7 GateHost (c): spoken ConfirmGate confirmations ────────────
    // While a gate raised by THIS session's own voice turn is pending,
    // the recognizer listens for a yes/no reply instead of a command.
    // Gates raised elsewhere (typed chat) never arm this — the
    // floating overlay's gate card serves those.
    @Volatile
    private var gateArmed = false

    private var gateWatcherStarted = false

    // ── F7/I mis-script guard ─────────────────────────────────────────
    // A final STT result dominated by an Indic script while the
    // effective language is Latin (en-*/Hinglish) is a recognizer
    // language slip: re-listen ONCE with the alternate English tag.
    @Volatile
    private var guardRetryPending = false

    @Volatile
    private var guardOriginalText: String? = null

    @Volatile
    private var guardUsedThisTurn = false

    private var pulseJob: Job? = null

    /** Screen-share → Live video forwarder (Level 5), Live path only. */
    private var shareJob: Job? = null

    private val isSilent: Boolean get() = settings?.silentExecution == true

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
        liveSwitchedToUniversal = false
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
        // F7 audit fix: gate/guard state never leaks across sessions —
        // a stale gateArmed (or a pending mis-script retry) from a
        // dead session must not intercept the new session's first
        // utterance.
        gateArmed = false
        guardRetryPending = false
        guardOriginalText = null
        guardUsedThisTurn = false
        pipelineRunning = false
        pipelineDone = true
        ttsSpeaking = false
        _captions.value = emptyList()
        CrashLogger.stage(TAG, "session start (engine setting=${settings.voiceEngine})")
        AppLog.i(
            "Voice",
            "session start engine=${settings.voiceEngine} persona=${settings.persona} " +
                "language=${settings.language}"
        )
        startGateWatcherIfNeeded()
        // F4.1 mic arbitration handshake: the wake-word service must
        // have FULLY released the microphone (Vosk SpeechService stop
        // is asynchronous) before any engine touches it — two live mic
        // consumers racing is a classic OEM-stack crash. The engine
        // starts only after acquireForSession confirms the mic is free
        // (or the short timeout elapses, which is logged).
        scope.launch {
            CrashLogger.stage(TAG, "mic acquire: begin")
            val acquired = WakeWordControl.acquireForSession(MIC_ACQUIRE_TIMEOUT_MS)
            CrashLogger.stage(TAG, "mic acquire: acquired=$acquired")
            if (!active) return@launch
            beginEngine()
        }
    }

    /** Engine selection — runs on Main, after the mic handshake. */
    private fun beginEngine() {
        val store = providerStore ?: return
        val s = settings ?: return
        val gemini = store.loadConfig(ProviderPresets.GEMINI.id)
        val liveModelLooksNative = s.liveModel.contains("live", ignoreCase = true) ||
            s.liveModel.contains("native-audio", ignoreCase = true)
        val engine = parseEngine(s.voiceEngine)
        CrashLogger.stage(TAG, "engine chosen: $engine")
        val activeConfig = store.orderedConfigs().firstOrNull()
        AppLog.i(
            "Voice",
            "engine decision: $engine provider=${activeConfig?.id ?: "none"} " +
                "chatModel=${activeConfig?.chatModel?.ifBlank { "(none)" } ?: "(none)"} " +
                "liveModel=${s.liveModel}"
        )
        when (engine) {
            VoiceEngine.UNIVERSAL -> startUniversal()
            VoiceEngine.NATIVE -> startLive()
            VoiceEngine.AUTO -> {
                val nativeOk = store.activeProviderId() == ProviderPresets.GEMINI.id &&
                    gemini.apiKey.isNotBlank() && liveModelLooksNative
                if (nativeOk) startLive() else startUniversal()
            }
        }
    }

    private fun parseEngine(raw: String): VoiceEngine = try {
        VoiceEngine.valueOf(raw.uppercase())
    } catch (e: IllegalArgumentException) {
        VoiceEngine.AUTO
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
            ttsRate = prefs.getFloat(KEY_TTS_RATE, UniversalVoice.DEFAULT_TTS_RATE),
            ttsPitch = prefs.getFloat(KEY_TTS_PITCH, UniversalVoice.DEFAULT_TTS_PITCH)
        )
    }

    /**
     * A media-play tool just succeeded in this voice session (F3):
     * block Universal auto-listen resume until the user taps the mic
     * or wakes the assistant again, and say so once via a SYSTEM caption.
     */
    private fun noteMediaPlayed() {
        if (mediaListenBlocked) return
        mediaListenBlocked = true
        if (_uiState.value.mode == VoiceMode.UNIVERSAL) {
            addCaption(
                VoiceCaption(
                    fromUser = false,
                    text = "Music playing — tap the mic when you want me again.",
                    kind = CaptionKind.SYSTEM
                )
            )
        }
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
        val uv = universal
        if (uv != null) {
            // On the Universal path the recognizer IS the mic, and the
            // mic button doubles as TTS barge-in: muting stops both
            // listening and speaking.
            if (muted) {
                uv.stopListening()
                uv.stopSpeaking()
            } else if (active) {
                maybeResumeUniversalListening()
            }
        }
    }

    private fun stopInternal(resetUi: Boolean) {
        active = false
        // F6: a stopped session closes any open Live task window so
        // the HUD doesn't keep a phantom RUNNING item.
        closeLiveTaskWindow()
        synchronized(liveProcessedToolCalls) { liveProcessedToolCalls.clear() }
        mediaListenBlocked = false
        pulseJob?.cancel()
        pulseJob = null
        teardownLive()
        universal?.shutdown()
        universal = null
        pipelineRunning = false
        ttsSpeaking = false
        userTurn.setLength(0)
        assistantTurn.setLength(0)
        userCaptionIndex = -1
        assistantCaptionIndex = -1
        _amplitude.value = 0f
        if (resetUi) _uiState.value = VoiceUiState()
    }

    /** Stops only the Live pieces (used by stop and by the Universal switch). */
    private fun teardownLive() {
        // F6: tearing Live down (stop, or the switch to Universal)
        // closes the task window. Idempotent — see stopInternal.
        closeLiveTaskWindow()
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
        pulseJob?.cancel()
        teardownLive()
        universal?.shutdown()
        universal = null
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
     */
    private fun startLive(resumeHandle: String? = null) {
        val store = providerStore ?: return
        val s = settings ?: return
        val db = memoryDb ?: return
        val gemini = store.loadConfig(ProviderPresets.GEMINI.id)
        val voice = s.customVoiceName.ifBlank { s.voiceName }
        liveFirstFrameLogged = false
        CrashLogger.stage(TAG, "live: connect begin model=${s.liveModel}")

        _uiState.value = VoiceUiState(
            status = VoiceStatus.CONNECTING,
            mode = VoiceMode.LIVE,
            voiceName = voice,
            liveModel = s.liveModel,
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

        scope.launch {
            val facts = withContext(Dispatchers.IO) { db.allFacts(null, null) }
            if (!active) return@launch
            val prompt = PersonaPrompts.build(s, facts, codeMode = false)
            // F5.1: NO persisted handle is read here anymore — the
            // only handle a connect may carry is the in-memory one
            // from THIS session's goAway reconnect (resumeHandle).
            val liveClient = GeminiLiveClient(
                apiKey = gemini.apiKey,
                model = s.liveModel,
                voiceName = voice,
                systemPrompt = prompt,
                resumptionHandle = resumeHandle,
                toolDeclarations = ToolRegistry.liveFunctionDeclarations(),
                languageCode = UniversalVoice.liveLanguageCode(s.language),
                providerBaseUrl = gemini.baseUrl,
                listener = LiveListener()
            )
            client = liveClient
            liveClient.connect()
            CrashLogger.stage(TAG, "live: ws connect issued")
            // connect() can fail synchronously (blank key) and switch
            // the session to Universal — only forward frames if the
            // session is still on the Live engine.
            if (_uiState.value.mode == VoiceMode.LIVE) startShareForwarder()
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

    /** F5.1: server wording for a rejected Live session/resumption handle. */
    private fun isSessionRejection(message: String): Boolean {
        val lower = message.lowercase()
        if (!lower.contains("session")) return false
        return lower.contains("handle") ||
            lower.contains("not found") ||
            lower.contains("expired")
    }

    /**
     * Native Live died (setup rejected, socket failure, dead close).
     * First time: tear the Live pieces down and continue the SAME
     * session on the Universal engine with a system note — never a dead
     * error screen. Second failure (or Universal itself failing) is a
     * real error.
     */
    private fun handleLiveFailure(message: String) {
        // F4.1: this is called from GeminiLiveClient callbacks, which
        // run on OkHttp WebSocket threads. The Universal switch below
        // constructs SpeechRecognizer / TextToSpeech — main-thread-only
        // APIs whose off-main use is a prime "crashes while connecting"
        // suspect — so the whole failure path is marshalled to Main.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch { handleLiveFailure(message) }
            return
        }
        CrashLogger.stage(TAG, "live failure: $message")
        // F5: the FULL reason goes to the log; the caption carries the
        // short FriendlyErrors cause so the user can see WHY Live died.
        AppLog.e("Live", "native live failed: $message")
        lastLiveFailureReason = message
        if (!active || reconnecting) return
        // F5.1: a session/handle rejection means the server refused
        // the resumption handle — not the model, not the key. Retry
        // ONCE with a guaranteed-clean session (fresh client, no
        // handle) before any Universal fallback.
        if (!liveCleanRetried && isSessionRejection(message)) {
            liveCleanRetried = true
            liveSessionHandle = null
            AppLog.w("Live", "session rejected — retrying with a clean session")
            teardownLive()
            pulseJob?.cancel()
            _amplitude.value = 0f
            setStatus(VoiceStatus.CONNECTING)
            startLive()
            return
        }
        if (!liveSwitchedToUniversal && settings != null) {
            liveSwitchedToUniversal = true
            teardownLive()
            pulseJob?.cancel()
            _amplitude.value = 0f
            addCaption(
                VoiceCaption(
                    fromUser = false,
                    text = "Native Live unavailable " +
                        "(${FriendlyErrors.shortReason(message)}) — switched to Universal",
                    kind = CaptionKind.SYSTEM
                )
            )
            startUniversal()
        } else {
            fail(message)
        }
    }

    private class LiveListener : GeminiLiveClient.Listener {
        override fun onSetupComplete() {
            if (!active) return
            CrashLogger.stage(TAG, "live: setupComplete received")
            reconnectAttempts = 0
            reconnecting = false
            val captureDevice = AudioCapture(
                onChunk = { chunk ->
                    if (!_uiState.value.micMuted) client?.sendAudioChunk(chunk)
                },
                onAmplitude = { amp ->
                    if (!_uiState.value.micMuted) _amplitude.value = amp
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
            synchronized(userTurn) { userTurn.append(text) }
            setTurnCaption(fromUser = true, text = userTurn.toString(), partial = true)
        }

        override fun onAssistantTranscript(text: String) {
            if (!active) return
            synchronized(assistantTurn) { assistantTurn.append(text) }
            setTurnCaption(fromUser = false, text = assistantTurn.toString(), partial = true)
            setStatus(VoiceStatus.SPEAKING)
        }

        override fun onAudioChunk(bytes: ByteArray) {
            if (!active) return
            if (!liveFirstFrameLogged) {
                liveFirstFrameLogged = true
                CrashLogger.stage(TAG, "live: first audio frame received")
            }
            player?.enqueue(bytes)
            if (_uiState.value.status != VoiceStatus.SPEAKING) {
                setStatus(VoiceStatus.SPEAKING)
            }
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
            scope.launch {
                delay(600)
                if (active) startLive(liveSessionHandle)
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
                val result: ToolResult = try {
                    // Per-turn ledger: identical repeat calls inside this
                    // Live turn return the cached result (F3).
                    ToolExecutor(ctx, db, liveLedger).execute(name, args)
                } catch (e: Exception) {
                    ToolResult(
                        false,
                        "Tool $name failed safely: ${e.message ?: "unexpected error"}"
                    )
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

    // ── UNIVERSAL path ─────────────────────────────────────────────────

    private fun startUniversal() {
        // F4.1: SpeechRecognizer / TextToSpeech are main-thread-only.
        // However we got here (UI tap — fine; a Live failure marshalled
        // through handleLiveFailure — also fine; any future caller),
        // never construct the engine off Main.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch { startUniversal() }
            return
        }
        CrashLogger.stage(TAG, "universal: engine start")
        universalFirstPartialLogged = false
        val s = settings ?: return
        val store = providerStore ?: return
        teardownLive()
        val activeConfig = store.orderedConfigs().firstOrNull()
        val modelShort = shortModelName(
            activeConfig?.chatModel?.takeIf { it.isNotBlank() }
                ?: activeConfig?.reasoningModel
                ?: ""
        )
        _uiState.value = VoiceUiState(
            status = VoiceStatus.CONNECTING,
            mode = VoiceMode.UNIVERSAL,
            voiceName = modelShort,
            liveModel = "",
            engineLabel = "UNIVERSAL • $modelShort",
            micMuted = _uiState.value.micMuted
        )
        val ctx = appContext ?: return
        val uv = UniversalVoice(
            ctx, s.language, UniversalListener(),
            // F5.1: TTS tuning providers read the persisted slider
            // values LIVE on every reply, so speed/pitch changes in
            // Settings apply without restarting the session.
            ttsRate = {
                ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .getFloat(KEY_TTS_RATE, UniversalVoice.DEFAULT_TTS_RATE)
            },
            ttsPitch = {
                ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .getFloat(KEY_TTS_PITCH, UniversalVoice.DEFAULT_TTS_PITCH)
            }
        )
        universal = uv
        if (!uv.isRecognitionAvailable()) {
            // F5: when Native Live already failed this session, its
            // reason belongs in this error's Details too.
            val liveNote = lastLiveFailureReason?.let { reason ->
                "\n\nNative Live also failed: $reason"
            } ?: ""
            fail(
                "Speech recognition is not available on this device. " +
                    "Use text chat, or add a Gemini API key in Settings → " +
                    "API & Connection for Native Live voice." + liveNote
            )
            return
        }
        pipelineDone = true
        pipelineRunning = false
        setStatus(VoiceStatus.UNIVERSAL_ACTIVE)
        CrashLogger.stage(TAG, "universal: recognizer ready — listening")
        if (!_uiState.value.micMuted) uv.startListening()
    }

    private fun shortModelName(model: String): String {
        val short = model.substringAfterLast('/').trim()
        if (short.isBlank()) return "chat model"
        return if (short.length > 26) short.take(25) + "…" else short
    }

    // ── F7 GateHost (c) + F7/I mis-script guard helpers ──────────────

    /**
     * Watch ConfirmGate for the lifetime of the process (started once
     * from startSession). A gate that appears while THIS manager's
     * Universal pipeline turn is running almost certainly belongs to
     * that turn (a send waiting for its confirmation): arm the spoken
     * yes/no intercept and start listening for it.
     */
    private fun startGateWatcherIfNeeded() {
        if (gateWatcherStarted) return
        gateWatcherStarted = true
        scope.launch {
            com.nshd.jarvis2.brain.tools.ConfirmGate.pending.collect { pending ->
                if (pending == null) {
                    gateArmed = false
                } else if (active && _uiState.value.mode == VoiceMode.UNIVERSAL &&
                    pipelineRunning && !_uiState.value.micMuted
                ) {
                    gateArmed = true
                    AppLog.i(
                        "Gate",
                        "gate #${pending.id} pending during a voice turn — " +
                            "listening for a spoken yes/no"
                    )
                    universal?.startListening()
                }
            }
        }
    }

    private enum class GateReply { APPROVE, DENY, NONE }

    /** Spoken gate replies, normalized (lowercase, trailing punctuation stripped). */
    private fun classifyGateReply(text: String): GateReply {
        val normalized = text.trim().lowercase().trimEnd('.', '!', '?', ' ')
        return when (normalized) {
            "yes", "yeah", "send", "send it", "ok", "okay", "confirm" ->
                GateReply.APPROVE
            "no", "cancel", "stop" -> GateReply.DENY
            else -> GateReply.NONE
        }
    }

    /** Indic Unicode blocks checked by the mis-script guard. */
    private val INDIC_RANGES = listOf(
        0x0900..0x097F, // Devanagari
        0x0980..0x09FF, // Bengali
        0x0A00..0x0A7F, // Gurmukhi
        0x0A80..0x0AFF, // Gujarati
        0x0B80..0x0BFF, // Tamil
        0x0C00..0x0C7F, // Telugu
        0x0C80..0x0CFF, // Kannada
        0x0D00..0x0D7F  // Malayalam
    )

    /** Share of letters in [text] that fall in an Indic block (0 when no letters). */
    private fun indicLetterRatio(text: String): Float {
        var letters = 0
        var indic = 0
        for (ch in text) {
            if (ch.isLetter()) {
                letters++
                if (INDIC_RANGES.any { ch.code in it }) indic++
            }
        }
        return if (letters == 0) 0f else indic.toFloat() / letters
    }

    /**
     * True when [text] looks like a recognizer language slip: the
     * effective STT language expects Latin script (en-* or Hinglish)
     * but the final transcript is >40% Indic-script letters. Once per
     * command only — the retry's result is always accepted.
     */
    private fun misScriptSuspected(text: String): Boolean {
        if (guardUsedThisTurn) return false
        val s = settings ?: return false
        val tag = UniversalVoice.sttLanguageTag(s.language)
        val latinExpected = tag.startsWith("en") || s.language == "Hinglish"
        if (!latinExpected) return false
        return indicLetterRatio(text) > 0.40f
    }

    /** The other English tag for the guard's one re-listen (en-IN ↔ en-US). */
    private fun alternateEnglishTag(): String {
        val s = settings ?: return "en-US"
        return if (UniversalVoice.sttLanguageTag(s.language) == "en-IN") {
            "en-US"
        } else {
            "en-IN"
        }
    }

    /** A final STT result that passed every intercept — run the turn. */
    private fun dispatchUniversalFinal(text: String) {
        guardUsedThisTurn = false
        CrashLogger.stage(TAG, "universal: final result received")
        AppLog.d("STT", "final result (${text.length} chars): ${text.take(80)}")
        _uiState.value = _uiState.value.copy(sttHint = null)
        setTurnCaption(fromUser = true, text = text, partial = false)
        userCaptionIndex = -1
        setStatus(VoiceStatus.THINKING)
        routeUniversalTurn(text)
    }

    private class UniversalListener : UniversalVoice.Listener {
        override fun onListeningStarted() {
            if (!active) return
            // F5: a fresh listen cycle buries any stale recognizer hint.
            if (_uiState.value.sttHint != null) {
                _uiState.value = _uiState.value.copy(sttHint = null)
            }
        }

        override fun onBeginningOfSpeech() {
            if (!active) return
            if (_uiState.value.sttHint != null) {
                _uiState.value = _uiState.value.copy(sttHint = null)
            }
        }

        override fun onTtsVoiceSelected(voiceName: String) {
            if (!active) return
            _uiState.value = _uiState.value.copy(ttsVoiceName = voiceName)
        }

        override fun onPartialResult(text: String) {
            if (!active) return
            if (!universalFirstPartialLogged) {
                universalFirstPartialLogged = true
                CrashLogger.stage(TAG, "universal: first partial result")
            }
            if (_uiState.value.sttHint != null) {
                _uiState.value = _uiState.value.copy(sttHint = null)
            }
            setTurnCaption(fromUser = true, text = text, partial = true)
        }

        override fun onFinalResult(text: String) {
            if (!active) return
            // F7 GateHost (c): while a gate from THIS voice turn is
            // pending, the utterance is its yes/no reply — it must
            // never be routed as a new command.
            val pendingGate = com.nshd.jarvis2.brain.tools.ConfirmGate.pending.value
            if (gateArmed && pendingGate != null) {
                when (classifyGateReply(text)) {
                    GateReply.APPROVE -> {
                        gateArmed = false
                        AppLog.i("Gate", "spoken reply APPROVED gate #${pendingGate.id}")
                        addCaption(
                            VoiceCaption(
                                fromUser = false,
                                text = "You confirmed — approved.",
                                kind = CaptionKind.SYSTEM,
                                partial = false
                            )
                        )
                        com.nshd.jarvis2.brain.tools.ConfirmGate.complete(
                            pendingGate.id, approved = true
                        )
                    }
                    GateReply.DENY -> {
                        gateArmed = false
                        AppLog.i("Gate", "spoken reply DENIED gate #${pendingGate.id}")
                        addCaption(
                            VoiceCaption(
                                fromUser = false,
                                text = "You said no — cancelled.",
                                kind = CaptionKind.SYSTEM,
                                partial = false
                            )
                        )
                        com.nshd.jarvis2.brain.tools.ConfirmGate.complete(
                            pendingGate.id, approved = false
                        )
                    }
                    GateReply.NONE -> {
                        AppLog.d(
                            "Gate",
                            "utterance was not a yes/no — still waiting for confirmation"
                        )
                        universal?.startListening()
                    }
                }
                return
            }
            // F7/I: this final IS the mis-script guard's re-listen —
            // accept it in whatever script it landed (he may genuinely
            // be speaking that language) and dispatch it.
            if (guardRetryPending) {
                guardRetryPending = false
                guardOriginalText = null
                AppLog.i("STT", "mis-script retry accepted (${text.length} chars)")
                dispatchUniversalFinal(text)
                return
            }
            // F7/I mis-script guard: effective language expects Latin
            // script but the transcript is Indic-dominated → the
            // recognizer slipped language. Suppress dispatch, re-listen
            // ONCE with the alternate English tag; the retry's final
            // (handled above) replaces this one. A no-match on the
            // retry falls back to THIS text in onError — a command is
            // never silently dropped.
            if (misScriptSuspected(text)) {
                guardUsedThisTurn = true
                guardRetryPending = true
                guardOriginalText = text
                val altTag = alternateEnglishTag()
                AppLog.w(
                    "STT",
                    "mis-script suspected (>40% Indic letters under a Latin " +
                        "language) — re-listening once with $altTag"
                )
                _uiState.value = _uiState.value.copy(
                    sttHint = "Heard it in the wrong script — listening again…"
                )
                universal?.startListeningWithLanguage(altTag)
                return
            }
            // F7/F: dispatch is immediate — no TTS drain or handler
            // delay sits between the final result and the pipeline
            // (playing TTS is stopped by the normal barge-in paths).
            dispatchUniversalFinal(text)
        }

        override fun onRms(rmsDb: Float) {
            if (!active) return
            // Recognizer dB range is roughly -2 (quiet) .. +10 (loud).
            _amplitude.value = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onError(message: String, fatal: Boolean) {
            if (!active) return
            if (fatal) {
                fail(message)
                return
            }
            // F7/I: the mis-script guard's re-listen heard nothing —
            // dispatch the ORIGINAL transcript rather than losing the
            // command entirely.
            if (guardRetryPending &&
                (message == "Didn't catch that" || message == "Didn't hear anything")
            ) {
                guardRetryPending = false
                val original = guardOriginalText
                guardOriginalText = null
                if (!original.isNullOrBlank()) {
                    AppLog.w(
                        "STT",
                        "mis-script retry heard nothing — dispatching the original transcript"
                    )
                    dispatchUniversalFinal(original)
                    return
                }
            }
            AppLog.w("STT", "recognizer error: $message")
            _uiState.value = _uiState.value.copy(sttHint = message)
            // One-shot recognizer ended (silence / no match / transient
            // service error) — brief pause, then the loop listens
            // again. F7: the pause dropped 300 ms → 150 ms (the
            // recognizer instance is now reused, so restart is cheap).
            // F7 GateHost: while gateArmed, re-listening for the
            // confirmation reply is allowed even mid-pipeline-turn.
            scope.launch {
                delay(150)
                if (active && _uiState.value.mode == VoiceMode.UNIVERSAL &&
                    !_uiState.value.micMuted && !mediaListenBlocked &&
                    (!pipelineRunning || gateArmed)
                ) {
                    setStatus(VoiceStatus.UNIVERSAL_ACTIVE)
                    universal?.startListening()
                }
            }
        }

        override fun onTtsQueueDrained() {
            if (!active) return
            ttsSpeaking = false
            pulseJob?.cancel()
            _amplitude.value = 0f
            maybeResumeUniversalListening()
        }

        override fun onTtsUnavailable() {
            if (!active) return
            AppLog.w("TTS", "device TTS unavailable — replies are text-only")
            _uiState.value = _uiState.value.copy(
                sttHint = "Text-to-speech is unavailable on this device — replies are text-only."
            )
            // Behave like a finished speak so the loop continues.
            onTtsQueueDrained()
        }
    }

    /** F5: pipeline failure replies — friendly-mapped before display/speech. */
    private fun isFailureReply(reply: String): Boolean =
        reply.startsWith("I couldn't") ||
            reply.startsWith("Please configure") ||
            reply.startsWith("I hit a problem") ||
            reply.startsWith("Failed:")

    private fun routeUniversalTurn(userText: String) {
        val store = providerStore ?: return
        val s = settings ?: return
        val db = memoryDb ?: return
        val ctx = appContext ?: return
        AppLog.d("Agent", "user turn received (${userText.length} chars)")
        pipelineRunning = true
        pipelineDone = false
        ttsSpeaking = false
        val silent = isSilent
        val sentenceBuffer = StringBuilder()
        var sawDelta = false
        // F5.1: the FIRST spoken chunk of a reply flushes at a clause
        // boundary (see extractSentences) so speech starts sooner.
        var firstChunkFlushed = false
        scope.launch {
            // Universal voice uses exactly the same pipeline as text
            // chat: local memory commands, deterministic quick commands
            // (which work with no API key), then the tool agent loop —
            // now with streamed deltas for live captions + early TTS.
            val reply = try {
                AgentPipeline.handleUserText(
                    context = ctx,
                    text = userText,
                    settings = s,
                    memoryDb = db,
                    providerStore = store,
                    codeMode = false,
                    onEvent = { event ->
                        // F3: media started → the recognizer must not
                        // auto-restart over the music (see noteMediaPlayed).
                        if (event.kind == ToolEventKind.SUCCESS &&
                            event.toolName in ToolRegistry.MEDIA_PLAY_TOOLS
                        ) {
                            noteMediaPlayed()
                        }
                        addCaption(
                            VoiceCaption(
                                fromUser = false,
                                text = event.chipText,
                                kind = CaptionKind.TOOL
                            )
                        )
                    },
                    onAssistantDelta = { delta ->
                        if (!active) return@handleUserText
                        sawDelta = true
                        synchronized(assistantTurn) { assistantTurn.append(delta) }
                        setTurnCaption(
                            fromUser = false,
                            text = assistantTurn.toString(),
                            partial = true
                        )
                        if (!silent) {
                            // Deltas arrive on the streaming thread; the
                            // buffer is only touched under its lock.
                            val ready = synchronized(sentenceBuffer) {
                                extractSentences(
                                    sentenceBuffer, delta,
                                    allowClauseCut = !firstChunkFlushed
                                )
                            }
                            if (ready.isNotEmpty()) firstChunkFlushed = true
                            ready.forEach { sentence -> speakUniversalSentence(sentence) }
                        }
                    }
                )
            } catch (e: Exception) {
                // Raw exception text goes to the log ONLY (F5) — the
                // reply string is a clean failure marker.
                AppLog.e("Agent", "universal turn crashed: ${e.message ?: "unknown"}", e)
                "I couldn't reach the model — something went wrong handling that."
            }
            pipelineRunning = false
            pipelineDone = true
            if (!active) return@launch
            val failed = isFailureReply(reply)
            // F5 error hygiene: a failed turn's raw provider text never
            // becomes the caption, the chat history, or speech. The
            // caption/history get the FriendlyErrors headline; the raw
            // text already lives in AppLog under the API tag.
            val displayReply = if (failed) FriendlyErrors.map(reply).headline else reply
            withContext(Dispatchers.IO) {
                db.addMessage("user", userText)
                db.addMessage("assistant", displayReply)
            }
            // Finalize the assistant caption with the display reply.
            synchronized(assistantTurn) {
                assistantTurn.setLength(0)
                assistantTurn.append(displayReply)
            }
            setTurnCaption(fromUser = false, text = displayReply, partial = false)
            assistantCaptionIndex = -1
            synchronized(assistantTurn) { assistantTurn.setLength(0) }
            // Turn complete — no recognizer hint survives into the
            // next state (the stale "Didn't catch that" bug, F5).
            _uiState.value = _uiState.value.copy(sttHint = null)

            when {
                silent -> {
                    if (failed) chime?.playError() else chime?.playSuccess()
                    maybeResumeUniversalListening()
                }
                failed -> {
                    chime?.playError()
                    sentenceBuffer.setLength(0)
                    // Speak ONLY the short friendly line — never the
                    // raw failure text (F5). The drain callback (or the
                    // !ttsSpeaking check) resumes listening.
                    speakUniversalSentence(
                        "Sorry, I couldn't reach the model — check the connection."
                    )
                    if (!ttsSpeaking) maybeResumeUniversalListening()
                }
                else -> {
                    // Non-streamed replies (quick commands, memory
                    // commands) never produced deltas — run the full
                    // reply through the sentence extractor now so it is
                    // still spoken.
                    if (!sawDelta) {
                        extractSentences(sentenceBuffer, reply).forEach { sentence ->
                            speakUniversalSentence(sentence)
                        }
                    }
                    // Speak whatever partial sentence is left over, then
                    // let the TTS-drain callback resume listening. If no
                    // sentence was ever queued, resume right away.
                    val rest = sentenceBuffer.toString().trim()
                    sentenceBuffer.setLength(0)
                    if (rest.isNotBlank()) speakUniversalSentence(rest)
                    if (!ttsSpeaking) maybeResumeUniversalListening()
                }
            }
        }
    }

    private fun speakUniversalSentence(sentence: String) {
        if (!active) return
        if (!ttsSpeaking) {
            ttsSpeaking = true
            // F5: speaking buries any stale recognizer hint.
            _uiState.value = _uiState.value.copy(sttHint = null)
            setStatus(VoiceStatus.SPEAKING)
            startSpeakingPulse()
        }
        universal?.speakSentence(sentence)
    }

    /**
     * Listening resumes only when the pipeline has finished AND the
     * TTS queue has drained. Called from the drain callback and from
     * turn-end/unmute paths where nothing was (or is still) spoken;
     * while speech is in flight the drain callback owns the resume.
     */
    private fun maybeResumeUniversalListening() {
        if (!active) return
        if (_uiState.value.mode != VoiceMode.UNIVERSAL) return
        // F3: media is playing — never auto-restart the recognizer;
        // only a mic tap (setMicMuted) or a new wake clears the block.
        if (mediaListenBlocked) return
        if (_uiState.value.micMuted) return
        if (pipelineRunning || !pipelineDone) return
        if (ttsSpeaking) return // drain callback resumes when speech ends
        setStatus(VoiceStatus.UNIVERSAL_ACTIVE)
        universal?.startListening()
    }

    /**
     * Appends [delta] to [buffer] and cuts out every completed
     * sentence, leaving the trailing partial sentence in the buffer.
     * A sentence counts as complete at [.!?…] followed by whitespace;
     * an over-long fragment is cut at a word boundary so one giant
     * utterance never stalls the speech.
     *
     * F5.1: while [allowClauseCut] is set (the reply's first chunk
     * only) and no sentence end is buffered yet, the first clause
     * boundary — `,` `;` `:` followed by a space, or `—` — at a chunk
     * length of ≥ [FIRST_CLAUSE_MIN_CHARS] also cuts, so the user
     * hears speech start noticeably sooner. The trailing space check
     * keeps decimals ("1,000") and times ("12:30") unbroken; later
     * chunks always wait for full sentences.
     */
    private fun extractSentences(
        buffer: StringBuilder,
        delta: String,
        allowClauseCut: Boolean = false
    ): List<String> {
        buffer.append(delta)
        val sentences = mutableListOf<String>()
        while (true) {
            val text = buffer.toString()
            var cut = -1
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '.' || c == '!' || c == '?' || c == '…') {
                    if (i + 1 >= text.length) break // ender at buffer edge: wait for more
                    if (text[i + 1] == ' ' || text[i + 1] == '\n') {
                        cut = i + 1
                        break
                    }
                }
                i++
            }
            if (cut < 0 && allowClauseCut && sentences.isEmpty()) {
                var j = FIRST_CLAUSE_MIN_CHARS - 1
                while (j < text.length) {
                    val c = text[j]
                    val isClause = when (c) {
                        ',', ';', ':' ->
                            j + 1 < text.length &&
                                (text[j + 1] == ' ' || text[j + 1] == '\n')
                        '—' -> true
                        else -> false
                    }
                    if (isClause) {
                        cut = j + 1
                        break
                    }
                    j++
                }
            }
            if (cut < 0 && text.length > 240) {
                val space = text.lastIndexOf(' ', 240)
                if (space > 40) cut = space
            }
            if (cut < 0) break
            val sentence = text.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (sentence.isNotBlank()) sentences += sentence
        }
        return sentences
    }

    /** TTS has no real amplitude — synthesize a gentle pulse for the orb. */
    private fun startSpeakingPulse() {
        pulseJob?.cancel()
        pulseJob = scope.launch {
            var t = 0f
            while (true) {
                t += 0.35f
                _amplitude.value = 0.35f + 0.25f * kotlin.math.sin(t)
                delay(90)
            }
        }
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

    private const val LIVE_PREFS = "jarvis_live_session"
    /** F5.1: retained ONLY so the legacy persisted handle can be removed. */
    private const val KEY_RESUMPTION_HANDLE = "resumption_handle"
    private const val SETTINGS_PREFS = "jarvis_settings"
    private const val KEY_TTS_RATE = "tts_rate"
    private const val KEY_TTS_PITCH = "tts_pitch"
    /** F5.1: minimum first-chunk length before a clause cut is allowed. */
    private const val FIRST_CLAUSE_MIN_CHARS = 24
    private const val MAX_RECONNECTS = 3
    private const val TAG = "voice"
    private const val MIC_ACQUIRE_TIMEOUT_MS = 900L
}

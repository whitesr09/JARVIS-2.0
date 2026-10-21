package com.nshd.jarvis2.voice

import android.content.Context
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.ExecutionLedger
import com.nshd.jarvis2.brain.PersonaPrompts
import com.nshd.jarvis2.brain.ProviderPresets
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.brain.ToolEvent
import com.nshd.jarvis2.brain.ToolEventKind
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.brain.tools.ToolRegistry
import com.nshd.jarvis2.brain.tools.ToolResult
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.service.ScreenShareState
import com.nshd.jarvis2.ui.AppTheme
import com.nshd.jarvis2.service.WakeWordControl
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val notice: String? = null,
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

    // F3 Live tool-call dedupe: Gemini toolCall ids already processed in
    // this session, mapped to the exact response that was sent. A
    // duplicate id is answered with the cached response, never
    // re-executed.
    private val liveProcessedToolCalls = mutableMapOf<String, JSONObject>()

    // F3 per-Live-turn execution ledger (reset at every finalizeLiveTurn).
    @Volatile
    private var liveLedger = ExecutionLedger()

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

    private var pulseJob: Job? = null

    /** Screen-share → Live video forwarder (Level 5), Live path only. */
    private var shareJob: Job? = null

    private val isSilent: Boolean get() = settings?.silentExecution == true

    // ── Lifecycle ──────────────────────────────────────────────────────

    fun start(
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
        synchronized(liveProcessedToolCalls) { liveProcessedToolCalls.clear() }
        liveLedger = ExecutionLedger()
        mediaListenBlocked = false
        pipelineRunning = false
        pipelineDone = true
        ttsSpeaking = false
        _captions.value = emptyList()
        // Mic arbitration (Level 6): the wake-word service releases the
        // microphone while a voice session owns it, and resumes after.
        runCatching { WakeWordControl.setSessionActive(true) }

        val gemini = providerStore.loadConfig(ProviderPresets.GEMINI.id)
        val liveModelLooksNative = settings.liveModel.contains("live", ignoreCase = true) ||
            settings.liveModel.contains("native-audio", ignoreCase = true)
        when (parseEngine(settings.voiceEngine)) {
            VoiceEngine.UNIVERSAL -> startUniversal()
            VoiceEngine.NATIVE -> startLive()
            VoiceEngine.AUTO -> {
                val nativeOk = providerStore.activeProviderId() == ProviderPresets.GEMINI.id &&
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
        val appCtx = context.applicationContext
        start(
            context = appCtx,
            settings = loadSettingsForWake(appCtx),
            providerStore = ProviderStore(appCtx),
            memoryDb = MemoryDb(appCtx)
        )
    }

    /**
     * Reads JarvisSettings from the same SharedPreferences file
     * MainActivity owns ("jarvis_settings", same keys/defaults, same
     * retired-SUNSET → PURPLE migration) so a wake-started session
     * behaves exactly like one started from the Voice screen.
     */
    private fun loadSettingsForWake(appContext: Context): JarvisSettings {
        val prefs = appContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val rawTheme = prefs.getString("theme", AppTheme.PURPLE.name) ?: AppTheme.PURPLE.name
        val theme = if (rawTheme == "SUNSET") {
            AppTheme.PURPLE
        } else {
            try {
                AppTheme.valueOf(rawTheme)
            } catch (e: IllegalArgumentException) {
                AppTheme.PURPLE
            }
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
            biometricsEnrolledDate = prefs.getString("biometrics_enrolled_date", "") ?: ""
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
            notice = null
        )
    }

    // ── LIVE path ──────────────────────────────────────────────────────

    private fun startLive() {
        val store = providerStore ?: return
        val s = settings ?: return
        val db = memoryDb ?: return
        val gemini = store.loadConfig(ProviderPresets.GEMINI.id)
        val voice = s.customVoiceName.ifBlank { s.voiceName }

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

        scope.launch {
            val facts = withContext(Dispatchers.IO) { db.allFacts(null, null) }
            if (!active) return@launch
            val prompt = PersonaPrompts.build(s, facts, codeMode = false)
            val handle = appContext
                ?.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE)
                ?.getString(KEY_RESUMPTION_HANDLE, null)
            val liveClient = GeminiLiveClient(
                apiKey = gemini.apiKey,
                model = s.liveModel,
                voiceName = voice,
                systemPrompt = prompt,
                resumptionHandle = handle,
                toolDeclarations = ToolRegistry.liveFunctionDeclarations(),
                languageCode = UniversalVoice.liveLanguageCode(s.language),
                listener = LiveListener()
            )
            client = liveClient
            liveClient.connect()
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

    /**
     * Native Live died (setup rejected, socket failure, dead close).
     * First time: tear the Live pieces down and continue the SAME
     * session on the Universal engine with a system note — never a dead
     * error screen. Second failure (or Universal itself failing) is a
     * real error.
     */
    private fun handleLiveFailure(message: String) {
        if (!active || reconnecting) return
        if (!liveSwitchedToUniversal && settings != null) {
            liveSwitchedToUniversal = true
            teardownLive()
            pulseJob?.cancel()
            _amplitude.value = 0f
            addCaption(
                VoiceCaption(
                    fromUser = false,
                    text = "Native Live unavailable — switched to Universal (works with any model)",
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
            if (captureDevice.start()) {
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
            appContext?.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_RESUMPTION_HANDLE, handle)?.apply()
        }

        override fun onSessionEnding() {
            if (!active || reconnecting) return
            // goAway: the server is about to close — reconnect WITH the
            // stored handle so the conversation survives the ~10-minute
            // session limit instead of losing context.
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
                if (active) startLive()
            }
        }

        override fun onError(message: String) {
            if (!active) return
            _uiState.value = _uiState.value.copy(notice = message)
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

    private fun finalizeLiveTurn() {
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
        val uv = UniversalVoice(appContext ?: return, s.language, UniversalListener())
        universal = uv
        if (!uv.isRecognitionAvailable()) {
            fail(
                "Speech recognition is not available on this device. " +
                    "Use text chat, or add a Gemini API key in Settings → " +
                    "API & Connection for Native Live voice."
            )
            return
        }
        pipelineDone = true
        pipelineRunning = false
        setStatus(VoiceStatus.UNIVERSAL_ACTIVE)
        if (!_uiState.value.micMuted) uv.startListening()
    }

    private fun shortModelName(model: String): String {
        val short = model.substringAfterLast('/').trim()
        if (short.isBlank()) return "chat model"
        return if (short.length > 26) short.take(25) + "…" else short
    }

    private class UniversalListener : UniversalVoice.Listener {
        override fun onPartialResult(text: String) {
            if (!active) return
            setTurnCaption(fromUser = true, text = text, partial = true)
        }

        override fun onFinalResult(text: String) {
            if (!active) return
            setTurnCaption(fromUser = true, text = text, partial = false)
            userCaptionIndex = -1
            setStatus(VoiceStatus.THINKING)
            routeUniversalTurn(text)
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
            _uiState.value = _uiState.value.copy(notice = message)
            // One-shot recognizer ended (silence / no match / transient
            // service error) — brief pause, then the loop listens again.
            scope.launch {
                delay(300)
                if (active && _uiState.value.mode == VoiceMode.UNIVERSAL &&
                    !_uiState.value.micMuted && !pipelineRunning && !mediaListenBlocked
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
            _uiState.value = _uiState.value.copy(
                notice = "Text-to-speech is unavailable on this device — replies are text-only."
            )
            // Behave like a finished speak so the loop continues.
            onTtsQueueDrained()
        }
    }

    private fun routeUniversalTurn(userText: String) {
        val store = providerStore ?: return
        val s = settings ?: return
        val db = memoryDb ?: return
        val ctx = appContext ?: return
        pipelineRunning = true
        pipelineDone = false
        ttsSpeaking = false
        val silent = isSilent
        val sentenceBuffer = StringBuilder()
        var sawDelta = false
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
                                extractSentences(sentenceBuffer, delta)
                            }
                            ready.forEach { sentence -> speakUniversalSentence(sentence) }
                        }
                    }
                )
            } catch (e: Exception) {
                "I hit a problem handling that: ${e.message ?: "unexpected error"}"
            }
            pipelineRunning = false
            pipelineDone = true
            if (!active) return@launch
            withContext(Dispatchers.IO) {
                db.addMessage("user", userText)
                db.addMessage("assistant", reply)
            }
            // Finalize the assistant caption with the complete reply.
            synchronized(assistantTurn) {
                assistantTurn.setLength(0)
                assistantTurn.append(reply)
            }
            setTurnCaption(fromUser = false, text = reply, partial = false)
            assistantCaptionIndex = -1
            synchronized(assistantTurn) { assistantTurn.setLength(0) }

            val failed = reply.startsWith("I couldn't reach any provider") ||
                reply.startsWith("I hit a problem") ||
                reply.startsWith("Please configure your API Key")
            when {
                silent -> {
                    if (failed) chime?.playError() else chime?.playSuccess()
                    maybeResumeUniversalListening()
                }
                failed -> {
                    chime?.playError()
                    sentenceBuffer.setLength(0)
                    maybeResumeUniversalListening()
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
     */
    private fun extractSentences(buffer: StringBuilder, delta: String): List<String> {
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
    private const val KEY_RESUMPTION_HANDLE = "resumption_handle"
    private const val MAX_RECONNECTS = 3
}

package com.nshd.jarvis2.voice

import android.content.Context
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.PersonaPrompts
import com.nshd.jarvis2.brain.ProviderPresets
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.brain.tools.ToolRegistry
import com.nshd.jarvis2.brain.tools.ToolResult
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.service.ScreenShareState
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

enum class VoiceMode { NONE, LIVE, FALLBACK }

enum class VoiceStatus {
    IDLE, CONNECTING, LISTENING, SPEAKING, THINKING, FALLBACK_ACTIVE, ERROR
}

data class VoiceCaption(val fromUser: Boolean, val text: String)

data class VoiceUiState(
    val status: VoiceStatus = VoiceStatus.IDLE,
    val mode: VoiceMode = VoiceMode.NONE,
    val voiceName: String = "",
    val liveModel: String = "",
    val micMuted: Boolean = false,
    val notice: String? = null,
    val errorMessage: String? = null
)

/**
 * App-level voice session owner for Level 3. Two paths:
 *
 *  - LIVE (Gemini key configured): GeminiLiveClient WebSocket + 16 kHz
 *    capture uplink + 24 kHz playback, server VAD turn-taking, barge-in
 *    flush, session-resumption handles persisted across the ~10 min
 *    server limit (goAway → reconnect with handle).
 *  - FALLBACK (no Gemini key): SpeechRecognizer → AgentPipeline (the
 *    same local/quick-command/tool pipeline as Chat) → TextToSpeech,
 *    looped until the user ends the session.
 *
 * Finished turns on both paths are persisted into MemoryDb chat history,
 * so voice conversations show up in the Chat tab. In Silent Execution
 * mode nothing is spoken — a subtle chime marks each finished turn.
 *
 * All state is exposed as StateFlows; safe to touch from the OkHttp WS
 * thread, the recognizer callbacks and the UI alike.
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
    private var fallback: FallbackVoice? = null
    private var chime: ChimePlayer? = null

    // Live turn accumulation (transcripts arrive incrementally).
    private val userTurn = StringBuilder()
    private val assistantTurn = StringBuilder()
    private var userCaptionIndex = -1
    private var assistantCaptionIndex = -1

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var reconnecting = false

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
        _captions.value = emptyList()
        // Mic arbitration (Level 6): the wake-word service releases the
        // microphone while a voice session owns it, and resumes after.
        runCatching { WakeWordControl.setSessionActive(true) }

        val gemini = providerStore.loadConfig(ProviderPresets.GEMINI.id)
        if (gemini.apiKey.isNotBlank()) {
            startLive()
        } else {
            startFallback()
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
        _uiState.value = _uiState.value.copy(micMuted = muted)
        if (muted) _amplitude.value = 0f
        val fb = fallback
        if (fb != null) {
            // The recognizer IS the mic on the fallback path.
            if (muted) fb.stopListening() else if (active) fb.startListening()
        }
    }

    private fun stopInternal(resetUi: Boolean) {
        active = false
        pulseJob?.cancel()
        pulseJob = null
        shareJob?.cancel()
        shareJob = null
        capture?.stop()
        capture = null
        client?.close()
        client = null
        player?.stop()
        player = null
        fallback?.shutdown()
        fallback = null
        userTurn.setLength(0)
        assistantTurn.setLength(0)
        userCaptionIndex = -1
        assistantCaptionIndex = -1
        _amplitude.value = 0f
        if (resetUi) _uiState.value = VoiceUiState()
    }

    private fun setStatus(status: VoiceStatus) {
        _uiState.value = _uiState.value.copy(status = status)
    }

    private fun fail(message: String) {
        pulseJob?.cancel()
        shareJob?.cancel()
        shareJob = null
        capture?.stop()
        capture = null
        client?.close()
        client = null
        player?.stop()
        player = null
        fallback?.shutdown()
        fallback = null
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
                languageCode = FallbackVoice.liveLanguageCode(s.language),
                listener = LiveListener()
            )
            client = liveClient
            liveClient.connect()
            startShareForwarder()
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
            setTurnCaption(fromUser = true, text = userTurn.toString())
        }

        override fun onAssistantTranscript(text: String) {
            if (!active) return
            synchronized(assistantTurn) { assistantTurn.append(text) }
            setTurnCaption(fromUser = false, text = assistantTurn.toString())
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
            fail(message)
        }

        override fun onClosed(reason: String) {
            if (!active || reconnecting) return
            fail(reason)
        }

        override fun onToolCall(id: String, name: String, args: JSONObject) {
            if (!active) return
            val ctx = appContext ?: return
            val db = memoryDb ?: return
            scope.launch(Dispatchers.IO) {
                val result: ToolResult = try {
                    ToolExecutor(ctx, db).execute(name, args)
                } catch (e: Exception) {
                    ToolResult(
                        false,
                        "Tool $name failed safely: ${e.message ?: "unexpected error"}"
                    )
                }
                if (active) {
                    addCaption(
                        VoiceCaption(
                            fromUser = false,
                            text = "${if (result.success) "✅" else "❌"} $name: " +
                                result.message.lineSequence().firstOrNull().orEmpty()
                        )
                    )
                    val response = JSONObject()
                        .put("success", result.success)
                        .put("message", result.message)
                    client?.sendToolResponse(id, name, response)
                }
            }
        }
    }

    private fun finalizeLiveTurn() {
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

    // ── FALLBACK path ──────────────────────────────────────────────────

    private fun startFallback() {
        val s = settings ?: return
        _uiState.value = VoiceUiState(
            status = VoiceStatus.CONNECTING,
            mode = VoiceMode.FALLBACK,
            voiceName = "Device TTS",
            liveModel = "",
            micMuted = _uiState.value.micMuted
        )
        val fb = FallbackVoice(appContext ?: return, s.language, FallbackListener())
        fallback = fb
        if (!fb.isRecognitionAvailable()) {
            fail(
                "Speech recognition is not available on this device, and no " +
                    "Gemini API key is configured for Live voice. Add a Gemini " +
                    "key in Settings → API & Connection, or use text chat."
            )
            return
        }
        setStatus(VoiceStatus.FALLBACK_ACTIVE)
        fb.startListening()
    }

    private class FallbackListener : FallbackVoice.Listener {
        override fun onResult(text: String) {
            if (!active) return
            // Finalize the (possibly partial) live caption, then close the turn.
            setTurnCaption(fromUser = true, text = text)
            userCaptionIndex = -1
            setStatus(VoiceStatus.THINKING)
            routeFallbackTurn(text)
        }

        override fun onPartial(text: String) {
            if (!active) return
            setTurnCaption(fromUser = true, text = text)
        }

        override fun onRms(rmsDb: Float) {
            if (!active) return
            // Recognizer dB range is roughly -2 (quiet) .. +10 (loud).
            _amplitude.value = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onError(message: String, recoverable: Boolean) {
            if (!active) return
            if (recoverable) {
                _uiState.value = _uiState.value.copy(notice = message)
                // One-shot recognizer ended (silence / no match) — the
                // loop simply listens again after a short beat.
                scope.launch {
                    delay(450)
                    if (active && _uiState.value.mode == VoiceMode.FALLBACK &&
                        !_uiState.value.micMuted
                    ) {
                        setStatus(VoiceStatus.FALLBACK_ACTIVE)
                        fallback?.startListening()
                    }
                }
            } else {
                fail(message)
            }
        }

        override fun onSpeakDone() {
            if (!active) return
            pulseJob?.cancel()
            _amplitude.value = 0f
            if (_uiState.value.micMuted) return
            setStatus(VoiceStatus.FALLBACK_ACTIVE)
            fallback?.startListening()
        }

        override fun onTtsUnavailable() {
            if (!active) return
            _uiState.value = _uiState.value.copy(
                notice = "Text-to-speech is unavailable on this device — replies are text-only."
            )
            // Behave like a finished speak so the loop continues.
            onSpeakDone()
        }
    }

    private fun routeFallbackTurn(userText: String) {
        val store = providerStore ?: return
        val s = settings ?: return
        val db = memoryDb ?: return
        val ctx = appContext ?: return
        scope.launch {
            // Fallback voice now uses exactly the same pipeline as text
            // chat: local memory commands, deterministic quick commands
            // (which work with no API key), then the tool agent loop.
            val reply = try {
                AgentPipeline.handleUserText(
                    context = ctx,
                    text = userText,
                    settings = s,
                    memoryDb = db,
                    providerStore = store,
                    codeMode = false,
                    onEvent = { event ->
                        addCaption(VoiceCaption(fromUser = false, text = event))
                    }
                )
            } catch (e: Exception) {
                "I hit a problem handling that: ${e.message ?: "unexpected error"}"
            }
            if (!active) return@launch
            withContext(Dispatchers.IO) {
                db.addMessage("user", userText)
                db.addMessage("assistant", reply)
            }
            addCaption(VoiceCaption(fromUser = false, text = reply))
            if (reply.startsWith("I couldn't reach any provider") ||
                reply.startsWith("I hit a problem")
            ) {
                chime?.playError()
                resumeFallbackListening()
            } else {
                speakOrChime(reply)
            }
        }
    }

    private fun speakOrChime(reply: String) {
        if (!active) return
        if (isSilent) {
            chime?.playSuccess()
            resumeFallbackListening()
        } else {
            setStatus(VoiceStatus.SPEAKING)
            startSpeakingPulse()
            fallback?.speak(reply)
        }
    }

    private fun resumeFallbackListening() {
        if (!active) return
        if (_uiState.value.micMuted) return
        setStatus(VoiceStatus.FALLBACK_ACTIVE)
        fallback?.startListening()
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
     * Live turns update their caption in place while transcripts stream;
     * once a turn finalizes, the next caption starts a fresh entry.
     */
    private fun setTurnCaption(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        val list = _captions.value.toMutableList()
        val index = if (fromUser) userCaptionIndex else assistantCaptionIndex
        if (index in list.indices && list[index].fromUser == fromUser) {
            list[index] = VoiceCaption(fromUser, text)
        } else {
            list.add(VoiceCaption(fromUser, text))
            if (fromUser) userCaptionIndex = list.lastIndex else assistantCaptionIndex = list.lastIndex
        }
        _captions.value = list
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

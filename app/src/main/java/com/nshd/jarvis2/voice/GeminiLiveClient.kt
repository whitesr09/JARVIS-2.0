package com.nshd.jarvis2.voice

import android.util.Base64
import com.nshd.jarvis2.CrashLogger
import com.nshd.jarvis2.core.AppLog
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Gemini Live (BidiGenerateContent) WebSocket client:
 *
 *  - Audio does NOT flow until the server sends top-level
 *    "setupComplete" (an early serverContent also counts as implicit
 *    setup-complete).
 *  - Session resumption IS implemented, but IN-MEMORY ONLY (F5.1):
 *    a fresh connect sends an EMPTY "session_resumption" {} — asking
 *    for a brand-new resumable session — and never a stored handle.
 *    Handles from "sessionResumptionUpdate" live only for the current
 *    voice session (held by the session manager, handed back through
 *    the constructor) and are used solely for the goAway reconnect,
 *    so the conversation survives the ~10 min limit. Persisting
 *    handles across sessions was the F5.1 bug: handles live only
 *    minutes, so every fresh connect that carried one was rejected
 *    up front with "session expired / not found / invalid session
 *    handle" before the model was ever reached.
 *  - The model string is the user's configured id with exactly one
 *    "models/" prefix (F5 normalisation). No allow-list, no silent
 *    substitution — JARVIS-2.0's user-editable-model rule.
 *
 * Setup JSON uses snake_case keys. Level 4 adds the shared phone-tool
 * declarations and tool-response handling. The API key travels as the
 * URL query param (the only auth this API offers here) — the URL is
 * NEVER logged.
 *
 * All listener callbacks fire on the OkHttp WebSocket thread; consumers
 * must be thread-safe (StateFlow writes are).
 */
class GeminiLiveClient(
    private val apiKey: String,
    model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    /**
     * F5.1: in-session reconnect handle ONLY (goAway / drop within
     * this voice session's lifetime). MUST be null on a fresh connect.
     */
    private val resumptionHandle: String?,
    private val toolDeclarations: JSONArray? = null,
    /** BCP-47 code for speech_config.language_code; null = let Live decide. */
    languageCode: String? = null,
    /**
     * F5: the Gemini provider's configured REST base URL. Native Live
     * ALWAYS connects to Google's WebSocket endpoint — if this base URL
     * points at a third-party gateway, its key will not work here and
     * the failure reason must say so.
     */
    private val providerBaseUrl: String = "",
    /**
     * F8 (#13): how long to wait for setupComplete before failing.
     * The session manager lowers this to 6 s when a weak network is
     * suspected, so a doomed Live start fails fast (v2.0.3: no fallback)
     * instead of hanging the full 12 s.
     */
    private val setupTimeoutMs: Long = SETUP_TIMEOUT_MS,
    /**
     * v1.8 (#4): user barge-in sensitivity ("LOW" / "NORMAL" / "HIGH"
     * from JarvisSettings) — mapped in the setup JSON to Gemini's
     * automaticActivityDetection startOfSpeechSensitivity. Defaulted
     * so existing call sites keep compiling.
     */
    private val bargeInSensitivity: String = "NORMAL",
    /**
     * v2.0.1: setup paths ("realtime_input_config.…") Gemini has
     * rejected with "Invalid value at 'setup.…'" during THIS app run.
     * Shared (same instance) across every client the session manager
     * builds — model-chain attempts and goAway reconnects included —
     * so once a field is stripped it is never re-sent this run. The
     * client adds paths as its self-healing setup strips them.
     */
    private val strippedSetupPaths: MutableSet<String> = mutableSetOf(),
    private val listener: Listener
) {
    interface Listener {
        /** Server accepted the setup — microphone streaming may begin. */
        fun onSetupComplete()

        /** Incremental input transcription (accumulate per turn). */
        fun onUserTranscript(text: String)

        /** Incremental output transcription (accumulate per turn). */
        fun onAssistantTranscript(text: String)

        /** One decoded PCM chunk (24 kHz mono PCM16) from the model turn. */
        fun onAudioChunk(bytes: ByteArray)

        /** Server flagged an interruption — flush playback (barge-in). */
        fun onInterrupted()

        /** turnComplete or generationComplete seen on serverContent. */
        fun onTurnComplete()

        /**
         * A new session-resumption handle. F5.1: keep it IN MEMORY
         * for this session only — never persist it.
         */
        fun onSessionHandle(handle: String)

        /** Server sent goAway — reconnect soon, with the stored handle. */
        fun onSessionEnding()

        /** Non-fatal protocol error object from the server. */
        fun onError(message: String)

        /** Socket failure — the session is dead. */
        fun onFailure(message: String)

        /** Socket closed (not by us). */
        fun onClosed(reason: String)

        /** A model tool/function call to execute locally. */
        fun onToolCall(id: String, name: String, args: JSONObject)
    }

    // F5: exactly one "models/" prefix — strip any number of repeats.
    private val model: String = normalizeModelId(model)

    /** Host of the configured REST base URL (null = unset/unparseable). */
    private val providerHost: String? = hostOf(providerBaseUrl)

    /** True when the Gemini key belongs to a gateway, not Google AI Studio. */
    private val usesGateway: Boolean =
        providerHost != null && providerHost != WS_HOST

    /** Append the gateway explanation to a failure reason when relevant. */
    private fun withGatewayHint(message: String): String =
        if (usesGateway) {
            "$message (Note: your Gemini base URL points at a gateway — " +
                "$providerHost — but native Live always connects to Google " +
                "directly and needs a Google AI Studio key.)"
        } else {
            message
        }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var setupComplete = false

    @Volatile
    private var closedByUser = false

    /**
     * The language code actually sent in the setup. The native-audio
     * models reject some locales outright ("Unsupported language code
     * 'en-IN'…") — when that happens the client retries ONCE with the
     * code omitted (see [retryWithoutLanguage]) before giving up.
     */
    @Volatile
    private var currentLanguageCode: String? = languageCode

    @Volatile
    private var languageRetried = false

    /**
     * v2.0.1 self-healing setup: how many strip retries THIS client
     * (one model attempt) has used, and the first offending setup
     * path — later strip levels escalate relative to it (leaf →
     * parent object → whole realtime_input_config).
     */
    @Volatile
    private var setupStripRetries = 0

    @Volatile
    private var firstSetupOffender: String? = null

    /** Incremented per connect(); stale sockets' callbacks are ignored. */
    @Volatile
    private var connectionGeneration = 0

    // F5: hard setup timeout — a socket that opens but never answers
    // the setup (bad model, gateway key, silent server rejection) used
    // to hang "Connecting…" until the user gave up. 12 s, then the
    // session fails with reason "setup timeout" and falls back.
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "live-setup-timeout").apply { isDaemon = true }
    }

    @Volatile
    private var setupTimeoutFuture: ScheduledFuture<*>? = null

    @Volatile
    private var firstAudioLogged = false

    private fun armSetupTimeout(gen: Int) {
        cancelSetupTimeout()
        runCatching {
            setupTimeoutFuture = timeoutExecutor.schedule({
                if (gen == connectionGeneration && !setupComplete && !closedByUser) {
                    AppLog.e("Live", "setup timeout after ${setupTimeoutMs / 1000}s (model=$model)")
                    // Suppress the resulting onClosed — the failure
                    // below is the one reason the session reports.
                    closedByUser = true
                    runCatching { webSocket?.close(1000, "setup timeout") }
                    runCatching {
                        listener.onFailure(
                            withGatewayHint(
                                "Live setup timeout — the server never confirmed " +
                                    "the session setup within ${setupTimeoutMs / 1000} seconds."
                            )
                        )
                    }
                }
            }, setupTimeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelSetupTimeout() {
        runCatching { setupTimeoutFuture?.cancel(false) }
        setupTimeoutFuture = null
    }

    fun connect() {
        if (apiKey.isBlank()) {
            listener.onFailure("Gemini API key is missing. Please configure it in Settings.")
            return
        }
        closedByUser = false
        setupComplete = false
        firstAudioLogged = false
        val gen = ++connectionGeneration
        // Host only — the URL itself carries the key and is NEVER logged.
        AppLog.i(
            "Live",
            "WS connect host=$WS_HOST model=$model voice=$voiceName " +
                "language=${currentLanguageCode ?: "auto"} " +
                "providerHost=${providerHost ?: "default"}"
        )
        if (usesGateway) {
            AppLog.w(
                "Live",
                "Gemini base URL is a gateway ($providerHost); native Live " +
                    "uses the Google endpoint and needs a Google AI Studio key"
            )
        }
        // F5.1: makes the next log screenshot self-explanatory — a
        // fresh connect must show "no handle" on this line.
        AppLog.i(
            "Live",
            if (resumptionHandle.isNullOrBlank()) {
                "connect: fresh session (no handle)"
            } else {
                "connect: resuming in-session (handle suffix …" +
                    resumptionHandle.takeLast(2) + ")"
            }
        )
        val request = Request.Builder().url("$WS_BASE_URL?key=$apiKey").build()
        webSocket = httpClient.newWebSocket(request, SocketListener(gen))
        armSetupTimeout(gen)
    }

    /**
     * Reconnect with NO language_code in the setup. The old socket is
     * closed quietly — its callbacks carry a stale generation and are
     * dropped by [SocketListener].
     */
    private fun retryWithoutLanguage() {
        if (languageRetried || currentLanguageCode == null) return
        languageRetried = true
        currentLanguageCode = null
        AppLog.w("Live", "language rejected — retrying setup without language_code")
        val old = webSocket
        webSocket = null
        runCatching { old?.close(1000, "Retrying without language code") }
        connect()
    }

    private fun isLanguageError(text: String?): Boolean =
        !text.isNullOrBlank() && text.contains("language", ignoreCase = true)

    /** Stream one mic chunk (16 kHz mono PCM16). No-ops until setup completes. */
    fun sendAudioChunk(pcm: ByteArray) {
        val ws = webSocket ?: return
        if (!setupComplete || pcm.isEmpty()) return
        // F4.1: a send racing socket teardown must never throw into the
        // capture thread feeding it.
        runCatching {
            val payload = JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject().put(
                        "audio",
                        JSONObject()
                            .put("mimeType", "audio/pcm;rate=16000")
                            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    )
                )
            ws.send(payload.toString())
        }.onFailure { CrashLogger.logNonFatal("live", it) }
    }

    /**
     * Stream one screen-share frame (JPEG) as Live video input using
     * {"realtimeInput":{"video":{...}}}. Callers throttle to ~1 FPS.
     * No-ops until setup completes.
     */
    fun sendVideoFrame(jpeg: ByteArray) {
        val ws = webSocket ?: return
        if (!setupComplete || jpeg.isEmpty()) return
        runCatching {
            val payload = JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject().put(
                        "video",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    )
                )
            ws.send(payload.toString())
        }.onFailure { CrashLogger.logNonFatal("live", it) }
    }

    /** Return one locally executed tool result to the Live model. */
    fun sendToolResponse(id: String, name: String, result: JSONObject) {
        val ws = webSocket ?: return
        runCatching {
            val functionResponse = JSONObject()
                .put("id", id)
                .put("name", name)
                .put(
                    "response",
                    JSONObject().put("result", result.toString())
                )
            val payload = JSONObject().put(
                "tool_response",
                JSONObject().put(
                    "function_responses",
                    JSONArray().put(functionResponse)
                )
            )
            ws.send(payload.toString())
        }.onFailure { CrashLogger.logNonFatal("live", it) }
    }

    /** Deliberate teardown — no error/closed callbacks are surfaced after this. */
    fun close() {
        closedByUser = true
        cancelSetupTimeout()
        runCatching { timeoutExecutor.shutdown() }
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "Session ended by user")
    }

    // ── Setup ──────────────────────────────────────────────────────────

    private fun buildSetupJson(): String {
        val speechConfig = JSONObject().put(
            "voice_config",
            JSONObject().put(
                "prebuilt_voice_config",
                JSONObject().put("voice_name", voiceName)
            )
        )
        // Pin the spoken language when the user picked a concrete one —
        // unpinned Live sessions drift to US English and mishear Indian
        // names/accents. Accepted by the Live API; ignored harmlessly
        // by revisions that don't support it.
        if (!currentLanguageCode.isNullOrBlank()) {
            speechConfig.put("language_code", currentLanguageCode)
        }
        val generationConfig = JSONObject()
            .put("response_modalities", JSONArray().put("AUDIO"))
            .put("speech_config", speechConfig)
            .put("temperature", 0.6)
        // Native-audio 2.5 models default to a 2048-token thinking
        // budget that adds 2–3 s of speech delay — disable it. Only
        // sent for 2.5 models.
        if (model.contains("2.5")) {
            generationConfig.put("thinking_config", JSONObject().put("thinking_budget", 0))
        }
        val setup = JSONObject()
            .put("model", model)
            .put("generation_config", generationConfig)
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            // Transcription configs — they power our captions.
            .put("input_audio_transcription", JSONObject())
            .put("output_audio_transcription", JSONObject())
            // v1.8 (#4): server-side VAD sensitivity from the user's
            // barge-in setting — LOW makes the model harder to
            // interrupt (noisy rooms), HIGH interrupts on a whisper.
            .put(
                "realtime_input_config",
                JSONObject().put(
                    "automatic_activity_detection",
                    JSONObject()
                        .put("disabled", false)
                        .put(
                            "start_of_speech_sensitivity",
                            // v2.0.1: the Live API's StartSensitivity
                            // enum has ONLY _HIGH and _LOW — the old
                            // "START_SENSITIVITY_MED" (the NORMAL
                            // branch) is not a real value, and Gemini
                            // rejected the whole setup with close code
                            // 1007 ("Invalid value at 'setup.…start_
                            // of_speech_sensitivity'"). NORMAL maps to
                            // HIGH (the API's own default behaviour).
                            when (bargeInSensitivity.trim().uppercase()) {
                                "LOW" -> "START_SENSITIVITY_LOW"
                                else -> "START_SENSITIVITY_HIGH"
                            }
                        )
                )
            )
        if (toolDeclarations != null && toolDeclarations.length() > 0) {
            setup.put(
                "tools",
                JSONArray().put(
                    JSONObject().put("function_declarations", toolDeclarations)
                )
            )
        }
        // F5.1: session_resumption is ALWAYS present. The empty
        // object asks the server for a NEW resumable session (it
        // answers with a fresh handle via sessionResumptionUpdate).
        // A handle is included ONLY for an in-session reconnect —
        // a stale stored handle here makes Google reject the setup
        // before the model is reached ("BidiGenerateContent session
        // expired / not found", "Invalid session handle").
        val resumption = JSONObject()
        if (!resumptionHandle.isNullOrBlank()) {
            resumption.put("handle", resumptionHandle)
        }
        setup.put("session_resumption", resumption)
        // v2.0.1: never re-send a field Gemini already rejected this
        // app run (see trySetupStripRetry) — stripped paths are
        // removed from every subsequent setup, for every model.
        applySetupStrips(setup)
        return JSONObject().put("setup", setup).toString()
    }

    // ── v2.0.1: self-healing setup ───────────────────────────────────
    //
    // A strict Live model can reject an optional setup field the
    // previous model accepted, closing the socket (1007) with
    // "Invalid value at 'setup.<path>'" BEFORE setupComplete — the
    // session died at the door even though only one leaf of the
    // setup was at fault. On that exact rejection class the client
    // retries the SAME model with the offending config progressively
    // stripped: (1) the named leaf, (2) its parent object,
    // (3) realtime_input_config entirely. Core setup (model,
    // generation_config, system_instruction, tools,
    // session_resumption) is never stripped. These retries run
    // INSIDE one model attempt: the session manager only hears
    // failure after they are exhausted, so its F5.1 clean retry,
    // F8 model chain order is kept (v2.0.3: no Universal fallback).

    /** The setup path out of "Invalid value at 'setup.<path>'", if any. */
    private fun extractInvalidSetupPath(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = INVALID_SETUP_PATH_REGEX.find(text) ?: return null
        return match.groupValues[1].trimEnd('.').takeIf { it.isNotBlank() }
    }

    /**
     * On a setup rejection naming [text]'s invalid path: strip the
     * next level, remember the stripped path in [strippedSetupPaths]
     * (shared for the app run) and reconnect with the rebuilt setup.
     * Returns false when this isn't a strippable rejection or the
     * per-attempt strip budget is spent — normal failure handling
     * then proceeds untouched.
     */
    private fun trySetupStripRetry(text: String?): Boolean {
        if (setupComplete || closedByUser) return false
        val named = extractInvalidSetupPath(text) ?: return false
        if (setupStripRetries >= MAX_SETUP_STRIP_RETRIES) return false
        val offender = firstSetupOffender ?: named.also { firstSetupOffender = it }
        val segments = offender.split('.').filter { it.isNotBlank() }
        if (segments.isEmpty()) return false
        val stripPath = when (setupStripRetries) {
            0 -> segments.joinToString(".")
            1 -> if (segments.size >= 2) {
                segments.dropLast(1).joinToString(".")
            } else {
                segments[0]
            }
            else -> segments[0]
        }
        val added = synchronized(strippedSetupPaths) { strippedSetupPaths.add(stripPath) }
        setupStripRetries++
        AppLog.w(
            "Live",
            "setup-strip $stripPath (retry $setupStripRetries/" +
                "$MAX_SETUP_STRIP_RETRIES) — server rejected '$named'"
        )
        if (!added) {
            // Already stripped for an earlier attempt this run, yet
            // THIS connect still sent it — impossible via buildSetupJson;
            // log for diagnosis but still retry with the next level
            // queued for the reconnect's build.
            AppLog.w("Live", "setup-strip path '$stripPath' was already remembered")
        }
        // Quietly drop this socket (its listener generation goes stale
        // in connect()) and reconnect with the stripped setup.
        val old = webSocket
        webSocket = null
        runCatching { old?.close(1000, "Retrying with adjusted setup") }
        connect()
        return true
    }

    /** Removes every remembered stripped path from a built setup object. */
    private fun applySetupStrips(setup: JSONObject) {
        val paths = synchronized(strippedSetupPaths) { strippedSetupPaths.toList() }
        for (path in paths) {
            val segments = path.split('.').filter { it.isNotBlank() }
            if (segments.isEmpty()) continue
            var node: JSONObject = setup
            var reachable = true
            for (i in 0 until segments.size - 1) {
                val next = node.optJSONObject(segments[i])
                if (next == null) {
                    reachable = false
                    break
                }
                node = next
            }
            if (reachable) node.remove(segments.last())
        }
    }

    // ── Downlink parsing ───────────────────────────────────────────────

    private fun markSetupComplete() {
        if (!setupComplete) {
            setupComplete = true
            cancelSetupTimeout()
            AppLog.i("Live", "setupComplete")
            listener.onSetupComplete()
        }
    }

    /** Full server error text: message + code + status when present (F5). */
    private fun buildErrorMessage(error: JSONObject): String {
        val parts = mutableListOf<String>()
        error.optString("message").takeIf { it.isNotBlank() }?.let { parts += it }
        val code = error.optInt("code", 0)
        if (code != 0) parts += "code=$code"
        error.optString("status").takeIf { it.isNotBlank() }?.let { parts += "status=$it" }
        if (parts.isEmpty()) {
            // Last resort: a trimmed dump of the error object itself.
            parts += error.toString().take(200)
        }
        return parts.joinToString(" ")
    }

    private fun handleMessage(text: String) {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            // Malformed / partial frame: log + ignore, never throw out
            // of the socket listener (it runs on an OkHttp thread).
            CrashLogger.logNonFatal("live", "malformed frame ignored")
            return
        }
        try {
            handleParsedMessage(root)
        } catch (e: Exception) {
            CrashLogger.logNonFatal("live", e)
        }
    }

    private fun handleParsedMessage(root: JSONObject) {
        if (root.has("setupComplete")) {
            markSetupComplete()
            return
        }

        root.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (update.optBoolean("resumable") && handle.isNotBlank()) {
                AppLog.d("Live", "session resumption handle updated")
                listener.onSessionHandle(handle)
            }
        }

        if (root.has("goAway")) {
            AppLog.i("Live", "goAway received — server is ending the session")
            listener.onSessionEnding()
        }

        root.optJSONObject("error")?.let { error ->
            val message = buildErrorMessage(error)
            if (message.isNotBlank()) {
                // Unsupported-language setup rejection: retry once with
                // the language code omitted instead of surfacing a dead
                // session (F2). Only pre-setup — a mid-session language
                // error is reported normally.
                if (!setupComplete && isLanguageError(message) &&
                    currentLanguageCode != null && !languageRetried
                ) {
                    retryWithoutLanguage()
                    return
                }
                // v2.0.1: a rejected optional setup field is healed
                // in place (strip + same-model retry) before this is
                // allowed to become a session failure.
                if (!setupComplete && trySetupStripRetry(message)) {
                    return
                }
                if (!setupComplete) {
                    // F5: a server error INSTEAD of setupComplete is a
                    // connection failure with a real reason (bad model,
                    // rejected key…) — not a non-fatal session error.
                    // It used to land in onError (a transient notice),
                    // which is why every Live failure surfaced as a
                    // reason-less "unavailable".
                    AppLog.e(
                        "Live",
                        "server rejected setup (code=${error.optInt("code", 0)}, " +
                            "status=${error.optString("status").take(40)}); raw message omitted"
                    )
                    cancelSetupTimeout()
                    listener.onFailure(withGatewayHint("Live setup rejected: $message"))
                    return
                }
                listener.onError(message)
            }
        }

        dispatchToolCalls(root.optJSONObject("toolCall"))

        val content = root.optJSONObject("serverContent") ?: return

        // An early serverContent implies the setup was accepted.
        markSetupComplete()
        dispatchToolCalls(content.optJSONObject("toolCall"))

        if (content.optBoolean("interrupted")) {
            listener.onInterrupted()
        }

        content.optJSONObject("inputTranscription")?.let { transcription ->
            val textPart = transcription.optString("text")
            if (textPart.isNotBlank()) listener.onUserTranscript(textPart)
        }
        content.optJSONObject("outputTranscription")?.let { transcription ->
            val textPart = transcription.optString("text")
            if (textPart.isNotBlank()) listener.onAssistantTranscript(textPart)
        }

        content.optJSONObject("modelTurn")?.let { modelTurn ->
            val parts = modelTurn.optJSONArray("parts") ?: return@let
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val inline = part.optJSONObject("inlineData")
                    ?: part.optJSONObject("inline_data")
                    ?: continue
                val mime = inline.optString("mimeType", inline.optString("mime_type"))
                val data = inline.optString("data")
                if (data.isBlank() || !mime.startsWith("audio/pcm")) continue
                val bytes = try {
                    Base64.decode(data, Base64.DEFAULT)
                } catch (e: Exception) {
                    null
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    if (!firstAudioLogged) {
                        firstAudioLogged = true
                        AppLog.d("Live", "first audio frame (${bytes.size} bytes)")
                    }
                    listener.onAudioChunk(bytes)
                }
            }
        }

        if (content.optBoolean("turnComplete") || content.optBoolean("generationComplete")) {
            listener.onTurnComplete()
        }
    }

    private fun dispatchToolCalls(toolCall: JSONObject?) {
        val calls = toolCall?.optJSONArray("functionCalls")
            ?: toolCall?.optJSONArray("function_calls")
            ?: return
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function")
            val name = call.optString("name")
                .ifBlank { function?.optString("name").orEmpty() }
            if (name.isBlank()) continue
            val rawArgs = call.opt("args")
                ?: call.opt("arguments")
                ?: function?.opt("args")
                ?: function?.opt("arguments")
            val args = when (rawArgs) {
                is JSONObject -> rawArgs
                is String -> try {
                    JSONObject(rawArgs)
                } catch (e: Exception) {
                    JSONObject()
                }
                else -> JSONObject()
            }
            val id = call.optString("id")
                .ifBlank { function?.optString("id").orEmpty() }
                .ifBlank { "live_call_$i" }
            AppLog.d("Live", "tool call: $name")
            listener.onToolCall(id, name, args)
        }
    }

    // F4.1: EVERY callback body is wrapped in try/catch(Throwable).
    // These run on OkHttp dispatcher threads, where an escaping
    // exception becomes an uncaught process crash — and the callbacks
    // dispatch straight into the session manager (audio start, engine
    // switch, UI state). Faults are logged to the session log and, for
    // onOpen, still routed into the normal failure handling.
    private inner class SocketListener(private val gen: Int) : WebSocketListener() {
        private val stale: Boolean get() = gen != connectionGeneration

        override fun onOpen(webSocket: WebSocket, response: Response) {
            try {
                if (stale) return
                CrashLogger.stage("live", "ws open — sending setup")
                // The EXACT model string sent (post-normalisation).
                AppLog.i(
                    "Live",
                    "WS open — setup sent model=$model voice=$voiceName " +
                        "language=${currentLanguageCode ?: "auto"}"
                )
                webSocket.send(buildSetupJson())
            } catch (e: Exception) {
                CrashLogger.logNonFatal("live", e)
                if (!closedByUser && !stale) {
                    runCatching {
                        listener.onFailure("Live connection failed: ${e.message ?: "setup failed"}")
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                if (stale) return
                handleMessage(text)
            } catch (e: Exception) {
                CrashLogger.logNonFatal("live", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                if (stale) return
                // The Live API speaks JSON text frames; tolerate binary-wrapped JSON too.
                handleMessage(bytes.utf8())
            } catch (e: Exception) {
                CrashLogger.logNonFatal("live", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            CrashLogger.stage("live", "WebSocket failure (${t.javaClass.simpleName})")
            try {
                if (closedByUser || stale) return
                cancelSetupTimeout()
                // HTTP upgrade failures carry their status on the
                // response (401/403/404) — that IS the failure reason.
                val httpStatus = response?.let { "HTTP ${it.code}" }
                val detail = t.message ?: httpStatus ?: "unknown error"
                AppLog.e(
                    "Live",
                    "WebSocket failure (${t.javaClass.simpleName})" +
                        if (httpStatus != null) " [$httpStatus]" else ""
                )
                // Some revisions deliver the language rejection as a socket
                // failure instead of an error object — same one-time retry.
                if (!setupComplete && isLanguageError(detail) &&
                    currentLanguageCode != null && !languageRetried
                ) {
                    retryWithoutLanguage()
                    return
                }
                listener.onFailure(withGatewayHint("Live connection failed: $detail"))
            } catch (e: Exception) {
                CrashLogger.logNonFatal("live", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            try {
                webSocket.close(1000, null)
            } catch (e: Exception) {
                CrashLogger.logNonFatal("live", e)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            try {
                if (closedByUser || stale) return
                cancelSetupTimeout()
                AppLog.w(
                    "Live",
                    "WebSocket closed code=$code (reason present=${reason.isNotBlank()})"
                )
                if (!setupComplete && isLanguageError(reason) &&
                    currentLanguageCode != null && !languageRetried
                ) {
                    retryWithoutLanguage()
                    return
                }
                // v2.0.1: close 1007 = the server rejected the setup
                // VALUES (that's exactly how the invalid sensitivity
                // enum arrived). Heal by stripping + retrying the
                // same model before reporting a dead session.
                if (!setupComplete && code == 1007 && trySetupStripRetry(reason)) {
                    return
                }
                listener.onClosed(
                    withGatewayHint(reason.ifBlank { "Session closed (code $code)" })
                )
            } catch (e: Exception) {
                CrashLogger.logNonFatal("live", e)
            }
        }
    }

    companion object {
        private const val WS_HOST = "generativelanguage.googleapis.com"
        private const val WS_BASE_URL =
            "wss://$WS_HOST/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val SETUP_TIMEOUT_MS = 12_000L

        /** v2.0.1: per-model-attempt budget for self-healing setup strips. */
        private const val MAX_SETUP_STRIP_RETRIES = 3

        /** v2.0.1: "Invalid value at 'setup.<path>'" in server rejections. */
        private val INVALID_SETUP_PATH_REGEX = Regex(
            """Invalid value at ['"]setup\.([a-zA-Z0-9_.]+)['"]""",
            RegexOption.IGNORE_CASE
        )

        /** Exactly one "models/" prefix, however the user typed it (F5). */
        fun normalizeModelId(raw: String): String {
            var id = raw.trim()
            while (id.startsWith("models/")) {
                id = id.removePrefix("models/")
            }
            return "models/$id"
        }

        /** Host part of a base URL, or null when unset/unparseable. */
        fun hostOf(url: String): String? =
            try {
                java.net.URI(url.trim()).host
            } catch (e: Exception) {
                null
            }
    }
}

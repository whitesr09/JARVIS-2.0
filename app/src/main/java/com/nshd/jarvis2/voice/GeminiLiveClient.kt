package com.nshd.jarvis2.voice

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini Live (BidiGenerateContent) WebSocket client — built from the
 * Tech GPTx Part 4/7 pattern audit (techgptx-voice-audit.md), with their
 * gaps fixed:
 *
 *  - P7 behaviour, not P4: audio does NOT flow until the server sends
 *    top-level "setupComplete" (an early serverContent also counts as
 *    implicit setup-complete, per P7).
 *  - Session resumption IS implemented (their code lacked it entirely):
 *    the setup carries "session_resumption" when a handle is stored,
 *    "sessionResumptionUpdate" handles are persisted via [Listener],
 *    and "goAway" is surfaced so the session can reconnect with the
 *    handle instead of losing the conversation at the ~10 min limit.
 *  - The model string is passed through EXACTLY as configured (only a
 *    missing "models/" prefix is added). No allow-list, no silent
 *    substitution — JARVIS-2.0's user-editable-model rule.
 *
 * Setup JSON uses snake_case keys like the shipping P4/P7 code. Level 4
 * adds the shared phone-tool declarations and tool-response handling. The
 * API key travels as the URL query param (the only auth this API offers
 * here) — the URL is NEVER logged.
 *
 * All listener callbacks fire on the OkHttp WebSocket thread; consumers
 * must be thread-safe (StateFlow writes are).
 */
class GeminiLiveClient(
    private val apiKey: String,
    model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val resumptionHandle: String?,
    private val toolDeclarations: JSONArray? = null,
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

        /** A new session-resumption handle to persist. */
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

    private val model: String = if (model.startsWith("models/")) model else "models/$model"

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

    fun connect() {
        if (apiKey.isBlank()) {
            listener.onFailure("Gemini API key is missing. Please configure it in Settings.")
            return
        }
        closedByUser = false
        setupComplete = false
        val request = Request.Builder().url("$WS_BASE_URL?key=$apiKey").build()
        webSocket = httpClient.newWebSocket(request, SocketListener())
    }

    /** Stream one mic chunk (16 kHz mono PCM16). No-ops until setup completes. */
    fun sendAudioChunk(pcm: ByteArray) {
        val ws = webSocket ?: return
        if (!setupComplete || pcm.isEmpty()) return
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
    }

    /**
     * Stream one screen-share frame (JPEG) as Live video input — the
     * audit's Part 5/6 pattern uses {"realtimeInput":{"video":{...}}}.
     * Callers throttle to ~1 FPS. No-ops until setup completes.
     */
    fun sendVideoFrame(jpeg: ByteArray) {
        val ws = webSocket ?: return
        if (!setupComplete || jpeg.isEmpty()) return
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
    }

    /** Return one locally executed tool result to the Live model. */
    fun sendToolResponse(id: String, name: String, result: JSONObject) {
        val ws = webSocket ?: return
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
    }

    /** Deliberate teardown — no error/closed callbacks are surfaced after this. */
    fun close() {
        closedByUser = true
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "Session ended by user")
    }

    // ── Setup ──────────────────────────────────────────────────────────

    private fun buildSetupJson(): String {
        val generationConfig = JSONObject()
            .put("response_modalities", JSONArray().put("AUDIO"))
            .put(
                "speech_config",
                JSONObject().put(
                    "voice_config",
                    JSONObject().put(
                        "prebuilt_voice_config",
                        JSONObject().put("voice_name", voiceName)
                    )
                )
            )
            .put("temperature", 0.6)
        // P7 lesson: native-audio 2.5 models default to a 2048-token
        // thinking budget that adds 2–3 s of speech delay — disable it.
        // Only sent for 2.5 models, exactly like P7.
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
            // Transcription configs (P4 had them; P7 dropped them from the
            // setup but still parsed the results). They power our captions.
            .put("input_audio_transcription", JSONObject())
            .put("output_audio_transcription", JSONObject())
        if (toolDeclarations != null && toolDeclarations.length() > 0) {
            setup.put(
                "tools",
                JSONArray().put(
                    JSONObject().put("function_declarations", toolDeclarations)
                )
            )
        }
        if (!resumptionHandle.isNullOrBlank()) {
            setup.put("session_resumption", JSONObject().put("handle", resumptionHandle))
        }
        return JSONObject().put("setup", setup).toString()
    }

    // ── Downlink parsing ───────────────────────────────────────────────

    private fun markSetupComplete() {
        if (!setupComplete) {
            setupComplete = true
            listener.onSetupComplete()
        }
    }

    private fun handleMessage(text: String) {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }

        if (root.has("setupComplete")) {
            markSetupComplete()
            return
        }

        root.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (update.optBoolean("resumable") && handle.isNotBlank()) {
                listener.onSessionHandle(handle)
            }
        }

        if (root.has("goAway")) {
            listener.onSessionEnding()
        }

        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message")
            if (message.isNotBlank()) listener.onError(message)
        }

        dispatchToolCalls(root.optJSONObject("toolCall"))

        val content = root.optJSONObject("serverContent") ?: return

        // P7: an early serverContent implies the setup was accepted.
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
                if (bytes != null && bytes.isNotEmpty()) listener.onAudioChunk(bytes)
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
            listener.onToolCall(id, name, args)
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(buildSetupJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The Live API speaks JSON text frames; tolerate binary-wrapped JSON too.
            handleMessage(bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closedByUser) return
            val detail = t.message ?: response?.let { "HTTP ${it.code}" } ?: "unknown error"
            listener.onFailure("Live connection failed: $detail")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closedByUser) return
            listener.onClosed(reason.ifBlank { "Session closed (code $code)" })
        }
    }

    companion object {
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}

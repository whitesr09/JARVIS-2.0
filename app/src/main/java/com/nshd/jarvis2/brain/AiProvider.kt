package com.nshd.jarvis2.brain

import com.nshd.jarvis2.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One chat message in OpenAI-compatible shape, with optional tool-call data. */
data class ChatMsg(
    val role: String,
    val content: String,
    /** Required on role="tool" messages so the provider can pair a result. */
    val toolCallId: String? = null,
    /** Raw JSON array for an assistant turn that requested tool calls. */
    val toolCallsJson: String? = null,
    /**
     * Optional JPEG screenshot (Base64, no data-URL prefix). When set the
     * message serializes as OpenAI content parts:
     * [{type:text, text:content}, {type:image_url, image_url:{url:data:…}}].
     */
    val imageBase64: String? = null
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class ModelReply(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList()
)

data class TestResult(val ok: Boolean, val latencyMs: Long, val detail: String)

class AiException(message: String) : Exception(message)

interface AiProvider {
    val id: String
    val label: String

    /** @throws AiException on HTTP / network / parse failure. */
    suspend fun chat(messages: List<ChatMsg>, model: String): String

    /** Chat completion that may return tool calls when [tools] is supplied. */
    suspend fun chatWithTools(
        messages: List<ChatMsg>,
        model: String,
        tools: JSONArray?
    ): ModelReply

    /**
     * Streaming variant of [chatWithTools]: content deltas are delivered to
     * [onDelta] as they arrive; the returned [ModelReply] is the complete
     * accumulation (full content + any tool calls). The default
     * implementation degrades to a single-shot call and emits the full
     * content once, so non-streaming providers still work.
     *
     * Plan #20 (v2.0.0): [onToolCallReady] fires the moment a streamed
     * tool call's argument JSON first parses as complete — before the
     * stream's tail (final content chunks / [DONE]) has arrived — so
     * the agent loop can surface the decision (HUD event + timing)
     * immediately instead of waiting for the full response.
     */
    suspend fun chatStream(
        messages: List<ChatMsg>,
        model: String,
        tools: JSONArray?,
        onDelta: (String) -> Unit,
        onToolCallReady: (ToolCall) -> Unit = {}
    ): ModelReply {
        val reply = chatWithTools(messages, model, tools)
        reply.toolCalls.forEach(onToolCallReady)
        if (reply.content.isNotBlank()) onDelta(reply.content)
        return reply
    }

    suspend fun testConnection(): TestResult
}

/**
 * Any OpenAI-compatible chat-completions endpoint (Gemini's OpenAI surface,
 * Groq, FreeLLMAPI, OpenRouter, a custom gateway…). The API key is only ever
 * placed in the Authorization header of requests to [baseUrl] — never logged.
 */
class OpenAiCompatibleProvider(
    override val id: String,
    override val label: String,
    val baseUrl: String,
    val apiKey: String,
    /** Used only by the test-connection fallback when /models is unsupported. */
    val defaultModel: String = ""
) : AiProvider {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Streaming calls: no overall call timeout (long generations stream). */
    private val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    /** "host/path" for AppLog lines (F5) — never contains the API key. */
    private fun logTarget(path: String): String = try {
        val uri = java.net.URI(baseUrl.trimEnd('/'))
        val host = uri.host ?: "endpoint"
        val base = uri.path.orEmpty().trimEnd('/')
        "$host$base$path"
    } catch (e: Exception) {
        "endpoint$path"
    }

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")

    override suspend fun chat(messages: List<ChatMsg>, model: String): String {
        val reply = chatWithTools(messages, model, tools = null)
        if (reply.content.isBlank()) throw AiException("Empty reply from model")
        return reply.content
    }

    override suspend fun chatWithTools(
        messages: List<ChatMsg>,
        model: String,
        tools: JSONArray?
    ): ModelReply = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw AiException("No base URL configured")
        if (apiKey.isBlank()) throw AiException("No API key configured")
        if (model.isBlank()) throw AiException("No model configured")

        val payload = JSONObject()
            .put("model", model)
            .put("messages", serializeMessages(messages))
        if (tools != null && tools.length() > 0) {
            payload.put("tools", tools)
            payload.put("tool_choice", "auto")
        }

        val request = authed(Request.Builder().url(endpoint("/chat/completions")))
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val startedAt = System.currentTimeMillis()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            AppLog.e(
                "API",
                "$id model=$model POST ${logTarget("/chat/completions")} " +
                    "network error: ${e.message ?: "unreachable"}"
            )
            throw AiException("Network error: ${e.message ?: "unreachable"}")
        }
        response.use {
            val bodyText = readLimitedBody(it.body, MAX_RESPONSE_BYTES)
            val latencyMs = System.currentTimeMillis() - startedAt
            if (!it.isSuccessful) {
                AppLog.e(
                    "API",
                    "$id model=$model POST ${logTarget("/chat/completions")} " +
                        "→ HTTP ${it.code} in ${latencyMs}ms: " +
                        bodyText.replace('\n', ' ').take(300)
                )
                val brief = bodyText.replace('\n', ' ').take(160)
                throw AiException("HTTP ${it.code}${if (brief.isNotBlank()) ": $brief" else ""}")
            }
            AppLog.d(
                "API",
                "$id model=$model POST ${logTarget("/chat/completions")} " +
                    "→ ${it.code} in ${latencyMs}ms"
            )
            try {
                val message = JSONObject(bodyText)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                val content = message.optString("content")
                val calls = mutableListOf<ToolCall>()
                val toolCalls = message.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (i in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(i) ?: continue
                        val function = call.optJSONObject("function") ?: continue
                        val name = function.optString("name")
                        if (name.isBlank()) continue
                        val rawArguments = function.opt("arguments")
                        val arguments = when (rawArguments) {
                            is JSONObject -> rawArguments.toString()
                            is JSONArray -> rawArguments.toString()
                            null -> "{}"
                            else -> rawArguments.toString()
                        }
                        calls += ToolCall(
                            id = call.optString("id").ifBlank { "call_$i" },
                            name = name,
                            argumentsJson = arguments.ifBlank { "{}" }
                        )
                    }
                }
                if (content.isBlank() && calls.isEmpty()) {
                    throw AiException("Empty reply from model")
                }
                ModelReply(content = content, toolCalls = calls)
            } catch (e: AiException) {
                throw e
            } catch (e: Exception) {
                throw AiException("Unexpected response format")
            }
        }
    }

    /**
     * SSE streaming chat completion ("stream": true). Content deltas go to
     * [onDelta] live; tool-call fragments are accumulated per index and
     * returned as complete [ToolCall]s so the agent loop works unchanged.
     *
     * Failure rules (shared with the router):
     *  - HTTP 400 whose body names "stream" → the provider rejects
     *    streaming: retry ONCE non-streamed on this same provider and emit
     *    its content as a single delta.
     *  - Network death before ANY delta → [AiException], so the router may
     *    fail over to the next provider.
     *  - Network death AFTER deltas → return the partial text marked
     *    "(connection dropped)" and NO tool calls (the words are already on
     *    screen; replaying could double-execute a phone action).
     */
    override suspend fun chatStream(
        messages: List<ChatMsg>,
        model: String,
        tools: JSONArray?,
        onDelta: (String) -> Unit,
        onToolCallReady: (ToolCall) -> Unit
    ): ModelReply = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw AiException("No base URL configured")
        if (apiKey.isBlank()) throw AiException("No API key configured")
        if (model.isBlank()) throw AiException("No model configured")

        val payload = JSONObject()
            .put("model", model)
            .put("messages", serializeMessages(messages))
            .put("stream", true)
        if (tools != null && tools.length() > 0) {
            payload.put("tools", tools)
            payload.put("tool_choice", "auto")
        }
        val request = authed(Request.Builder().url(endpoint("/chat/completions")))
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val streamStartedAt = System.currentTimeMillis()
        AppLog.d(
            "API",
            "$id model=$model POST ${logTarget("/chat/completions")} stream start"
        )
        val response = try {
            streamClient.newCall(request).execute()
        } catch (e: IOException) {
            AppLog.e(
                "API",
                "$id model=$model stream network error: ${e.message ?: "unreachable"}"
            )
            throw AiException("Network error: ${e.message ?: "unreachable"}")
        }

        response.use {
            if (!it.isSuccessful) {
                val bodyText = readLimitedBody(it.body, MAX_RESPONSE_BYTES)
                if (it.code == 400 && bodyText.contains("stream", ignoreCase = true)) {
                    AppLog.i(
                        "API",
                        "$id model=$model stream rejected (HTTP 400) — retrying non-streamed"
                    )
                    val reply = chatWithTools(messages, model, tools)
                    reply.toolCalls.forEach(onToolCallReady)
                    if (reply.content.isNotBlank()) onDelta(reply.content)
                    return@withContext reply
                }
                AppLog.e(
                    "API",
                    "$id model=$model POST ${logTarget("/chat/completions")} " +
                        "→ HTTP ${it.code}: " + bodyText.replace('\n', ' ').take(300)
                )
                val brief = bodyText.replace('\n', ' ').take(160)
                throw AiException("HTTP ${it.code}${if (brief.isNotBlank()) ": $brief" else ""}")
            }
            val source = it.body?.source()
                ?: throw AiException("Empty streaming response")
            val content = StringBuilder()
            val toolBuilders = sortedMapOf<Int, ToolCallBuilder>()
            // Plan #20: indices whose argument JSON has already closed
            // and been announced via onToolCallReady.
            val readyAnnounced = mutableSetOf<Int>()
            var emittedAny = false
            var sawDone = false
            var streamedChars = 0L
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    streamedChars += line.length.toLong()
                    if (streamedChars > MAX_STREAM_CHARS) {
                        throw AiException("Streaming response exceeded the size limit")
                    }
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") {
                        sawDone = true
                        break
                    }
                    val chunk = try {
                        JSONObject(data)
                    } catch (e: Exception) {
                        continue
                    }
                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta") ?: continue
                    val piece = delta.optString("content")
                    if (piece.isNotEmpty()) {
                        content.append(piece)
                        emittedAny = true
                        onDelta(piece)
                    }
                    val calls = delta.optJSONArray("tool_calls") ?: continue
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val index = call.optInt("index", i)
                        val builder = toolBuilders.getOrPut(index) { ToolCallBuilder() }
                        call.optString("id")
                            .takeIf { id -> id.isNotBlank() }
                            ?.let { id -> builder.id = id }
                        val function = call.optJSONObject("function") ?: continue
                        function.optString("name")
                            .takeIf { n -> n.isNotBlank() }
                            ?.let { n -> builder.name.append(n) }
                        when (val argsPart = function.opt("arguments")) {
                            is String -> builder.arguments.append(argsPart)
                            null -> Unit
                            else -> builder.arguments.append(argsPart.toString())
                        }
                        // Plan #20: the moment this call's accumulated
                        // arguments parse as complete JSON, announce
                        // the decision — the stream tail (remaining
                        // content, [DONE]) no longer delays it.
                        if (index !in readyAnnounced &&
                            builder.name.isNotBlank() &&
                            isCompleteJson(builder.arguments.toString())
                        ) {
                            readyAnnounced += index
                            onToolCallReady(
                                ToolCall(
                                    id = builder.id.ifBlank { "call_$index" },
                                    name = builder.name.toString(),
                                    argumentsJson = builder.arguments.toString()
                                )
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                if (emittedAny) {
                    AppLog.w(
                        "API",
                        "$id model=$model stream dropped after deltas " +
                            "(${content.length} chars kept)"
                    )
                    val partial = content.toString().trimEnd() + "\n(connection dropped)"
                    return@withContext ModelReply(content = partial)
                }
                AppLog.e(
                    "API",
                    "$id model=$model stream died before any delta: " +
                        (e.message ?: "interrupted")
                )
                throw AiException("Network error: ${e.message ?: "stream interrupted"}")
            }
            if (!sawDone && !emittedAny && toolBuilders.isEmpty()) {
                throw AiException("Empty reply from model")
            }
            val calls = toolBuilders.entries.mapIndexedNotNull { position, (_, builder) ->
                val name = builder.name.toString()
                if (name.isBlank()) {
                    null
                } else {
                    ToolCall(
                        id = builder.id.ifBlank { "call_$position" },
                        name = name,
                        argumentsJson = builder.arguments.toString().ifBlank { "{}" }
                    )
                }
            }
            if (content.isBlank() && calls.isEmpty()) {
                throw AiException("Empty reply from model")
            }
            AppLog.d(
                "API",
                "$id model=$model stream done in " +
                    "${System.currentTimeMillis() - streamStartedAt}ms " +
                    "(${content.length} chars, ${calls.size} tool calls)"
            )
            ModelReply(content = content.toString(), toolCalls = calls)
        }
    }

    /**
     * Read a non-streaming response with a strict byte ceiling. Content-Length
     * alone is not sufficient because chunked responses may omit it.
     */
    private fun readLimitedBody(body: ResponseBody?, maxBytes: Int): String {
        if (body == null) return ""
        if (body.contentLength() > maxBytes) {
            throw AiException("Provider response exceeded the size limit")
        }
        val output = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val remaining = maxBytes + 1 - output.size()
                if (remaining <= 0) throw AiException("Provider response exceeded the size limit")
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > maxBytes) {
                    throw AiException("Provider response exceeded the size limit")
                }
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_STREAM_CHARS = 2_097_152L
    }

    /** Accumulates one streamed tool call (id + name + argument fragments). */
    private class ToolCallBuilder {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    /** Plan #20: does [text] parse as one complete JSON object/array? */
    private fun isCompleteJson(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        return try {
            when (trimmed.first()) {
                '{' -> JSONObject(trimmed)
                '[' -> JSONArray(trimmed)
                else -> return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun serializeMessages(messages: List<ChatMsg>): JSONArray {
        val array = JSONArray()
        messages.forEach { message ->
            val item = JSONObject().put("role", message.role)
            if (message.role == "tool" && !message.toolCallId.isNullOrBlank()) {
                item.put("tool_call_id", message.toolCallId)
            }
            if (!message.toolCallsJson.isNullOrBlank()) {
                item.put("tool_calls", JSONArray(message.toolCallsJson))
                item.put(
                    "content",
                    if (message.content.isBlank()) JSONObject.NULL else message.content
                )
            } else if (!message.imageBase64.isNullOrBlank()) {
                item.put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", message.content))
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put(
                                        "url",
                                        "data:image/jpeg;base64,${message.imageBase64}"
                                    )
                                )
                        )
                )
            } else {
                item.put("content", message.content)
            }
            array.put(item)
        }
        return array
    }

    /**
     * Connection Test v2 probe (F3): one tiny chat completion against
     * [model] — `ping`, max_tokens 1 — measured end-to-end. Success only
     * proves this exact model answers on this key/endpoint; the short
     * [TestResult.detail] on failure is the HTTP brief or network error,
     * already trimmed for display (never the raw body dump).
     */
    suspend fun probeChatModel(model: String): TestResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext TestResult(false, 0, "No base URL set")
        if (apiKey.isBlank()) return@withContext TestResult(false, 0, "No API key set")
        if (model.isBlank()) return@withContext TestResult(false, 0, "No model set")
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        try {
            val payload = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
                )
                .put("max_tokens", 1)
            val req = authed(Request.Builder().url(endpoint("/chat/completions")))
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    AppLog.d(
                        "API",
                        "$id probe model=$model OK in ${elapsed()}ms"
                    )
                    TestResult(true, elapsed(), "OK")
                } else {
                    val brief = resp.body?.string().orEmpty()
                        .replace('\n', ' ').trim().take(120)
                    AppLog.w(
                        "API",
                        "$id probe model=$model → HTTP ${resp.code}: $brief"
                    )
                    TestResult(
                        false, elapsed(),
                        "HTTP ${resp.code}" + if (brief.isNotBlank()) ": $brief" else ""
                    )
                }
            }
        } catch (e: IOException) {
            TestResult(false, elapsed(), "Network error: ${e.message ?: "unreachable"}")
        } catch (e: Exception) {
            TestResult(false, elapsed(), e.message?.take(120) ?: "Probe failed")
        }
    }

    override suspend fun testConnection(): TestResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext TestResult(false, 0, "No base URL set")
        if (apiKey.isBlank()) return@withContext TestResult(false, 0, "No API key set")
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000

        // Preferred probe: GET /models
        val modelsReq = authed(Request.Builder().url(endpoint("/models"))).get().build()
        val modelsResp = try {
            client.newCall(modelsReq).execute()
        } catch (e: IOException) {
            return@withContext TestResult(false, elapsed(), "Network error: ${e.message ?: "unreachable"}")
        }
        val modelsCode = modelsResp.code
        modelsResp.close()
        if (modelsCode == 200) {
            return@withContext TestResult(true, elapsed(), "Connected — model list reachable")
        }
        if (modelsCode == 401 || modelsCode == 403) {
            return@withContext TestResult(false, elapsed(), "HTTP $modelsCode — API key rejected")
        }
        // Some providers don't implement /models (404/405): fall back to a
        // 1-token chat completion against the configured chat model.
        if (modelsCode == 404 || modelsCode == 405) {
            if (defaultModel.isBlank()) {
                return@withContext TestResult(
                    false, elapsed(),
                    "HTTP $modelsCode on /models and no chat model set for the fallback test"
                )
            }
            return@withContext try {
                val payload = JSONObject()
                    .put("model", defaultModel)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
                    )
                    .put("max_tokens", 1)
                val req = authed(Request.Builder().url(endpoint("/chat/completions")))
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val brief = if (resp.isSuccessful) "" else
                        " — " + resp.body?.string().orEmpty().replace('\n', ' ').take(120)
                    if (resp.isSuccessful) {
                        TestResult(true, elapsed(), "Connected — test completion succeeded")
                    } else {
                        TestResult(false, elapsed(), "HTTP ${resp.code}$brief")
                    }
                }
            } catch (e: IOException) {
                TestResult(false, elapsed(), "Network error: ${e.message ?: "unreachable"}")
            }
        }
        TestResult(false, elapsed(), "HTTP $modelsCode from /models")
    }
}

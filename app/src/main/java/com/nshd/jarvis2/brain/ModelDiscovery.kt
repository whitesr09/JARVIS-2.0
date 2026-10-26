package com.nshd.jarvis2.brain

import com.nshd.jarvis2.ui.FriendlyErrors
import com.nshd.jarvis2.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Outcome of Connection Test v2 (F3). [endpointOk] is Stage 1
 * (GET /models with auth); when it is false, [endpointError] is the
 * friendly-ready reason and [probes] is empty — Stage 2 never ran.
 * [probes] preserves probe order: configured chat model first, then
 * discovered models in list order, native-audio models included as
 * [ProbeStatus.SKIPPED_AUDIO] without consuming a chat-probe slot.
 */
data class ConnectionTestOutcome(
    val endpointOk: Boolean,
    val endpointError: String? = null,
    val models: List<ModelDiscovery.ModelInfo> = emptyList(),
    val probes: List<ModelProbeResult> = emptyList()
)

/**
 * Live model discovery (F2): GET {baseUrl}/models on the user's own API
 * so the Universal Voice Engine (and the chat model picker) can work
 * with whatever models the provider actually offers — no hard-coded
 * model lists anywhere. The request mirrors
 * [OpenAiCompatibleProvider]'s auth style: the key travels only in the
 * Authorization header of a request to the configured base URL.
 */
object ModelDiscovery {

    enum class Kind { NATIVE_AUDIO, TEXT }

    data class ModelInfo(val id: String, val kind: Kind)

    /**
     * Native-audio (Gemini Live capable) models are recognised by the
     * "live" / "native-audio" markers in their ids — the same heuristic
     * the AUTO voice-engine selection uses.
     */
    fun classify(id: String): Kind {
        val lower = id.lowercase()
        return if (lower.contains("live") || lower.contains("native-audio")) {
            Kind.NATIVE_AUDIO
        } else {
            Kind.TEXT
        }
    }

    fun info(id: String): ModelInfo = ModelInfo(id, classify(id))

    suspend fun fetchModels(config: ProviderConfig): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            if (config.baseUrl.isBlank()) {
                return@withContext Result.failure(
                    AiException("No base URL configured for ${config.label}")
                )
            }
            if (config.apiKey.isBlank()) {
                return@withContext Result.failure(
                    AiException("No API key configured for ${config.label}")
                )
            }
            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .get()
                .build()
            val modelsHost = try {
                java.net.URI(config.baseUrl.trimEnd('/')).host ?: "endpoint"
            } catch (e: Exception) {
                "endpoint"
            }
            val fetchStartedAt = System.currentTimeMillis()
            try {
                client.newCall(request).execute().use { response ->
                    val bodyText = readLimitedBody(response.body)
                    val fetchMs = System.currentTimeMillis() - fetchStartedAt
                    if (!response.isSuccessful) {
                        // Provider error bodies may contain request details or
                        // server internals; keep diagnostics to status/latency.
                        AppLog.w(
                            "API",
                            "${config.id} list-models → HTTP " +
                                "${response.code} in ${fetchMs}ms"
                        )
                        return@withContext Result.failure(
                            AiException("HTTP ${response.code} from provider")
                        )
                    }
                    val ids = parseIds(bodyText)
                    AppLog.d(
                        "API",
                        "${config.id} list-models → 200 in " +
                            "${fetchMs}ms (${ids.size} models)"
                    )
                    if (ids.isEmpty()) {
                        Result.failure(
                            AiException("The provider returned no models")
                        )
                    } else {
                        Result.success(ids.map { info(it) })
                    }
                }
            } catch (e: IOException) {
                val safeReason = FriendlyErrors.shortReason(e.message)
                AppLog.e(
                    "API",
                    "${config.id} list-models network error (${e.javaClass.simpleName})"
                )
                Result.failure(AiException("Network error: $safeReason"))
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(
                    AiException("Unexpected model-list response from ${config.label}")
                )
            }
        }

    /** Hard cap on chat completions per Connection Test v2 run (F3). */
    const val MAX_CHAT_PROBES = 7

    /**
     * Connection Test v2 (F3):
     *  Stage 1 — endpoint check via [fetchModels] (GET /models, auth).
     *    Any failure stops the run: probing chat against an endpoint
     *    that cannot even list models would only produce noise.
     *  Stage 2 — sequential tiny chat probes (`ping`, max_tokens 1):
     *    the configured chat model first, then discovered models in
     *    list order, at most [MAX_CHAT_PROBES] chat models total.
     *    NATIVE_AUDIO models are never chat-probed; they are recorded
     *    as [ProbeStatus.SKIPPED_AUDIO] in place.
     */
    suspend fun runConnectionTestV2(config: ProviderConfig): ConnectionTestOutcome {
        val stage1 = fetchModels(config)
        val discovered = stage1.getOrElse { error ->
            return ConnectionTestOutcome(
                endpointOk = false,
                endpointError = error.message ?: "Endpoint check failed"
            )
        }
        // Probe order: configured chat model first (even when the
        // provider did not list it), then discovered ids in list order.
        val orderedIds = LinkedHashSet<String>()
        config.chatModel.trim().takeIf { it.isNotBlank() }?.let { orderedIds += it }
        discovered.forEach { orderedIds += it.id }
        val provider = config.toProvider()
        val probes = mutableListOf<ModelProbeResult>()
        var chatProbed = 0
        for (id in orderedIds) {
            if (chatProbed >= MAX_CHAT_PROBES) break
            val now = System.currentTimeMillis()
            if (classify(id) == Kind.NATIVE_AUDIO) {
                probes += ModelProbeResult(
                    modelId = id,
                    status = ProbeStatus.SKIPPED_AUDIO,
                    timestamp = now
                )
                continue
            }
            val result = provider.probeChatModel(id)
            probes += ModelProbeResult(
                modelId = id,
                status = if (result.ok) ProbeStatus.WORKING else ProbeStatus.FAILED,
                latencyMs = result.latencyMs,
                error = if (result.ok) "" else result.detail,
                timestamp = System.currentTimeMillis()
            )
            chatProbed++
        }
        return ConnectionTestOutcome(
            endpointOk = true,
            models = discovered,
            probes = probes
        )
    }

    /** OpenAI-shaped {"data":[{"id": …}]}; dedupes and caps returned identifiers. */
    internal fun parseIds(bodyText: String): List<String> {
        val ids = sortedSetOf<String>()
        try {
            val root = JSONObject(bodyText)
            val data = root.optJSONArray("data") ?: return emptyList()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty().trim()
                if (id.isNotBlank() && id.length <= MAX_MODEL_ID_CHARS) {
                    ids += id
                    if (ids.size >= MAX_DISCOVERED_MODELS) break
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return ids.toList()
    }

    private fun readLimitedBody(body: ResponseBody?): String {
        if (body == null) return ""
        if (body.contentLength() > MAX_MODEL_LIST_BYTES) {
            throw AiException("Provider model list exceeded the 1 MiB response limit")
        }
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val remaining = MAX_MODEL_LIST_BYTES + 1 - output.size()
                if (remaining <= 0) {
                    throw AiException("Provider model list exceeded the 1 MiB response limit")
                }
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_MODEL_LIST_BYTES) {
                    throw AiException("Provider model list exceeded the 1 MiB response limit")
                }
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private const val MAX_MODEL_LIST_BYTES = 1_048_576
    private const val MAX_DISCOVERED_MODELS = 500
    private const val MAX_MODEL_ID_CHARS = 256

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

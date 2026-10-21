package com.nshd.jarvis2.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

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
            try {
                client.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val brief = bodyText.replace('\n', ' ').take(140)
                        return@withContext Result.failure(
                            AiException(
                                "HTTP ${response.code}" +
                                    if (brief.isNotBlank()) ": $brief" else ""
                            )
                        )
                    }
                    val ids = parseIds(bodyText)
                    if (ids.isEmpty()) {
                        Result.failure(
                            AiException("The provider returned no models")
                        )
                    } else {
                        Result.success(ids.map { info(it) })
                    }
                }
            } catch (e: IOException) {
                Result.failure(
                    AiException("Network error: ${e.message ?: "unreachable"}")
                )
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(
                    AiException("Unexpected model-list response from ${config.label}")
                )
            }
        }

    /** OpenAI-shaped {"data":[{"id": …}]}; tolerates a bare array root. */
    private fun parseIds(bodyText: String): List<String> {
        val ids = sortedSetOf<String>()
        try {
            val root = JSONObject(bodyText)
            val data = root.optJSONArray("data") ?: return emptyList()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) ids += id
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return ids.toList() // sortedSet iteration = alphabetical
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

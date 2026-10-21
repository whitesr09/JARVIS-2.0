package com.nshd.jarvis2.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One chat message in OpenAI-compatible shape. */
data class ChatMsg(val role: String, val content: String)

data class TestResult(val ok: Boolean, val latencyMs: Long, val detail: String)

class AiException(message: String) : Exception(message)

interface AiProvider {
    val id: String
    val label: String

    /** @throws AiException on HTTP / network / parse failure. */
    suspend fun chat(messages: List<ChatMsg>, model: String): String

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

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")

    override suspend fun chat(messages: List<ChatMsg>, model: String): String =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) throw AiException("No base URL configured")
            if (apiKey.isBlank()) throw AiException("No API key configured")
            if (model.isBlank()) throw AiException("No model configured")

            val msgArray = JSONArray()
            messages.forEach { m ->
                msgArray.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            val payload = JSONObject()
                .put("model", model)
                .put("messages", msgArray)

            val request = authed(Request.Builder().url(endpoint("/chat/completions")))
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw AiException("Network error: ${e.message ?: "unreachable"}")
            }
            response.use {
                val bodyText = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val brief = bodyText.replace('\n', ' ').take(160)
                    throw AiException("HTTP ${it.code}${if (brief.isNotBlank()) ": $brief" else ""}")
                }
                val content = try {
                    JSONObject(bodyText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content")
                } catch (e: Exception) {
                    throw AiException("Unexpected response format")
                }
                if (content.isBlank()) throw AiException("Empty reply from model")
                content
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

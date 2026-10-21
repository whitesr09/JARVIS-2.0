package com.nshd.jarvis2.brain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Outcome of one model probe in Connection Test v2 (F3). */
enum class ProbeStatus { WORKING, FAILED, SKIPPED_AUDIO }

/**
 * One cached probe result (F3): per provider, per model.
 * [latencyMs] is meaningful only for [ProbeStatus.WORKING]; [error]
 * holds the short failure reason for [ProbeStatus.FAILED].
 */
data class ModelProbeResult(
    val modelId: String,
    val status: ProbeStatus,
    val latencyMs: Long = 0L,
    val error: String = "",
    val timestamp: Long = 0L
)

/** Editable per-provider configuration (persisted, key encrypted at rest). */
data class ProviderConfig(
    val id: String,
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val chatModel: String,
    val reasoningModel: String
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    fun toProvider(): OpenAiCompatibleProvider =
        OpenAiCompatibleProvider(id, label, baseUrl, apiKey, defaultModel = chatModel)
}

/**
 * Persists provider configs in EncryptedSharedPreferences. If encrypted
 * storage cannot be created on this device (rare OEM keystore quirks), it
 * falls back to plain SharedPreferences instead of crashing — and says so in
 * logcat only. API keys are never written to logs anywhere.
 */
class ProviderStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private fun createPrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs")
            appContext.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    fun loadConfig(id: String): ProviderConfig {
        val preset = ProviderPresets.byId(id)
        return ProviderConfig(
            id = preset.id,
            label = preset.label,
            baseUrl = prefs.getString(key(id, "base"), preset.baseUrl) ?: preset.baseUrl,
            apiKey = prefs.getString(key(id, "key"), "") ?: "",
            chatModel = prefs.getString(key(id, "chat_model"), preset.chatModel)
                ?: preset.chatModel,
            reasoningModel = prefs.getString(key(id, "reasoning_model"), preset.reasoningModel)
                ?: preset.reasoningModel
        )
    }

    fun saveConfig(config: ProviderConfig) {
        prefs.edit()
            .putString(key(config.id, "base"), config.baseUrl.trim())
            .putString(key(config.id, "key"), config.apiKey.trim())
            .putString(key(config.id, "chat_model"), config.chatModel.trim())
            .putString(key(config.id, "reasoning_model"), config.reasoningModel.trim())
            .apply()
    }

    /**
     * Last model list fetched from this provider (F2 discovery cache),
     * so the picker dialog reopens instantly. Stored as a JSON array of
     * ids; kinds are re-derived from the ids on read.
     */
    fun cachedModelIds(id: String): List<String> {
        val raw = prefs.getString(key(id, "models"), null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveModelIds(id: String, ids: List<String>) {
        val array = org.json.JSONArray()
        ids.forEach { array.put(it) }
        prefs.edit().putString(key(id, "models"), array.toString()).apply()
    }

    /**
     * Cached Connection-Test-v2 probe results for this provider (F3),
     * in probe order. Empty when never tested. Lets the API screen show
     * the last known health of every model instantly on reopen.
     */
    fun cachedProbeResults(id: String): List<ModelProbeResult> {
        val raw = prefs.getString(key(id, "probes"), null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            val out = mutableListOf<ModelProbeResult>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val modelId = obj.optString("model").takeIf { it.isNotBlank() } ?: continue
                val status = try {
                    ProbeStatus.valueOf(obj.optString("status"))
                } catch (e: IllegalArgumentException) {
                    ProbeStatus.FAILED
                }
                out += ModelProbeResult(
                    modelId = modelId,
                    status = status,
                    latencyMs = obj.optLong("latency", 0L),
                    error = obj.optString("error", ""),
                    timestamp = obj.optLong("ts", 0L)
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveProbeResults(id: String, results: List<ModelProbeResult>) {
        val array = org.json.JSONArray()
        results.forEach { r ->
            array.put(
                org.json.JSONObject()
                    .put("model", r.modelId)
                    .put("status", r.status.name)
                    .put("latency", r.latencyMs)
                    .put("error", r.error)
                    .put("ts", r.timestamp)
            )
        }
        prefs.edit().putString(key(id, "probes"), array.toString()).apply()
    }

    fun activeProviderId(): String =
        prefs.getString(KEY_ACTIVE, ProviderPresets.GEMINI.id) ?: ProviderPresets.GEMINI.id

    fun setActiveProviderId(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** All configs in preset (failover) order, active provider first. */
    fun orderedConfigs(): List<ProviderConfig> {
        val active = activeProviderId()
        val all = ProviderPresets.ALL.map { loadConfig(it.id) }
        return all.sortedBy { if (it.id == active) 0 else 1 } // stable: rest keep preset order
    }

    private fun key(id: String, field: String) = "prov_${id}_$field"

    companion object {
        private const val TAG = "ProviderStore"
        private const val FILE_NAME = "jarvis_providers_enc"
        private const val FALLBACK_FILE_NAME = "jarvis_providers_plain"
        private const val KEY_ACTIVE = "active_provider"
    }
}

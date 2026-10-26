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
 * Persists provider configs only in EncryptedSharedPreferences. If encrypted
 * storage cannot be initialized, provider keys are unavailable and writes
 * fail closed; credentials are never written to plaintext preferences.
 */
class ProviderStore(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    var storageError: String? = null
        private set

    @Volatile
    var migrationWarning: String? = null
        private set

    private val prefs: SharedPreferences? = createPrefs(appContext)

    val secureStorageAvailable: Boolean
        get() = prefs != null

    private fun createPrefs(appContext: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateLegacyPlaintextPrefs(appContext, encrypted)
            encrypted
        } catch (e: Exception) {
            val legacyPlaintextMayRemain = runCatching {
                appContext.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
                    .all.isNotEmpty()
            }.getOrDefault(false)
            storageError = if (legacyPlaintextMayRemain) {
                "Secure provider storage is unavailable. Jarvis did not read or write the legacy unencrypted settings, but an older plaintext copy may remain on this device."
            } else {
                "Secure provider storage is unavailable. API keys were not saved."
            }
            // Log only the exception type. Never log messages that could contain data.
            Log.e(TAG, "Encrypted provider storage unavailable; refusing plaintext fallback (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Older builds used a plaintext fallback. Copy its string values into
     * encrypted storage and delete the legacy file only after commit succeeds.
     * If migration fails, do not read from or write to the plaintext file.
     */
    private fun migrateLegacyPlaintextPrefs(
        appContext: Context,
        encrypted: SharedPreferences
    ) {
        try {
            val legacy = appContext.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
            val legacyValues = legacy.all
            if (legacyValues.isEmpty()) return
            val editor = encrypted.edit()
            for ((key, value) in legacyValues) {
                if (!encrypted.contains(key) && value is String) {
                    editor.putString(key, value)
                }
            }
            if (!editor.commit()) {
                migrationWarning = "Old provider settings could not be migrated securely. Re-enter provider keys and save."
                Log.e(TAG, "Legacy provider settings migration commit failed; plaintext source retained")
                return
            }
            if (legacy.edit().clear().commit()) {
                appContext.deleteSharedPreferences(FALLBACK_FILE_NAME)
                Log.i(TAG, "Legacy plaintext provider settings migrated and source removed")
            } else {
                migrationWarning = "Provider settings were encrypted, but the old plaintext copy could not be removed."
                Log.e(TAG, "Legacy provider settings migrated but plaintext source could not be cleared")
            }
        } catch (e: Exception) {
            migrationWarning = "Old provider settings could not be migrated securely. Re-enter provider keys and save."
            Log.e(TAG, "Legacy provider settings migration failed (${e.javaClass.simpleName})")
        }
    }

    fun loadConfig(id: String): ProviderConfig {
        val preset = ProviderPresets.byId(id)
        val storage = prefs ?: return ProviderConfig(
            id = preset.id,
            label = preset.label,
            baseUrl = preset.baseUrl,
            apiKey = "",
            chatModel = preset.chatModel,
            reasoningModel = preset.reasoningModel
        )
        return ProviderConfig(
            id = preset.id,
            label = preset.label,
            baseUrl = storage.getString(key(id, "base"), preset.baseUrl) ?: preset.baseUrl,
            apiKey = storage.getString(key(id, "key"), "") ?: "",
            chatModel = storage.getString(key(id, "chat_model"), preset.chatModel)
                ?: preset.chatModel,
            reasoningModel = storage.getString(key(id, "reasoning_model"), preset.reasoningModel)
                ?: preset.reasoningModel
        )
    }

    /** Returns false rather than persisting a key if encrypted storage is unavailable. */
    fun saveConfig(config: ProviderConfig): Boolean {
        val storage = prefs ?: return false
        return try {
            storage.edit()
                .putString(key(config.id, "base"), config.baseUrl.trim())
                .putString(key(config.id, "key"), config.apiKey.trim())
                .putString(key(config.id, "chat_model"), config.chatModel.trim())
                .putString(key(config.id, "reasoning_model"), config.reasoningModel.trim())
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted provider settings write failed (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Last model list fetched from this provider (F2 discovery cache),
     * so the picker dialog reopens instantly. Stored as a JSON array of
     * ids; kinds are re-derived from the ids on read.
     */
    fun cachedModelIds(id: String): List<String> {
        val raw = prefs?.getString(key(id, "models"), null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveModelIds(id: String, ids: List<String>): Boolean {
        val array = org.json.JSONArray()
        ids.forEach { array.put(it) }
        val storage = prefs ?: return false
        return storage.edit().putString(key(id, "models"), array.toString()).commit()
    }

    /**
     * Cached Connection-Test-v2 probe results for this provider (F3),
     * in probe order. Empty when never tested. Lets the API screen show
     * the last known health of every model instantly on reopen.
     */
    fun cachedProbeResults(id: String): List<ModelProbeResult> {
        val raw = prefs?.getString(key(id, "probes"), null) ?: return emptyList()
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

    fun saveProbeResults(id: String, results: List<ModelProbeResult>): Boolean {
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
        val storage = prefs ?: return false
        return storage.edit().putString(key(id, "probes"), array.toString()).commit()
    }

    fun activeProviderId(): String =
        prefs?.getString(KEY_ACTIVE, ProviderPresets.GEMINI.id) ?: ProviderPresets.GEMINI.id

    fun setActiveProviderId(id: String): Boolean {
        val storage = prefs ?: return false
        return storage.edit().putString(KEY_ACTIVE, id).commit()
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

package com.nshd.jarvis2.brain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

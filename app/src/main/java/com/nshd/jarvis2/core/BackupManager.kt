package com.nshd.jarvis2.core

import android.content.Context
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan #101: full backup / restore to ONE JSON file.
 *
 * Contents: every app setting (the "jarvis_settings" prefs), all Memory
 * Core facts, and the provider configurations (label, base URL, models,
 * active provider). API KEYS ARE NEVER EXPORTED — provider keys live in
 * EncryptedSharedPreferences whose ciphertext is device-bound and
 * useless in a portable file, so a restored provider simply needs its
 * key pasted in again; the backup says so in a `note` field.
 *
 * Restore: settings are written back (an app restart applies them),
 * facts are re-added skipping exact duplicates, provider configs are
 * NOT overwritten (only reported) so a restore can never wipe a working
 * key setup.
 */
object BackupManager {

    private const val SETTINGS_PREFS = "jarvis_settings"
    private const val FORMAT = "jarvis2-backup"
    private const val FORMAT_VERSION = 1
    private const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    private const val MAX_SETTINGS_STRING_CHARS = 32_768
    private const val MAX_FACT_CHARS = 16_384
    private const val MAX_FACTS = 10_000
    private const val MAX_PROVIDERS = 32

    // These flags refer to WAV samples stored under this device's private
    // filesDir/biometrics folder. The audio samples are intentionally not
    // exported, so a portable backup must not imply the destination has them.
    private val DEVICE_LOCAL_SETTING_KEYS = setOf(
        "biometrics_enrolled",
        "biometrics_sample_count",
        "biometrics_enrolled_date"
    )

    private enum class SettingKind { STRING, BOOLEAN, INTEGER, FLOAT }

    // Keep in sync with MainActivity.saveSettings / JarvisSettings. Strict
    // types prevent a crafted JSON file from causing ClassCastException when
    // Android later reads a preference using getString/getBoolean/etc.
    private val settingKinds = buildMap<String, SettingKind> {
        listOf(
            "assistant_name", "callsign", "theme", "custom_bg", "language", "persona",
            "voice_name", "custom_voice_name", "live_model", "voice_engine", "avatar_id",
            "biometrics_enrolled_date", "barge_in_sensitivity", "chat_font_size",
            "custom_persona_name", "custom_persona_prompt", "wake_sensitivity",
            "persona_day_persona", "persona_night_persona", "persona_override",
            "persona_override_day", "custom_wake_phrase"
        ).forEach { put(it, SettingKind.STRING) }
        listOf(
            "wake_always_on", "silent_execution", "biometrics_enrolled", "reduce_motion",
            "panel_haptics", "dnd_respect", "ptt_mode", "persona_schedule_enabled"
        ).forEach { put(it, SettingKind.BOOLEAN) }
        listOf(
            "biometrics_sample_count", "last_seen_version_code", "persona_day_start_hour",
            "persona_day_end_hour", "panel_size_percent"
        ).forEach { put(it, SettingKind.INTEGER) }
        listOf("overlay_avatar_opacity", "panel_bg_opacity")
            .forEach { put(it, SettingKind.FLOAT) }
    }

    private val allowedRootKeys = setOf(
        "format", "formatVersion", "appVersion", "exportedAt", "note",
        "settings", "facts", "providers", "activeProviderId"
    )

    data class ImportSummary(
        val settingsApplied: Int,
        val factsAdded: Int,
        val factsSkipped: Int,
        val providersListed: Int
    )

    fun exportJson(context: Context): String {
        val appContext = context.applicationContext
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("formatVersion", FORMAT_VERSION)
        root.put(
            "appVersion",
            runCatching {
                appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0).versionName
            }.getOrDefault("?")
        )
        root.put("exportedAt", System.currentTimeMillis())
        root.put(
            "note",
            "Provider API keys are NOT included (they stay encrypted " +
                "on-device). After restoring on a new phone, paste each " +
                "provider key again in Settings → API & Model. Voice-biometric " +
                "enrollment samples are device-local and are not backed up; " +
                "enroll again on the destination device."
        )

        val all = appContext
            .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .all
        val settingsObj = portableSettings(all)
        root.put("settings", settingsObj)

        val db = MemoryDb(appContext)
        val factsArr = JSONArray()
        db.allFacts(null, null).forEach { fact ->
            factsArr.put(
                JSONObject()
                    .put("category", fact.category)
                    .put("text", fact.text)
            )
        }
        root.put("facts", factsArr)

        val store = ProviderStore(appContext)
        val providersArr = JSONArray()
        store.orderedConfigs().forEach { cfg ->
            providersArr.put(
                JSONObject()
                    .put("id", cfg.id)
                    .put("label", cfg.label)
                    .put("baseUrl", cfg.baseUrl)
                    .put("chatModel", cfg.chatModel)
                    .put("reasoningModel", cfg.reasoningModel)
                    .put("keyIncluded", false)
            )
        }
        root.put("providers", providersArr)
        root.put("activeProviderId", store.activeProviderId())
        // Run the same checks on our own export so it can always be
        // imported by this version; do not generate a backup that silently
        // fails its own size/schema constraints.
        val output = root.toString(2)
        validateBackupJson(output)
        AppLog.i(
            "App",
            "Backup exported: ${settingsObj.length()} settings, ${factsArr.length()} facts, " +
                "${providersArr.length()} providers (keys excluded)"
        )
        return output
    }

    /** Build an exportable settings object, excluding device-local enrollment state. */
    internal fun portableSettings(values: Map<String, *>): JSONObject {
        val result = JSONObject()
        for ((key, value) in values) {
            // Export only the current schema. Unknown legacy preference keys
            // are intentionally dropped rather than making the resulting file
            // fail the import allowlist or revive retired settings.
            if (key in DEVICE_LOCAL_SETTING_KEYS || key !in settingKinds) continue
            when (value) {
                is String -> result.put(key, value)
                is Boolean -> result.put(key, value)
                is Int -> result.put(key, value)
                is Long -> result.put(key, value)
                is Float -> if (value.isFinite()) result.put(key, value.toDouble())
                is Double -> if (value.isFinite()) result.put(key, value)
                else -> Unit // sets and unsupported preference types are not part of JarvisSettings
            }
        }
        return result
    }

    /**
     * Validate the complete backup before any settings or memory is changed.
     * Internal visibility lets local JVM tests cover malformed-file handling
     * without requiring an Android Context or database.
     */
    internal fun validateBackupJson(json: String): JSONObject {
        require(json.length <= MAX_BACKUP_BYTES) {
            "Backup file is too large (maximum 5 MiB)."
        }
        require(json.isNotBlank()) { "Backup file is empty." }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "Backup file is too large (maximum 5 MiB)."
        }
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) {
            "Not a Jarvis backup file (missing format marker)."
        }
        val version = root.opt("formatVersion")
        require(version is Number && version.toDouble() == FORMAT_VERSION.toDouble()) {
            "Unsupported Jarvis backup format version."
        }
        val rootKeys = root.keys()
        while (rootKeys.hasNext()) {
            val key = rootKeys.next()
            require(key in allowedRootKeys) { "Unsupported backup field: $key" }
        }
        val settings = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("Backup settings section is missing or invalid.")
        val settingKeys = settings.keys()
        while (settingKeys.hasNext()) {
            val key = settingKeys.next()
            val kind = settingKinds[key]
                ?: throw IllegalArgumentException("Unsupported setting key: $key")
            val value = settings.get(key)
            when (kind) {
                SettingKind.STRING -> {
                    require(value is String && value.length <= MAX_SETTINGS_STRING_CHARS) {
                        "Setting '$key' must be a string no longer than $MAX_SETTINGS_STRING_CHARS characters."
                    }
                }
                SettingKind.BOOLEAN -> require(value is Boolean) {
                    "Setting '$key' must be true or false."
                }
                SettingKind.INTEGER -> {
                    require(value is Int || value is Long) {
                        "Setting '$key' must be an integer."
                    }
                    val numeric = (value as Number).toLong()
                    val valid = when (key) {
                        "persona_day_start_hour", "persona_day_end_hour" -> numeric in 0L..23L
                        "panel_size_percent" -> numeric in 80L..120L
                        "biometrics_sample_count" -> numeric in 0L..10_000L
                        "last_seen_version_code" -> numeric in 0L..1_000_000L
                        else -> false
                    }
                    require(valid) { "Setting '$key' is outside its allowed range." }
                }
                SettingKind.FLOAT -> {
                    require(value is Number) { "Setting '$key' must be numeric." }
                    val numeric = (value as Number).toDouble()
                    val range = when (key) {
                        "overlay_avatar_opacity" -> 0.25..1.0
                        "panel_bg_opacity" -> 0.35..1.0
                        else -> null
                    }
                    require(numeric.isFinite() && range != null && numeric in range) {
                        "Setting '$key' is outside its allowed range."
                    }
                }
            }
        }

        val facts = root.optJSONArray("facts")
            ?: throw IllegalArgumentException("Backup facts section is missing or invalid.")
        require(facts.length() <= MAX_FACTS) {
            "Backup contains too many memory entries (maximum $MAX_FACTS)."
        }
        for (i in 0 until facts.length()) {
            val fact = facts.optJSONObject(i)
                ?: throw IllegalArgumentException("Memory entry " + (i + 1) + " is invalid.")
            val text = fact.opt("text")
            require(text is String && text.length <= MAX_FACT_CHARS) {
                "Memory entry " + (i + 1) + " has invalid or oversized text."
            }
            val category = fact.opt("category")
            require(category is String && category.length <= 64) {
                "Memory entry " + (i + 1) + " has an invalid category."
            }
            val factKeys = fact.keys()
            while (factKeys.hasNext()) {
                val key = factKeys.next()
                require(key == "text" || key == "category") {
                    "Unsupported field in memory entry " + (i + 1) + "."
                }
            }
        }

        val providers = root.optJSONArray("providers")
            ?: throw IllegalArgumentException("Backup providers section is missing or invalid.")
        require(providers.length() <= MAX_PROVIDERS) {
            "Backup contains too many provider entries."
        }
        val allowedProviderKeys = setOf(
            "id", "label", "baseUrl", "chatModel", "reasoningModel", "keyIncluded"
        )
        for (i in 0 until providers.length()) {
            val provider = providers.optJSONObject(i)
                ?: throw IllegalArgumentException("Provider entry " + (i + 1) + " is invalid.")
            val providerKeys = provider.keys()
            while (providerKeys.hasNext()) {
                val key = providerKeys.next()
                require(key in allowedProviderKeys) {
                    "Unsupported field in provider entry " + (i + 1) + "."
                }
            }
            val keyIncluded = provider.opt("keyIncluded")
            require(keyIncluded == null || (keyIncluded is Boolean && !keyIncluded)) {
                "Provider API keys must not be included in a backup."
            }
        }
        val activeProviderId = root.opt("activeProviderId")
        require(activeProviderId is String && activeProviderId.length <= 128) {
            "Backup active-provider identifier is missing or invalid."
        }
        val appVersion = root.opt("appVersion")
        require(appVersion == null || (appVersion is String && appVersion.length <= 128)) {
            "Backup application version field is invalid."
        }
        val exportedAt = root.opt("exportedAt")
        require(exportedAt == null || (exportedAt is Number && (exportedAt as Number).toLong() >= 0L)) {
            "Backup export timestamp is invalid."
        }
        val note = root.opt("note")
        require(note == null || (note is String && note.length <= 2_048)) {
            "Backup note field is invalid."
        }
        return root
    }

    /** Throws on malformed input; the caller surfaces a friendly error. */
    fun importJson(context: Context, json: String): ImportSummary {
        val appContext = context.applicationContext
        val root = validateBackupJson(json)
        val settingsObj = root.getJSONObject("settings")
        val factsArr = root.getJSONArray("facts")
        val providersArr = root.getJSONArray("providers")

        var settingsApplied = 0
        val prefs = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val keys = settingsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // Legacy backups may contain these flags, but the corresponding
            // WAV samples are not portable. Never restore a stale enrolled
            // flag on another device; leave local enrollment state untouched.
            if (key in DEVICE_LOCAL_SETTING_KEYS) continue
            when (val value = settingsObj.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> {
                    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        "Setting '$key' is outside the supported integer range."
                    }
                    editor.putInt(key, value.toInt())
                }
                is Double -> {
                    require(value.isFinite() && value.toFloat().isFinite()) {
                        "Setting '$key' is outside the supported numeric range."
                    }
                    editor.putFloat(key, value.toFloat())
                }
                else -> throw IllegalArgumentException("Unsupported setting value type for '$key'.")
            }
            settingsApplied++
        }

        val db = MemoryDb(appContext)
        var factsAdded = 0
        var factsSkipped = 0

        // Read the dedupe set inside the same transaction as the inserts.
        // A concurrent memory write cannot slip between the snapshot and import.
        // Commit settings only after all fact writes succeed.
        db.withTransaction {
            val existing = db.allFacts(null, null)
                .map { it.category + "\u0001" + it.text.trim().lowercase() }
                .toHashSet()
            for (i in 0 until factsArr.length()) {
                val obj = factsArr.getJSONObject(i)
                val text = obj.getString("text").trim()
                val category = obj.optString("category")
                    .takeIf { it in MemoryCategories.ALL }
                    ?: MemoryCategories.GENERAL
                val dedupeKey = category + "\u0001" + text.lowercase()
                if (text.isEmpty() || dedupeKey in existing) {
                    factsSkipped++
                } else {
                    val insertedId = db.addFact(category, text)
                    require(insertedId > 0L) {
                        "A memory entry could not be saved; restore was not completed."
                    }
                    existing += dedupeKey
                    factsAdded++
                }
            }
            require(editor.commit()) {
                "Settings could not be saved; restore was not completed."
            }
        }

        val providersListed = providersArr.length()
        AppLog.i(
            "App",
            "Backup restored: $settingsApplied settings, $factsAdded facts added, " +
                "$factsSkipped skipped, $providersListed providers listed (not overwritten)"
        )
        return ImportSummary(settingsApplied, factsAdded, factsSkipped, providersListed)
    }
}

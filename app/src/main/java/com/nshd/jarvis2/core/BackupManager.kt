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
                "provider key again in Settings → API & Model."
        )

        val settingsObj = JSONObject()
        val all = appContext
            .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .all
        for ((key, value) in all) {
            when (value) {
                is String -> settingsObj.put(key, value)
                is Boolean -> settingsObj.put(key, value)
                is Int -> settingsObj.put(key, value)
                is Long -> settingsObj.put(key, value)
                is Float -> settingsObj.put(key, value.toDouble())
                else -> Unit // sets are not used by JarvisSettings
            }
        }
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
        AppLog.i(
            "App",
            "Backup exported: ${all.size} settings, ${factsArr.length()} facts, " +
                "${providersArr.length()} providers (keys excluded)"
        )
        return root.toString(2)
    }

    /** Throws on malformed input; the caller surfaces a friendly error. */
    fun importJson(context: Context, json: String): ImportSummary {
        val appContext = context.applicationContext
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) {
            "Not a Jarvis backup file (missing format marker)."
        }

        var settingsApplied = 0
        val settingsObj = root.optJSONObject("settings")
        if (settingsObj != null) {
            val editor = appContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit()
            val keys = settingsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val v = settingsObj.get(key)) {
                    is String -> editor.putString(key, v)
                    is Boolean -> editor.putBoolean(key, v)
                    is Int -> editor.putInt(key, v)
                    is Long -> editor.putLong(key, v)
                    is Double -> editor.putFloat(key, v.toFloat())
                    else -> Unit
                }
                settingsApplied++
            }
            editor.apply()
        }

        val db = MemoryDb(appContext)
        val existing = db.allFacts(null, null)
            .map { it.category + "" + it.text.trim().lowercase() }
            .toHashSet()
        var factsAdded = 0
        var factsSkipped = 0
        val factsArr = root.optJSONArray("facts")
        if (factsArr != null) {
            for (i in 0 until factsArr.length()) {
                val obj = factsArr.optJSONObject(i) ?: continue
                val text = obj.optString("text").trim()
                if (text.isEmpty()) {
                    factsSkipped++
                    continue
                }
                val category = obj.optString("category")
                    .takeIf { it in MemoryCategories.ALL }
                    ?: MemoryCategories.GENERAL
                val dedupeKey = category + "" + text.lowercase()
                if (dedupeKey in existing) {
                    factsSkipped++
                } else {
                    db.addFact(category, text)
                    existing += dedupeKey
                    factsAdded++
                }
            }
        }

        val providersListed = root.optJSONArray("providers")?.length() ?: 0
        AppLog.i(
            "App",
            "Backup restored: $settingsApplied settings, $factsAdded facts added, " +
                "$factsSkipped skipped, $providersListed providers listed (not overwritten)"
        )
        return ImportSummary(settingsApplied, factsAdded, factsSkipped, providersListed)
    }
}

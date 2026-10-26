package com.nshd.jarvis2.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerValidationTest {

    private fun backup(
        settings: JSONObject = JSONObject(),
        facts: JSONArray = JSONArray(),
        providers: JSONArray = JSONArray(),
        version: Any = 1
    ): String = JSONObject()
        .put("format", "jarvis2-backup")
        .put("formatVersion", version)
        .put("appVersion", "2.0.3")
        .put("exportedAt", 1_797_000_000_000L)
        .put("note", "Provider API keys are not included.")
        .put("settings", settings)
        .put("facts", facts)
        .put("providers", providers)
        .put("activeProviderId", "gemini")
        .toString()

    @Test
    fun acceptsValidExportShapeAndKnownSettingTypes() {
        val json = backup(
            settings = JSONObject()
                .put("theme", "DARK")
                .put("wake_always_on", false)
                .put("persona_day_start_hour", 9)
                .put("panel_size_percent", 100)
                .put("overlay_avatar_opacity", 1.0)
                .put("panel_bg_opacity", 0.97),
            facts = JSONArray().put(
                JSONObject().put("category", "General").put("text", "A remembered preference")
            ),
            providers = JSONArray().put(
                JSONObject()
                    .put("id", "gemini")
                    .put("label", "Gemini")
                    .put("baseUrl", "https://example.invalid")
                    .put("chatModel", "model")
                    .put("reasoningModel", "")
                    .put("keyIncluded", false)
            )
        )

        val root = BackupManager.validateBackupJson(json)

        assertEquals(1, root.getInt("formatVersion"))
        assertEquals(1, root.getJSONArray("facts").length())
    }

    @Test
    fun portableBackupExcludesDeviceLocalBiometricEnrollmentFlags() {
        val settings = BackupManager.portableSettings(
            mapOf(
                "theme" to "DARK",
                "wake_always_on" to false,
                "biometrics_enrolled" to true,
                "biometrics_sample_count" to 3,
                "biometrics_enrolled_date" to "2026-10-26"
            )
        )

        assertEquals("DARK", settings.getString("theme"))
        assertEquals(false, settings.getBoolean("wake_always_on"))
        assertEquals(false, settings.has("biometrics_enrolled"))
        assertEquals(false, settings.has("biometrics_sample_count"))
        assertEquals(false, settings.has("biometrics_enrolled_date"))
    }

    @Test
    fun rejectsUnsupportedFormatVersion() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(backup(version = 2))
        }
        assertTrue(error.message.orEmpty().contains("format version"))
    }

    @Test
    fun rejectsUnknownPreferenceKeysBeforeRestore() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(
                backup(settings = JSONObject().put("unexpected_internal_flag", true))
            )
        }
        assertTrue(error.message.orEmpty().contains("Unsupported setting key"))
    }

    @Test
    fun rejectsPreferenceTypeMismatchThatCouldCrashTypedReads() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(
                backup(settings = JSONObject().put("theme", true))
            )
        }
        assertTrue(error.message.orEmpty().contains("theme"))
    }

    @Test
    fun rejectsOutOfRangePreferenceValues() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(
                backup(settings = JSONObject().put("persona_day_start_hour", 24))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(
                backup(settings = JSONObject().put("panel_bg_opacity", 1.5))
            )
        }
    }

    @Test
    fun rejectsProviderKeysAndUnknownProviderFields() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(
                backup(
                    providers = JSONArray().put(
                        JSONObject()
                            .put("id", "gemini")
                            .put("keyIncluded", true)
                    )
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(
                backup(
                    providers = JSONArray().put(
                        JSONObject()
                            .put("id", "gemini")
                            .put("privateToken", "must-not-be-imported")
                    )
                )
            )
        }
    }

    @Test
    fun rejectsMalformedFactsAndOversizedBackup() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson(
                backup(facts = JSONArray().put(JSONObject().put("text", "missing category")))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.validateBackupJson("x".repeat(5 * 1024 * 1024 + 1))
        }
    }
}

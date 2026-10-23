package com.nshd.jarvis2.core

import android.content.Context
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.ui.AppTheme

/**
 * v2.0.0: load [JarvisSettings] from the shared "jarvis_settings"
 * prefs outside MainActivity (headless routine runs). Mirrors
 * MainActivity.loadSettings / VoiceSessionManager.loadSettingsForWake
 * — same keys, same defaults, same theme migration.
 */
object SettingsLoader {

    fun load(context: Context): JarvisSettings {
        val prefs = context.applicationContext
            .getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val rawTheme = prefs.getString("theme", AppTheme.DARK.name)
            ?: AppTheme.DARK.name
        val theme = if (rawTheme == "PURPLE") {
            AppTheme.DARK
        } else {
            try {
                AppTheme.valueOf(rawTheme)
            } catch (e: IllegalArgumentException) {
                AppTheme.DARK
            }
        }
        return JarvisSettings(
            assistantName = prefs.getString("assistant_name", "Jarvis") ?: "Jarvis",
            callsign = prefs.getString("callsign", "Nishad") ?: "Nishad",
            theme = theme,
            customBgHex = prefs.getString("custom_bg", null),
            language = prefs.getString("language", "Auto") ?: "Auto",
            persona = prefs.getString("persona", "Jarvis Pro") ?: "Jarvis Pro",
            wakeAlwaysOn = prefs.getBoolean("wake_always_on", false),
            silentExecution = prefs.getBoolean("silent_execution", false),
            voiceName = prefs.getString("voice_name", "Kore") ?: "Kore",
            customVoiceName = prefs.getString("custom_voice_name", "") ?: "",
            liveModel = prefs.getString(
                "live_model", "gemini-2.5-flash-native-audio-preview-12-2025"
            ) ?: "gemini-2.5-flash-native-audio-preview-12-2025",
            voiceEngine = prefs.getString("voice_engine", "AUTO") ?: "AUTO",
            avatarId = prefs.getString("avatar_id", "droid") ?: "droid",
            biometricsEnrolled = prefs.getBoolean("biometrics_enrolled", false),
            biometricsSampleCount = prefs.getInt("biometrics_sample_count", 0),
            biometricsEnrolledDate = prefs.getString("biometrics_enrolled_date", "") ?: "",
            ttsRate = prefs.getFloat("tts_rate", 1.18f),
            ttsPitch = prefs.getFloat("tts_pitch", 1.22f),
            bargeInSensitivity = prefs.getString("barge_in_sensitivity", "NORMAL") ?: "NORMAL",
            ttsVoiceName = prefs.getString("tts_voice_name", "") ?: "",
            chatFontSize = prefs.getString("chat_font_size", "M") ?: "M",
            reduceMotion = prefs.getBoolean("reduce_motion", false),
            panelHaptics = prefs.getBoolean("panel_haptics", true),
            lastSeenVersionCode = prefs.getInt("last_seen_version_code", 0),
            customPersonaName = prefs.getString("custom_persona_name", "") ?: "",
            customPersonaPrompt = prefs.getString("custom_persona_prompt", "") ?: "",
            dndRespect = prefs.getBoolean("dnd_respect", true),
            pttMode = prefs.getBoolean("ptt_mode", false),
            wakeSensitivity = prefs.getString("wake_sensitivity", "NORMAL") ?: "NORMAL",
            personaScheduleEnabled = prefs.getBoolean("persona_schedule_enabled", false),
            personaDayPersona = prefs.getString("persona_day_persona", "Jarvis Pro")
                ?: "Jarvis Pro",
            personaNightPersona = prefs.getString("persona_night_persona", "Girlfriend Mode")
                ?: "Girlfriend Mode",
            personaDayStartHour = prefs.getInt("persona_day_start_hour", 9),
            personaDayEndHour = prefs.getInt("persona_day_end_hour", 18),
            personaOverride = prefs.getString("persona_override", "") ?: "",
            personaOverrideDay = prefs.getString("persona_override_day", "") ?: "",
            customWakePhrase = prefs.getString("custom_wake_phrase", "") ?: ""
        )
    }
}

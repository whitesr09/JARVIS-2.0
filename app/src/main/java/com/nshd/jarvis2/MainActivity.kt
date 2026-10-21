package com.nshd.jarvis2

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import com.nshd.jarvis2.ui.AppNavRequests
import com.nshd.jarvis2.ui.AppTheme
import com.nshd.jarvis2.ui.JarvisApp

/** Persisted user settings (Level 1: plain SharedPreferences; encrypted store arrives with keys in L2). */
data class JarvisSettings(
    val assistantName: String = "Jarvis",
    val callsign: String = "Nishad",
    val theme: AppTheme = AppTheme.SUNSET,
    val customBgHex: String? = null,
    val language: String = "Auto",
    val persona: String = "Jarvis Pro",
    val wakeAlwaysOn: Boolean = false,
    val silentExecution: Boolean = false,
    /** Gemini Live voice name (one of the 5 picker voices). */
    val voiceName: String = "Kore",
    /** Free-text custom voice name — overrides [voiceName] when non-blank. */
    val customVoiceName: String = "",
    /** Gemini Live model, WITHOUT the "models/" prefix (the client adds it). */
    val liveModel: String = "gemini-2.5-flash-native-audio-preview-12-2025",
    /** Floating-overlay companion (see AvatarCatalog); default "droid". */
    val avatarId: String = "droid"
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var settings by mutableStateOf(JarvisSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavIntent(intent)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        settings = loadSettings()
        setContent {
            JarvisApp(
                settings = settings,
                onSettingsChange = { updated ->
                    settings = updated
                    saveSettings(updated)
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }

    /** The floating overlay bubble asks for the Voice screen via this extra. */
    private fun handleNavIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_VOICE, false) == true) {
            AppNavRequests.requestVoice()
            intent.removeExtra(EXTRA_OPEN_VOICE)
        }
    }

    private fun loadSettings(): JarvisSettings {
        val theme = try {
            AppTheme.valueOf(prefs.getString(KEY_THEME, AppTheme.SUNSET.name) ?: AppTheme.SUNSET.name)
        } catch (e: IllegalArgumentException) {
            AppTheme.SUNSET
        }
        return JarvisSettings(
            assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis",
            callsign = prefs.getString(KEY_CALLSIGN, "Nishad") ?: "Nishad",
            theme = theme,
            customBgHex = prefs.getString(KEY_CUSTOM_BG, null),
            language = prefs.getString(KEY_LANGUAGE, "Auto") ?: "Auto",
            persona = prefs.getString(KEY_PERSONA, "Jarvis Pro") ?: "Jarvis Pro",
            wakeAlwaysOn = prefs.getBoolean(KEY_WAKE_ALWAYS_ON, false),
            silentExecution = prefs.getBoolean(KEY_SILENT, false),
            voiceName = prefs.getString(KEY_VOICE_NAME, "Kore") ?: "Kore",
            customVoiceName = prefs.getString(KEY_CUSTOM_VOICE, "") ?: "",
            liveModel = prefs.getString(
                KEY_LIVE_MODEL, "gemini-2.5-flash-native-audio-preview-12-2025"
            ) ?: "gemini-2.5-flash-native-audio-preview-12-2025",
            avatarId = prefs.getString(KEY_AVATAR_ID, "droid") ?: "droid"
        )
    }

    private fun saveSettings(s: JarvisSettings) {
        prefs.edit()
            .putString(KEY_ASSISTANT_NAME, s.assistantName)
            .putString(KEY_CALLSIGN, s.callsign)
            .putString(KEY_THEME, s.theme.name)
            .putString(KEY_CUSTOM_BG, s.customBgHex)
            .putString(KEY_LANGUAGE, s.language)
            .putString(KEY_PERSONA, s.persona)
            .putBoolean(KEY_WAKE_ALWAYS_ON, s.wakeAlwaysOn)
            .putBoolean(KEY_SILENT, s.silentExecution)
            .putString(KEY_VOICE_NAME, s.voiceName)
            .putString(KEY_CUSTOM_VOICE, s.customVoiceName)
            .putString(KEY_LIVE_MODEL, s.liveModel)
            .putString(KEY_AVATAR_ID, s.avatarId)
            .apply()
    }

    companion object {
        const val EXTRA_OPEN_VOICE = "open_voice"
        private const val PREFS_NAME = "jarvis_settings"
        private const val KEY_ASSISTANT_NAME = "assistant_name"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_THEME = "theme"
        private const val KEY_CUSTOM_BG = "custom_bg"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PERSONA = "persona"
        private const val KEY_WAKE_ALWAYS_ON = "wake_always_on"
        private const val KEY_SILENT = "silent_execution"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_CUSTOM_VOICE = "custom_voice_name"
        private const val KEY_LIVE_MODEL = "live_model"
        private const val KEY_AVATAR_ID = "avatar_id"
    }
}

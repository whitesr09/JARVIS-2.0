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
import android.os.Build
import com.nshd.jarvis2.service.FloatingOverlayService
import com.nshd.jarvis2.service.WakeWordControl
import com.nshd.jarvis2.ui.AppNavRequests
import com.nshd.jarvis2.voice.VoskModelManager
import com.nshd.jarvis2.ui.AppTheme
import com.nshd.jarvis2.ui.JarvisApp

/** Persisted user settings (Level 1: plain SharedPreferences; encrypted store arrives with keys in L2). */
data class JarvisSettings(
    val assistantName: String = "Jarvis",
    val callsign: String = "Nishad",
    val theme: AppTheme = AppTheme.DARK,
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
    /**
     * Voice engine selection (F2): "AUTO" (Native Live when the model
     * supports it, otherwise Universal), "NATIVE" (Gemini Live only,
     * with Universal fallback on failure), "UNIVERSAL" (SpeechRecognizer
     * → chat model → TTS; works with any provider/model).
     */
    val voiceEngine: String = "AUTO",
    /** Floating-overlay companion (see AvatarCatalog); default "droid". */
    val avatarId: String = "droid",
    /** Voice biometrics enrollment (Level 6): samples recorded on-device. */
    val biometricsEnrolled: Boolean = false,
    val biometricsSampleCount: Int = 0,
    /** Human-readable enrollment date, e.g. "21 Oct 2026" ("" when none). */
    val biometricsEnrolledDate: String = ""
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var settings by mutableStateOf(JarvisSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // F4.1: crash capture goes in before ANYTHING else runs, so a
        // crash anywhere in startup is still recorded for the report
        // screen on the next launch.
        CrashLogger.install(this)
        // F2: track app foreground so the floating overlay bubble hides
        // while Jarvis's own screens are up.
        com.nshd.jarvis2.service.AppForeground.register(application)
        handleNavIntent(intent)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        settings = loadSettings()
        // Always-on wake word was left enabled: restart the listener (the
        // service is START_STICKY too, this also covers a fresh process).
        if (settings.wakeAlwaysOn &&
            VoskModelManager.status(this) == VoskModelManager.ModelStatus.READY
        ) {
            runCatching { WakeWordControl.start(this) }
        }
        setContent {
            JarvisApp(
                settings = settings,
                onSettingsChange = { updated ->
                    val themeChanged = updated.theme != settings.theme ||
                        updated.customBgHex != settings.customBgHex
                    settings = updated
                    saveSettings(updated)
                    // D3: a running overlay re-reads its HUD palette on
                    // start, so a theme change re-skins the HUD live.
                    if (themeChanged && FloatingOverlayService.isRunning()) {
                        runCatching { FloatingOverlayService.start(this) }
                    }
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
        if (intent == null) return
        // Wake-word launches must punch through the lock screen: the flags
        // have to be set before the content shows (API 27+ activity calls).
        if (intent.getBooleanExtra(EXTRA_FROM_WAKE, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            intent.removeExtra(EXTRA_FROM_WAKE)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_VOICE, false) == true) {
            AppNavRequests.requestVoice()
            intent.removeExtra(EXTRA_OPEN_VOICE)
        }
    }

    private fun loadSettings(): JarvisSettings {
        // F4 migration: the retired pre-F4 default theme (its stored
        // name is checked literally below) reads as DARK, the new
        // default. SUNSET is a live theme again and parses normally;
        // any unknown value also falls back to DARK.
        val rawTheme = prefs.getString(KEY_THEME, AppTheme.DARK.name) ?: AppTheme.DARK.name
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
            voiceEngine = prefs.getString(KEY_VOICE_ENGINE, "AUTO") ?: "AUTO",
            avatarId = prefs.getString(KEY_AVATAR_ID, "droid") ?: "droid",
            biometricsEnrolled = prefs.getBoolean(KEY_BIO_ENROLLED, false),
            biometricsSampleCount = prefs.getInt(KEY_BIO_COUNT, 0),
            biometricsEnrolledDate = prefs.getString(KEY_BIO_DATE, "") ?: ""
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
            .putString(KEY_VOICE_ENGINE, s.voiceEngine)
            .putString(KEY_AVATAR_ID, s.avatarId)
            .putBoolean(KEY_BIO_ENROLLED, s.biometricsEnrolled)
            .putInt(KEY_BIO_COUNT, s.biometricsSampleCount)
            .putString(KEY_BIO_DATE, s.biometricsEnrolledDate)
            .apply()
    }

    companion object {
        const val EXTRA_OPEN_VOICE = "open_voice"
        /** Wake word fired: show over the lock screen and turn it on. */
        const val EXTRA_FROM_WAKE = "from_wake"
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
        private const val KEY_VOICE_ENGINE = "voice_engine"
        private const val KEY_AVATAR_ID = "avatar_id"
        private const val KEY_BIO_ENROLLED = "biometrics_enrolled"
        private const val KEY_BIO_COUNT = "biometrics_sample_count"
        private const val KEY_BIO_DATE = "biometrics_enrolled_date"
    }
}

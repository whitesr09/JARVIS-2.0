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
    val biometricsEnrolledDate: String = "",
    /** Device-TTS speed for the Universal voice (F5.1 slider, 0.80–1.50×). */
    val ttsRate: Float = 1.18f,
    /** Device-TTS pitch for the Universal voice (F5.1 slider, 0.80–1.60×; >1 = girlier). */
    val ttsPitch: Float = 1.22f,
    /** Plan #4: Live barge-in sensitivity — "LOW" / "NORMAL" / "HIGH". */
    val bargeInSensitivity: String = "NORMAL",
    /** Plan #7: chosen device-TTS voice name ("" = automatic best voice). */
    val ttsVoiceName: String = "",
    /** Plan #93: chat message font size — "S" / "M" / "L". */
    val chatFontSize: String = "M",
    /** Plan #94: reduce-motion — calms orb + panel animations. */
    val reduceMotion: Boolean = false,
    /** Plan #58: haptic tick in the floating panel when a plan step completes. */
    val panelHaptics: Boolean = true,
    /** Plan #105: highest versionCode whose "What's new" card was dismissed. */
    val lastSeenVersionCode: Int = 0,
    /** Plan #77: user-built persona — name + prompt ("" = none saved). */
    val customPersonaName: String = "",
    val customPersonaPrompt: String = "",
    /** Plan #87: while Do-Not-Disturb is on, voice sessions stay silent. */
    val dndRespect: Boolean = true,
    /** Plan #14: push-to-talk — hold the orb/mic to talk, release to send. */
    val pttMode: Boolean = false,
    /** Plan #9: wake-word sensitivity — "LOW" / "NORMAL" / "HIGH". */
    val wakeSensitivity: String = "NORMAL",
    /**
     * Plan #78 (v2.0.0): persona schedule — during the day window the
     * [personaDayPersona] is active, outside it [personaNightPersona].
     * A manual persona pick while the schedule is on becomes a
     * one-day [personaOverride] (manual override wins, per the plan).
     */
    val personaScheduleEnabled: Boolean = false,
    val personaDayPersona: String = "Jarvis Pro",
    val personaNightPersona: String = "Girlfriend Mode",
    val personaDayStartHour: Int = 9,
    val personaDayEndHour: Int = 18,
    /** Manual persona override ("" = none) + the day it was set ("yyyy-MM-dd"). */
    val personaOverride: String = "",
    val personaOverrideDay: String = "",
    /** Plan #10 (v2.0.0): his own extra wake phrase ("" = Hey Jarvis only). */
    val customWakePhrase: String = "",
    /** v2.0.1: floating avatar opacity (0.25–1.0; the figure only, never a plate). */
    val overlayAvatarOpacity: Float = 1.0f,
    /** v2.0.1: floating task-panel size, percent of the shipped width (80–120). */
    val panelSizePercent: Int = 100,
    /**
     * v2.0.1: floating task-panel background opacity (0.35–1.0). The
     * default matches the shipped palette fill (PANEL_ALPHA 0xF7 ≈ 0.97).
     */
    val panelBgOpacity: Float = 0.97f
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var settings by mutableStateOf(JarvisSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        // Plan #25 (cold-start audit): keep onCreate lean — no disk IO
        // beyond prefs on the start path; the timer below logs how long
        // first composition actually took to set up.
        val createStartMs = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        // Plan #102: stamp the boot BEFORE anything that could crash, so
        // the crash handler can tell a boot-loop crash from a late one.
        CrashLogger.markBootStart(this)
        val safeMode = CrashLogger.isSafeMode(this)
        // F5: the full log system initialises first so every later
        // breadcrumb (including CrashLogger's, which mirror into
        // AppLog) lands in the persistent log from the very start.
        com.nshd.jarvis2.core.AppLog.init(this)
        com.nshd.jarvis2.core.AppLog.i(
            "App",
            "MainActivity onCreate — Jarvis " +
                runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrDefault("?")
        )
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
        // Plan #102 (safe mode): after 3 boot crashes in a row, wake word
        // and overlay auto-starts stay OFF until the user exits safe
        // mode from the banner JarvisApp shows.
        // Plan #25: the Vosk model status check walks the model dir on
        // disk — moved off the main thread so it never delays first
        // paint; the wake restart itself hops back via the service API.
        if (settings.wakeAlwaysOn && !safeMode) {
            val appContext = applicationContext
            val wakeOn = settings.wakeAlwaysOn
            Thread {
                if (wakeOn &&
                    VoskModelManager.status(appContext) ==
                    VoskModelManager.ModelStatus.READY
                ) {
                    runCatching { WakeWordControl.start(appContext) }
                }
            }.start()
        }
        // Plan #102: surviving 20 s of runtime clears the boot-crash
        // streak (a later crash is a normal crash, not a boot loop).
        android.os.Handler(mainLooper).postDelayed(
            { CrashLogger.markBootSettled(applicationContext) },
            20_000L
        )
        com.nshd.jarvis2.core.AppLog.i(
            "App",
            "Cold start setup took ${System.currentTimeMillis() - createStartMs}ms" +
                if (safeMode) " (SAFE MODE)" else ""
        )
        setContent {
            JarvisApp(
                settings = settings,
                onSettingsChange = { updated ->
                    // Plan #78: a manual persona pick while the
                    // schedule is ON is a one-day override, not a
                    // rewrite of the schedule's base choice (manual
                    // override wins until tomorrow).
                    var effective = updated
                    if (updated.personaScheduleEnabled &&
                        updated.persona != settings.persona &&
                        updated.personaOverride == settings.personaOverride
                    ) {
                        effective = updated.copy(
                            persona = settings.persona,
                            personaOverride = updated.persona,
                            personaOverrideDay =
                                com.nshd.jarvis2.core.BrainStore.todayKey()
                        )
                    }
                    val themeChanged = effective.theme != settings.theme ||
                        effective.customBgHex != settings.customBgHex
                    // v2.0.1: overlay appearance (avatar opacity, panel
                    // size, panel background opacity) applies through
                    // the same live re-start: the service's
                    // onStartCommand re-reads them and repaints.
                    val overlayLookChanged =
                        effective.overlayAvatarOpacity != settings.overlayAvatarOpacity ||
                            effective.panelSizePercent != settings.panelSizePercent ||
                            effective.panelBgOpacity != settings.panelBgOpacity
                    settings = effective
                    saveSettings(effective)
                    // D3: a running overlay re-reads its HUD palette on
                    // start, so a theme change re-skins the HUD live.
                    if ((themeChanged || overlayLookChanged) &&
                        FloatingOverlayService.isRunning()
                    ) {
                        runCatching { FloatingOverlayService.start(this) }
                    }
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        com.nshd.jarvis2.core.AppLog.i("App", "MainActivity onStart")
    }

    override fun onStop() {
        com.nshd.jarvis2.core.AppLog.i("App", "MainActivity onStop")
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }

    /** The floating overlay bubble asks for the Voice screen via this extra. */
    private fun handleNavIntent(intent: Intent?) {
        if (intent == null) return
        // v2.0.0 (#113): a headset / Bluetooth media-button press
        // (routed here by WakeWordService's media session) toggles
        // the voice session — JarvisApp consumes the request.
        if (intent.action == ACTION_TOGGLE_VOICE) {
            AppNavRequests.requestVoiceToggle()
            intent.action = null
        }
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
        // v2.0.0 (#51): a scheduled message came due — JarvisApp
        // shows the draft for review; the send re-enters the gated
        // flow (recipient + text confirmed again at fire time).
        val scheduledId = intent.getLongExtra(EXTRA_SCHEDULED_ID, -1L)
        if (scheduledId > 0) {
            AppNavRequests.requestScheduled(scheduledId)
            intent.removeExtra(EXTRA_SCHEDULED_ID)
        }
        // v1.9.0: launcher static shortcuts + the QS tile carry their
        // target screen in this extra ("voice" / "chat" / "handsfree"
        // / "selftest").
        intent.getStringExtra(EXTRA_OPEN_SCREEN)?.let { target ->
            if (target == "voice") {
                AppNavRequests.requestVoice()
            } else {
                AppNavRequests.requestScreen(target)
            }
            intent.removeExtra(EXTRA_OPEN_SCREEN)
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
            biometricsEnrolledDate = prefs.getString(KEY_BIO_DATE, "") ?: "",
            ttsRate = prefs.getFloat(KEY_TTS_RATE, 1.18f),
            ttsPitch = prefs.getFloat(KEY_TTS_PITCH, 1.22f),
            bargeInSensitivity = prefs.getString(KEY_BARGE_IN, "NORMAL") ?: "NORMAL",
            ttsVoiceName = prefs.getString(KEY_TTS_VOICE, "") ?: "",
            chatFontSize = prefs.getString(KEY_CHAT_FONT, "M") ?: "M",
            reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
            panelHaptics = prefs.getBoolean(KEY_PANEL_HAPTICS, true),
            lastSeenVersionCode = prefs.getInt(KEY_LAST_SEEN_VC, 0),
            customPersonaName = prefs.getString(KEY_CUSTOM_PERSONA_NAME, "") ?: "",
            customPersonaPrompt = prefs.getString(KEY_CUSTOM_PERSONA_PROMPT, "") ?: "",
            dndRespect = prefs.getBoolean(KEY_DND_RESPECT, true),
            pttMode = prefs.getBoolean(KEY_PTT_MODE, false),
            wakeSensitivity = prefs.getString(KEY_WAKE_SENSITIVITY, "NORMAL") ?: "NORMAL",
            personaScheduleEnabled = prefs.getBoolean(KEY_PERSONA_SCHEDULE, false),
            personaDayPersona = prefs.getString(KEY_PERSONA_DAY, "Jarvis Pro") ?: "Jarvis Pro",
            personaNightPersona = prefs.getString(KEY_PERSONA_NIGHT, "Girlfriend Mode")
                ?: "Girlfriend Mode",
            personaDayStartHour = prefs.getInt(KEY_PERSONA_DAY_START, 9),
            personaDayEndHour = prefs.getInt(KEY_PERSONA_DAY_END, 18),
            personaOverride = prefs.getString(KEY_PERSONA_OVERRIDE, "") ?: "",
            personaOverrideDay = prefs.getString(KEY_PERSONA_OVERRIDE_DAY, "") ?: "",
            customWakePhrase = prefs.getString(KEY_CUSTOM_WAKE_PHRASE, "") ?: "",
            overlayAvatarOpacity = prefs.getFloat(KEY_OVERLAY_AVATAR_OPACITY, 1.0f)
                .coerceIn(0.25f, 1.0f),
            panelSizePercent = prefs.getInt(KEY_PANEL_SIZE_PERCENT, 100)
                .coerceIn(80, 120),
            panelBgOpacity = prefs.getFloat(KEY_PANEL_BG_OPACITY, 0.97f)
                .coerceIn(0.35f, 1.0f)
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
            .putFloat(KEY_TTS_RATE, s.ttsRate)
            .putFloat(KEY_TTS_PITCH, s.ttsPitch)
            .putString(KEY_BARGE_IN, s.bargeInSensitivity)
            .putString(KEY_TTS_VOICE, s.ttsVoiceName)
            .putString(KEY_CHAT_FONT, s.chatFontSize)
            .putBoolean(KEY_REDUCE_MOTION, s.reduceMotion)
            .putBoolean(KEY_PANEL_HAPTICS, s.panelHaptics)
            .putInt(KEY_LAST_SEEN_VC, s.lastSeenVersionCode)
            .putString(KEY_CUSTOM_PERSONA_NAME, s.customPersonaName)
            .putString(KEY_CUSTOM_PERSONA_PROMPT, s.customPersonaPrompt)
            .putBoolean(KEY_DND_RESPECT, s.dndRespect)
            .putBoolean(KEY_PTT_MODE, s.pttMode)
            .putString(KEY_WAKE_SENSITIVITY, s.wakeSensitivity)
            .putBoolean(KEY_PERSONA_SCHEDULE, s.personaScheduleEnabled)
            .putString(KEY_PERSONA_DAY, s.personaDayPersona)
            .putString(KEY_PERSONA_NIGHT, s.personaNightPersona)
            .putInt(KEY_PERSONA_DAY_START, s.personaDayStartHour)
            .putInt(KEY_PERSONA_DAY_END, s.personaDayEndHour)
            .putString(KEY_PERSONA_OVERRIDE, s.personaOverride)
            .putString(KEY_PERSONA_OVERRIDE_DAY, s.personaOverrideDay)
            .putString(KEY_CUSTOM_WAKE_PHRASE, s.customWakePhrase)
            .putFloat(KEY_OVERLAY_AVATAR_OPACITY, s.overlayAvatarOpacity)
            .putInt(KEY_PANEL_SIZE_PERCENT, s.panelSizePercent)
            .putFloat(KEY_PANEL_BG_OPACITY, s.panelBgOpacity)
            .apply()
    }

    companion object {
        /** v2.0.0 (#113): headset media-button toggle (WakeWordService). */
        const val ACTION_TOGGLE_VOICE = "com.nshd.jarvis2.action.TOGGLE_VOICE"
        /** v2.0.0 (#51): a due scheduled message's id (notification tap). */
        const val EXTRA_SCHEDULED_ID = "scheduled_id"
        const val EXTRA_OPEN_VOICE = "open_voice"
        /** v1.9.0: shortcut/tile target screen (see handleNavIntent). */
        const val EXTRA_OPEN_SCREEN = "open_screen"
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
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_BARGE_IN = "barge_in_sensitivity"
        private const val KEY_TTS_VOICE = "tts_voice_name"
        private const val KEY_CHAT_FONT = "chat_font_size"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_PANEL_HAPTICS = "panel_haptics"
        private const val KEY_LAST_SEEN_VC = "last_seen_version_code"
        private const val KEY_CUSTOM_PERSONA_NAME = "custom_persona_name"
        private const val KEY_CUSTOM_PERSONA_PROMPT = "custom_persona_prompt"
        private const val KEY_DND_RESPECT = "dnd_respect"
        private const val KEY_PTT_MODE = "ptt_mode"
        private const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        private const val KEY_PERSONA_SCHEDULE = "persona_schedule_enabled"
        private const val KEY_PERSONA_DAY = "persona_day_persona"
        private const val KEY_PERSONA_NIGHT = "persona_night_persona"
        private const val KEY_PERSONA_DAY_START = "persona_day_start_hour"
        private const val KEY_PERSONA_DAY_END = "persona_day_end_hour"
        private const val KEY_PERSONA_OVERRIDE = "persona_override"
        private const val KEY_PERSONA_OVERRIDE_DAY = "persona_override_day"
        private const val KEY_CUSTOM_WAKE_PHRASE = "custom_wake_phrase"
        private const val KEY_OVERLAY_AVATAR_OPACITY = "overlay_avatar_opacity"
        private const val KEY_PANEL_SIZE_PERCENT = "panel_size_percent"
        private const val KEY_PANEL_BG_OPACITY = "panel_bg_opacity"
    }
}

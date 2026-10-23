package com.nshd.jarvis2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.nshd.jarvis2.MainActivity
import com.nshd.jarvis2.voice.ChimePlayer
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import com.nshd.jarvis2.voice.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Always-on wake word (Level 6): a microphone foreground service running
 * the Vosk offline recognizer with a grammar constrained to the wake
 * phrases, so "Hey Jarvis" works with the screen off, locked, and with
 * no network at all.
 *
 * Mic arbitration: a voice session owns the microphone — the service
 * pauses recognition while one runs (see [WakeWordControl]) and resumes
 * after. Battery-aware: recognition auto-pauses at ≤20% while not
 * charging and resumes when charging or above 30%.
 */
class WakeWordService : Service(), RecognitionListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    @Volatile
    private var engineReady = false

    @Volatile
    private var engineStarting = false

    @Volatile
    private var listeningNow = false

    /** Pause reasons; recognition runs only when this set is empty. Main-thread only. */
    private val pauseReasons = mutableSetOf<String>()

    @Volatile
    private var lastWakeAtMs = 0L

    private var batteryReceiver: BroadcastReceiver? = null

    /** Plan #113 (v2.0.0): headset / Bluetooth media-button session. */
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val chime = ChimePlayer()

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // F5: AppLog init is idempotent — the service may be the first
        // entry point in the process.
        com.nshd.jarvis2.core.AppLog.init(this)
        com.nshd.jarvis2.core.AppLog.i("Wake", "wake-word service created")
        // F4.1: crash capture (idempotent — whichever entry point runs
        // first in the process installs the handler).
        com.nshd.jarvis2.CrashLogger.install(this)
        running = true
        setupMediaSession()
        createNotificationChannel()
        startForegroundCompat("Starting wake-word listener…")
        WakeWordControl.registerSessionHook { active ->
            mainHandler.post {
                if (active) {
                    pauseReasons += REASON_SESSION
                } else {
                    pauseReasons -= REASON_SESSION
                    pauseReasons -= REASON_WAKE_TRANSIENT
                }
                applyEngineState()
            }
        }
        registerBatteryReceiver()
        WakeState.set(
            WakeStatus(enabled = true, listening = false, detail = "Loading offline model…")
        )
        startEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> mainHandler.post {
                pauseReasons += REASON_MANUAL
                applyEngineState()
            }
            ACTION_RESUME -> mainHandler.post {
                pauseReasons -= REASON_MANUAL
                applyEngineState()
            }
            else -> Unit // ACTION_START / sticky restart: engine handled in onCreate
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        com.nshd.jarvis2.core.AppLog.i("Wake", "wake-word service destroyed")
        running = false
        WakeWordControl.setWakeMicHeld(false)
        WakeWordControl.clearSessionHook()
        runCatching { mediaSession?.release() }
        mediaSession = null
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        releaseWakeLock()
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
        scope.cancel()
        WakeState.set(WakeStatus(enabled = false, listening = false, detail = "Off"))
        super.onDestroy()
    }

    // ── Engine ─────────────────────────────────────────────────────────

    /** Load the Vosk model + recognizer off the main thread (JNI load is slow). */
    private fun startEngine() {
        if (engineStarting || engineReady) return
        engineStarting = true
        scope.launch {
            try {
                // F4.1 Vosk integrity gate: a PARTIAL model directory
                // (interrupted download — the user's was seen at 1%)
                // must never reach the native Model constructor: Vosk's
                // JNI load can abort the whole process on a truncated
                // model, which no Java try/catch can intercept. Verify
                // completeness first; if it fails, delete the partial
                // tree and ask for a re-download instead of crashing.
                if (!VoskModelManager.isModelComplete(this@WakeWordService)) {
                    com.nshd.jarvis2.CrashLogger.logNonFatal(
                        "wake",
                        "Vosk model directory incomplete — deleting partial download, " +
                            "refusing to construct Model"
                    )
                    VoskModelManager.deleteModel(this@WakeWordService)
                    WakeState.set(
                        WakeStatus(
                            enabled = true,
                            listening = false,
                            detail = "Wake model incomplete — re-download in Settings"
                        )
                    )
                    updateNotification("Wake word model incomplete — open Jarvis to re-download")
                    return@launch
                }
                val loadedModel = Model(VoskModelManager.modelPath(this@WakeWordService))
                // Plan #10 (v2.0.0): his own wake phrase joins the
                // grammar when set. If the phrase breaks grammar
                // construction (a word outside the small model's
                // vocabulary can do that), fall back to the built-in
                // grammar — the wake word must never die over it.
                val customPhrase = customWakePhrase()
                val rec = if (customPhrase.isNotBlank()) {
                    try {
                        Recognizer(
                            loadedModel, SAMPLE_RATE, grammarWith(customPhrase)
                        )
                    } catch (e: Exception) {
                        com.nshd.jarvis2.core.AppLog.w(
                            "Wake",
                            "custom wake phrase grammar failed " +
                                "(${e.message}) — using built-in phrases"
                        )
                        Recognizer(loadedModel, SAMPLE_RATE, GRAMMAR)
                    }
                } else {
                    Recognizer(loadedModel, SAMPLE_RATE, GRAMMAR)
                }
                val speech = SpeechService(rec, SAMPLE_RATE)
                model = loadedModel
                recognizer = rec
                speechService = speech
                engineReady = true
                mainHandler.post { applyEngineState() }
            } catch (e: Exception) {
                WakeState.set(
                    WakeStatus(
                        enabled = true,
                        listening = false,
                        detail = "Wake engine failed: ${e.message ?: "model unavailable"}"
                    )
                )
                updateNotification("Wake word unavailable — open Jarvis to fix")
            } finally {
                engineStarting = false
            }
        }
    }

    /**
     * Single funnel (main thread) that starts/stops recognition to match
     * the pause-reason set and refreshes status + notification.
     */
    private fun applyEngineState() {
        if (!engineReady) return
        val shouldListen = pauseReasons.isEmpty()
        val batteryPaused = REASON_BATTERY in pauseReasons
        if (shouldListen && !listeningNow) {
            try {
                speechService?.startListening(this@WakeWordService)
                listeningNow = true
                // F4.1: publish the real mic state for the arbiter.
                WakeWordControl.setWakeMicHeld(true)
            } catch (e: Exception) {
                WakeWordControl.setWakeMicHeld(false)
                WakeState.set(
                    WakeStatus(
                        enabled = true,
                        listening = false,
                        batteryPaused = batteryPaused,
                        detail = "Microphone busy — will retry when free"
                    )
                )
                updateNotification("Wake word paused — microphone busy")
                return
            }
        } else if (!shouldListen && listeningNow) {
            runCatching { speechService?.stop() }
            listeningNow = false
            WakeWordControl.setWakeMicHeld(false)
        }
        val detail = when {
            listeningNow -> "Listening for \"Hey Jarvis\""
            REASON_BATTERY in pauseReasons -> "Paused — battery low (charging resumes it)"
            REASON_SESSION in pauseReasons -> "Paused — voice session is using the mic"
            REASON_MANUAL in pauseReasons -> "Paused"
            else -> "Paused"
        }
        WakeState.set(
            WakeStatus(
                enabled = true,
                listening = listeningNow,
                batteryPaused = batteryPaused,
                detail = detail
            )
        )
        updateNotification(
            when {
                listeningNow -> "Listening for \"Hey Jarvis\""
                batteryPaused -> "Wake word paused — battery saver"
                else -> "Wake word paused"
            }
        )
    }

    // ── RecognitionListener (Vosk SpeechService callbacks) ─────────────

    override fun onPartialResult(hypothesis: String?) {
        // Partial hypotheses are ignored — only final results can wake.
    }

    override fun onResult(hypothesis: String?) {
        checkWakePhrase(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        checkWakePhrase(hypothesis)
    }

    override fun onError(exception: Exception?) {
        // A recognizer error must never crash the service: stop the loop,
        // report, and let a later resume/restart rebuild it.
        listeningNow = false
        WakeWordControl.setWakeMicHeld(false)
        if (exception != null) {
            com.nshd.jarvis2.CrashLogger.logNonFatal("wake", exception)
        }
        WakeState.set(
            WakeStatus(
                enabled = true,
                listening = false,
                batteryPaused = REASON_BATTERY in pauseReasons,
                detail = "Recognizer error: ${exception?.message ?: "unknown"}"
            )
        )
        updateNotification("Wake word error — open Jarvis")
    }

    override fun onTimeout() {
        // Grammar recognizer sessions don't time out in practice; ignore.
    }

    /**
     * Plan #10 (v2.0.0): the user's custom wake phrase from settings
     * (sanitized: lowercase letters/spaces, 2–5 words — the small
     * offline model's vocabulary is limited, and the Settings UI
     * says to prefer common English words).
     */
    private fun customWakePhrase(): String {
        val raw = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
            .getString("custom_wake_phrase", "") ?: ""
        val clean = raw.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = clean.split(" ").filter { it.isNotBlank() }
        return if (words.size in 2..5) clean else ""
    }

    /** Grammar JSON: the built-in phrases + [phrase] + [unk]. */
    private fun grammarWith(phrase: String): String =
        "[\"hey jarvis\", \"hey jarves\", \"jarvis\", \"$phrase\", \"[unk]\"]"

    private fun checkWakePhrase(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val json = try {
            JSONObject(hypothesis)
        } catch (e: Exception) {
            return
        }
        val text = json.optString("text").trim().lowercase()
        val customPhrase = customWakePhrase()
        val heardCustom = customPhrase.isNotBlank() &&
            (text == customPhrase || text.contains(customPhrase))
        if (text.contains("hey jarvis") || text == "jarvis" || heardCustom) {
            val now = System.currentTimeMillis()
            if (now - lastWakeAtMs < WAKE_DEBOUNCE_MS) return
            // v1.9.0 (#9): sensitivity gate — the recognizer's word
            // confidences for the wake words must clear the threshold
            // for the user's sensitivity setting. Below it, the phrase
            // is logged as a (visible) near-miss instead of waking.
            val confidence = wakeConfidence(json)
            val threshold = wakeThreshold()
            if (confidence < threshold) {
                com.nshd.jarvis2.core.WakeEvents.record(
                    this,
                    "Heard \"hey jarvis\" at ${(confidence * 100).toInt()}% " +
                        "confidence — below the $threshold threshold, ignored"
                )
                com.nshd.jarvis2.core.AppLog.d(
                    "Wake",
                    "wake phrase below sensitivity threshold " +
                        "(conf=$confidence < $threshold)"
                )
                return
            }
            lastWakeAtMs = now
            com.nshd.jarvis2.core.AppLog.i("Wake", "wake phrase detected")
            com.nshd.jarvis2.core.WakeEvents.record(
                this,
                "Wake fired — \"hey jarvis\" at ${(confidence * 100).toInt()}% confidence"
            )
            mainHandler.post { onWake() }
        }
    }

    /** v1.9.0 (#9): mean confidence of the wake words in a Vosk result. */
    private fun wakeConfidence(json: JSONObject): Float {
        val words = json.optJSONArray("result") ?: return 0.75f
        var sum = 0f
        var count = 0
        for (i in 0 until words.length()) {
            val entry = words.optJSONObject(i) ?: continue
            val word = entry.optString("word").lowercase()
            if (word == "hey" || word == "jarvis") {
                sum += entry.optDouble("conf", 0.75).toFloat()
                count++
            }
        }
        return if (count == 0) 0.75f else sum / count
    }

    /** v1.9.0 (#9): confidence threshold for the sensitivity setting. */
    private fun wakeThreshold(): Float {
        val setting = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
            .getString("wake_sensitivity", "NORMAL") ?: "NORMAL"
        return when (setting) {
            "LOW" -> 0.85f
            "HIGH" -> 0.0f
            else -> 0.55f
        }
    }

    // ── Wake ───────────────────────────────────────────────────────────

    /**
     * F3 background wake: hearing the wake phrase NEVER opens the app.
     * The earcon plays, the mic is handed to a background voice session
     * via [VoiceSessionManager.startFromWake], and the user keeps doing
     * whatever they were doing. Only a failure (mic permission, engine
     * error) surfaces anything — a notification whose tap may open the
     * app so the user can fix permissions.
     */
    private fun onWake() {
        // Release the mic immediately so the voice session can take it.
        runCatching { speechService?.stop() }
        listeningNow = false
        WakeWordControl.setWakeMicHeld(false)
        pauseReasons += REASON_WAKE_TRANSIENT
        acquireWakeLockBriefly()
        chime.playEarcon()
        WakeState.set(
            WakeStatus(
                enabled = true,
                listening = false,
                batteryPaused = REASON_BATTERY in pauseReasons,
                detail = "\"Hey Jarvis\" heard — listening in background"
            )
        )
        val started = try {
            VoiceSessionManager.startFromWake(applicationContext)
            true
        } catch (e: Exception) {
            false
        }
        if (started) {
            // v2.0.0 (#115): on-wake routines (e.g. a morning greeting
            // routine) fire alongside the session start.
            com.nshd.jarvis2.core.RoutineRunner
                .maybeRunOnWake(applicationContext)
        }
        if (!started) {
            pauseReasons -= REASON_WAKE_TRANSIENT
            applyEngineState()
            postWakeFailureNotification()
            return
        }
        // Early failure check: a session that lands on ERROR shortly
        // after wake (mic permission, no recognizer…) notifies the user
        // instead of failing silently in the background.
        mainHandler.postDelayed({
            if (VoiceSessionManager.uiState.value.status == VoiceStatus.ERROR) {
                postWakeFailureNotification()
            }
        }, WAKE_FAILURE_CHECK_MS)
        // Watchdog: if no voice session actually starts (user backs out,
        // permission denied…), lift the transient pause, listen again,
        // and tell the user via notification if the session errored.
        mainHandler.postDelayed({
            if (REASON_WAKE_TRANSIENT in pauseReasons &&
                REASON_SESSION !in pauseReasons
            ) {
                val status = VoiceSessionManager.uiState.value.status
                if (status == VoiceStatus.IDLE || status == VoiceStatus.ERROR) {
                    pauseReasons -= REASON_WAKE_TRANSIENT
                    applyEngineState()
                    if (status == VoiceStatus.ERROR) postWakeFailureNotification()
                }
            }
        }, WAKE_WATCHDOG_MS)
    }

    /** F3: the ONLY user-visible fallback when a background wake fails. */
    private fun postWakeFailureNotification() {
        val openApp = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(
                "Jarvis couldn't start listening — open the app to check permissions"
            )
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(WAKE_FAILURE_NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLockBriefly() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            releaseWakeLock()
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "jarvis:wake-word"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_MS)
            }
            // Extra safety: release early once the activity is up.
            mainHandler.postDelayed({ releaseWakeLock() }, WAKELOCK_MS)
        } catch (e: Exception) {
            // WakeLock is an optimisation — never fail the wake over it.
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock != null) {
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    // ── Headset / Bluetooth media button (plan #113) ────────────────

    /**
     * An ACTIVE media session makes this service the target of
     * headset and Bluetooth media buttons: a play/pause press starts
     * (or stops) a voice session, exactly like saying the wake word.
     * The session carries no media metadata and no audio focus games —
     * it exists purely as the button target, and only while the wake
     * service itself is running.
     */
    private fun setupMediaSession() {
        runCatching {
            val session = MediaSession(this, "jarvis_headset")
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = onHeadsetButton()
                override fun onPause() = onHeadsetButton()
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = mediaButtonIntent
                        .getParcelableExtra<android.view.KeyEvent>(
                            Intent.EXTRA_KEY_EVENT
                        )
                    if (keyEvent?.action == android.view.KeyEvent.ACTION_DOWN) {
                        onHeadsetButton()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE
                    )
                    .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                    .build()
            )
            session.isActive = true
            mediaSession = session
            com.nshd.jarvis2.core.AppLog.i(
                "Wake", "headset media-button session active (plan #113)"
            )
        }.onFailure {
            com.nshd.jarvis2.core.AppLog.w(
                "Wake", "media session setup failed: ${it.message}"
            )
        }
    }

    /** Headset press: toggle the voice session, like the wake word. */
    private fun onHeadsetButton() {
        com.nshd.jarvis2.core.AppLog.i("Wake", "headset button pressed")
        // Route through the same start/stop entry the overlay uses:
        // the main activity owns the session toggle + wake-hook runs.
        val intent = Intent(this, com.nshd.jarvis2.MainActivity::class.java)
            .setAction(com.nshd.jarvis2.MainActivity.ACTION_TOGGLE_VOICE)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        runCatching { startActivity(intent) }
    }

    // ── Battery awareness ──────────────────────────────────────────────

    private fun registerBatteryReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level < 0 || scale <= 0) return
                val percent = level * 100 / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                when {
                    !charging && percent <= BATTERY_PAUSE_PERCENT -> {
                        if (pauseReasons.add(REASON_BATTERY)) {
                            com.nshd.jarvis2.core.AppLog.w(
                                "Wake", "paused — battery at $percent%"
                            )
                            applyEngineState()
                        }
                    }
                    charging || percent > BATTERY_RESUME_PERCENT -> {
                        if (pauseReasons.remove(REASON_BATTERY)) {
                            com.nshd.jarvis2.core.AppLog.i(
                                "Wake", "battery pause lifted ($percent%)"
                            )
                            applyEngineState()
                        }
                    }
                    // 21–30% and not charging: hysteresis — keep current state.
                }
            }
        }
        batteryReceiver = receiver
        // Sticky broadcast: registering also delivers the current battery
        // state immediately, so a low-battery start pauses right away.
        runCatching {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    // ── Notification / foreground ──────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis wake word",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis wake word")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(0, "Stop", stopIntent).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 14+ restricts background microphone-FGS starts. Our
            // starts come from in-app user action, but on OEM builds that
            // still refuse, surface it instead of crashing.
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                WakeState.set(
                    WakeStatus(
                        enabled = true,
                        listening = false,
                        detail = "Could not start (background mic restriction) — open Jarvis and tap Start"
                    )
                )
                stopSelf()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.nshd.jarvis2.wake.START"
        const val ACTION_STOP = "com.nshd.jarvis2.wake.STOP"
        const val ACTION_PAUSE = "com.nshd.jarvis2.wake.PAUSE"
        const val ACTION_RESUME = "com.nshd.jarvis2.wake.RESUME"

        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 1003
        private const val WAKE_FAILURE_NOTIFICATION_ID = 1004
        private const val SAMPLE_RATE = 16000f
        /** Grammar-constrained phrases: the recognizer can only "hear" these. */
        private const val GRAMMAR =
            "[\"hey jarvis\", \"hey jarves\", \"jarvis\", \"[unk]\"]"
        private const val REASON_MANUAL = "manual"
        private const val REASON_SESSION = "session"
        private const val REASON_BATTERY = "battery"
        private const val REASON_WAKE_TRANSIENT = "wake-transient"
        private const val BATTERY_PAUSE_PERCENT = 20
        private const val BATTERY_RESUME_PERCENT = 30
        private const val WAKELOCK_MS = 5_000L
        private const val WAKE_DEBOUNCE_MS = 3_000L
        private const val WAKE_WATCHDOG_MS = 12_000L
        private const val WAKE_FAILURE_CHECK_MS = 2_500L

        fun isRunning(): Boolean = running

        @Volatile
        private var running = false
    }
}

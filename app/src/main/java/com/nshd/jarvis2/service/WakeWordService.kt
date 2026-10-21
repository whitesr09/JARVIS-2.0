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
    private var wakeLock: PowerManager.WakeLock? = null
    private val chime = ChimePlayer()

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        running = true
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
        running = false
        WakeWordControl.clearSessionHook()
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
                val loadedModel = Model(VoskModelManager.modelPath(this@WakeWordService))
                val rec = Recognizer(loadedModel, SAMPLE_RATE, GRAMMAR)
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
            } catch (e: Exception) {
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

    private fun checkWakePhrase(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = try {
            JSONObject(hypothesis).optString("text").trim().lowercase()
        } catch (e: Exception) {
            return
        }
        if (text.contains("hey jarvis") || text == "jarvis") {
            val now = System.currentTimeMillis()
            if (now - lastWakeAtMs < WAKE_DEBOUNCE_MS) return
            lastWakeAtMs = now
            mainHandler.post { onWake() }
        }
    }

    // ── Wake ───────────────────────────────────────────────────────────

    private fun onWake() {
        // Release the mic immediately so the voice session can take it.
        runCatching { speechService?.stop() }
        listeningNow = false
        pauseReasons += REASON_WAKE_TRANSIENT
        acquireWakeLockBriefly()
        chime.playEarcon()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_OPEN_VOICE, true)
            putExtra(MainActivity.EXTRA_FROM_WAKE, true)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // OEM background-launch block: drop the transient pause so the
            // listener recovers instead of waiting for a session that
            // will never start.
            pauseReasons -= REASON_WAKE_TRANSIENT
            applyEngineState()
            return
        }
        WakeState.set(
            WakeStatus(
                enabled = true,
                listening = false,
                batteryPaused = REASON_BATTERY in pauseReasons,
                detail = "\"Hey Jarvis\" heard — opening voice"
            )
        )
        // Watchdog: if no voice session actually starts (user backs out,
        // permission denied…), lift the transient pause and listen again.
        mainHandler.postDelayed({
            if (REASON_WAKE_TRANSIENT in pauseReasons &&
                REASON_SESSION !in pauseReasons &&
                VoiceSessionManager.uiState.value.status == VoiceStatus.IDLE
            ) {
                pauseReasons -= REASON_WAKE_TRANSIENT
                applyEngineState()
            }
        }, WAKE_WATCHDOG_MS)
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
                        if (pauseReasons.add(REASON_BATTERY)) applyEngineState()
                    }
                    charging || percent > BATTERY_RESUME_PERCENT -> {
                        if (pauseReasons.remove(REASON_BATTERY)) applyEngineState()
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

        fun isRunning(): Boolean = running

        @Volatile
        private var running = false
    }
}

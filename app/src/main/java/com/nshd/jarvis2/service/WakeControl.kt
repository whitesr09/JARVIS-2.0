package com.nshd.jarvis2.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live status of the always-on wake-word listener (Level 6), published
 * by [WakeWordService] and rendered by the Settings wake-word card.
 */
data class WakeStatus(
    /** The foreground service is running. */
    val enabled: Boolean = false,
    /** The recognizer is actively listening right now. */
    val listening: Boolean = false,
    /** Auto-paused by the battery saver (low battery, not charging). */
    val batteryPaused: Boolean = false,
    /** One-line human status for UI + notification. */
    val detail: String = "Off"
)

object WakeState {

    private val _status = MutableStateFlow(WakeStatus())
    val status: StateFlow<WakeStatus> = _status.asStateFlow()

    fun set(status: WakeStatus) {
        _status.value = status
    }
}

/**
 * Control surface for [WakeWordService]: start/stop intents plus the mic
 * arbitration hook used by the voice session.
 *
 * Arbitration model: Jarvis has ONE microphone consumer at a time. When
 * a voice session starts, [setSessionActive] `true` tells the wake
 * service to release the mic (recognition paused, FGS stays alive); when
 * the session ends, `false` lets it resume — provided wake mode is still
 * enabled and no other pause reason applies. The service registers its
 * hook in onCreate and clears it in onDestroy; calls with no service
 * running are no-ops.
 */
object WakeWordControl {

    @Volatile
    private var sessionHook: ((Boolean) -> Unit)? = null

    fun registerSessionHook(hook: (Boolean) -> Unit) {
        sessionHook = hook
    }

    fun clearSessionHook() {
        sessionHook = null
    }

    /** Called by VoiceSessionManager on session start (true) / end (false). */
    fun setSessionActive(active: Boolean) {
        try {
            sessionHook?.invoke(active)
        } catch (e: Exception) {
            // Arbitration is best-effort: never break the voice session.
        }
    }

    fun start(context: Context) {
        val intent = Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_STOP
        }
        // stopService is the reliable path; the ACTION_STOP start intent
        // also lets a running instance shut its engine down gracefully.
        runCatching { context.startService(intent) }
        context.stopService(Intent(context, WakeWordService::class.java))
    }
}

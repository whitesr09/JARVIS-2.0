package com.nshd.jarvis2.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-shot navigation requests from outside the Compose tree (overlay
 * bubble taps, notification actions…). [MainActivity] translates launch
 * intents into these flags; [JarvisApp] consumes each flag exactly once.
 */
object AppNavRequests {

    private val _voiceRequested = MutableStateFlow(false)
    val voiceRequested: StateFlow<Boolean> = _voiceRequested.asStateFlow()

    fun requestVoice() {
        _voiceRequested.value = true
    }

    /** Returns true (and clears the flag) when a voice request is pending. */
    fun consumeVoiceRequest(): Boolean {
        if (!_voiceRequested.value) return false
        _voiceRequested.value = false
        return true
    }

    // ── v1.9.0 (parent extra): launcher static-shortcut targets ──────
    // "chat" / "handsfree" (the Hands-Free screen also hosts the
    // self-test card, so the Self-test shortcut lands there too).
    private val _screenRequested = MutableStateFlow<String?>(null)
    val screenRequested: StateFlow<String?> = _screenRequested.asStateFlow()

    fun requestScreen(target: String) {
        _screenRequested.value = target
    }

    /** Returns (and clears) the pending shortcut target, if any. */
    fun consumeScreenRequest(): String? {
        val target = _screenRequested.value
        _screenRequested.value = null
        return target
    }
}

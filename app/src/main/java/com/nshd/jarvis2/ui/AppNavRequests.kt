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
}

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

    // ── v2.0.0 (#113): headset / Bluetooth media-button toggle ──────
    private val _voiceToggleRequested = MutableStateFlow(false)
    val voiceToggleRequested: StateFlow<Boolean> = _voiceToggleRequested.asStateFlow()

    fun requestVoiceToggle() {
        _voiceToggleRequested.value = true
    }

    /** Returns true (and clears the flag) when a toggle is pending. */
    fun consumeVoiceToggleRequest(): Boolean {
        if (!_voiceToggleRequested.value) return false
        _voiceToggleRequested.value = false
        return true
    }

    // ── v2.0.0 (#51): a scheduled message came due ───────────────────
    private val _scheduledRequested = MutableStateFlow<Long?>(null)
    val scheduledRequested: StateFlow<Long?> = _scheduledRequested.asStateFlow()

    fun requestScheduled(id: Long) {
        _scheduledRequested.value = id
    }

    /** Returns (and clears) the due scheduled message id, if any. */
    fun consumeScheduledRequest(): Long? {
        val id = _scheduledRequested.value
        _scheduledRequested.value = null
        return id
    }

    // ── v2.0.0 (#51): inject a command into Chat as if typed ─────────
    // Used by the due-draft dialog's "Send now": the text re-enters
    // the normal pipeline, so the send is gated (recipient + full
    // text) exactly like a freshly spoken command.
    private val _chatTextRequested = MutableStateFlow<String?>(null)
    val chatTextRequested: StateFlow<String?> = _chatTextRequested.asStateFlow()

    fun requestChatText(text: String) {
        _chatTextRequested.value = text
    }

    /** Returns (and clears) the pending chat text, if any. */
    fun consumeChatTextRequest(): String? {
        val text = _chatTextRequested.value
        _chatTextRequested.value = null
        return text
    }
}

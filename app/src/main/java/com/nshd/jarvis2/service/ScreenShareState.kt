package com.nshd.jarvis2.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state for the MediaProjection screen-share session (Level 5).
 * [ScreenShareService] publishes the freshest frame (~1 FPS, JPEG q70,
 * width ≤ 720); consumers are the vision tools (latest still) and the
 * Gemini Live session (frames forwarded as realtime video input).
 */
object ScreenShareState {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _latestJpeg = MutableStateFlow<ByteArray?>(null)
    val latestJpeg: StateFlow<ByteArray?> = _latestJpeg.asStateFlow()

    private val _latestJpegBase64 = MutableStateFlow<String?>(null)
    val latestJpegBase64: StateFlow<String?> = _latestJpegBase64.asStateFlow()

    /** Monotonic counter bumped per published frame (Live forwarder pacing). */
    private val _frameSeq = MutableStateFlow(0L)
    val frameSeq: StateFlow<Long> = _frameSeq.asStateFlow()

    fun setActive(value: Boolean) {
        _active.value = value
        if (!value) {
            _latestJpeg.value = null
            _latestJpegBase64.value = null
        }
    }

    fun publish(jpeg: ByteArray, base64: String) {
        _latestJpeg.value = jpeg
        _latestJpegBase64.value = base64
        _frameSeq.value = _frameSeq.value + 1
    }
}

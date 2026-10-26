package com.nshd.jarvis2.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.CrashLogger
import kotlin.math.sqrt

/**
 * Microphone capture for the Gemini Live uplink: VOICE_COMMUNICATION
 * source, 16 kHz mono PCM16, 640-byte (20 ms) chunks for low latency,
 * hardware AEC / NS / AGC attached on the record session when the
 * device offers them (needed so the user can barge in while Jarvis is
 * speaking).
 *
 * Chunks are delivered verbatim to [onChunk]; per-chunk RMS (0..1) goes to
 * [onAmplitude] for the orb. Muting is the caller's job (send nothing) —
 * the mic keeps running so un-muting is instant.
 */
class AudioCapture(
    private val context: Context,
    private val onChunk: (ByteArray) -> Unit,
    private val onAmplitude: (Float) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    /** @return true if capture actually started (permission + hardware OK). */
    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Microphone permission is not granted")
            return false
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            // F4.1: device refused a buffer size — fail soft, logged.
            CrashLogger.logNonFatal("audio", "getMinBufferSize returned $minBuffer")
            onError("Microphone buffer unavailable on this device")
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Generous internal buffer so the reader never starves.
                maxOf(minBuffer * 4, 32_000)
            )
        } catch (e: SecurityException) {
            CrashLogger.logNonFatal("audio", e)
            onError("Microphone permission is missing or was revoked")
            return false
        } catch (e: Exception) {
            CrashLogger.logNonFatal("audio", e)
            onError("Microphone could not be opened: ${e.message ?: "unknown error"}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            CrashLogger.logNonFatal(
                "audio",
                "AudioRecord not STATE_INITIALIZED (state=${record.state})"
            )
            runCatching { record.release() }
            onError("Microphone could not be initialized (is RECORD_AUDIO granted?)")
            return false
        }
        recorder = record
        attachEffects(record.audioSessionId)
        running = true
        try {
            record.startRecording()
        } catch (e: Exception) {
            CrashLogger.logNonFatal("audio", e)
            running = false
            releaseEffects()
            runCatching { record.release() }
            recorder = null
            onError("Microphone recording failed to start: ${e.message ?: "unknown error"}")
            return false
        }
        thread = Thread({ readLoop(record) }, "jarvis-audio-capture").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun readLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(CHUNK_BYTES)
        var consecutiveErrors = 0
        while (running) {
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                -1
            }
            if (read > 0) {
                consecutiveErrors = 0
                onAmplitude(rmsOf(buffer, read))
                onChunk(buffer.copyOf(read))
            } else if (read < 0) {
                consecutiveErrors++
                if (consecutiveErrors >= 10) {
                    running = false
                    onError("Microphone read failed repeatedly")
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        val record = recorder
        recorder = null
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        releaseEffects()
        thread?.let { runCatching { it.join(300) } }
        thread = null
        onAmplitude(0f)
    }

    private fun attachEffects(sessionId: Int) {
        // Each effect is optional hardware — failure to attach is not fatal.
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) { /* best effort */ }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) { /* best effort */ }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) { /* best effort */ }
    }

    private fun releaseEffects() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 20 ms of 16 kHz mono PCM16 — the low-latency chunk size. */
        const val CHUNK_BYTES = 640

        /** RMS of PCM16 samples in [bytes], normalized toward 0..1 (sqrt for orb visibility). */
        fun rmsOf(bytes: ByteArray, length: Int): Float {
            val samples = length / 2
            if (samples == 0) return 0f
            var sumSquares = 0.0
            var i = 0
            while (i + 1 < length) {
                val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
                val v = sample.toDouble() / 32768.0
                sumSquares += v * v
                i += 2
            }
            val rms = sqrt(sumSquares / samples)
            return sqrt(rms).toFloat().coerceIn(0f, 1f)
        }
    }
}

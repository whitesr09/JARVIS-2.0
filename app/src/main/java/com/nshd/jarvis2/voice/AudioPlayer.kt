package com.nshd.jarvis2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Playback for Gemini Live audio — pattern from Tech GPTx Part 7 (§3 of
 * the audit): AudioTrack MODE_STREAM at 24 kHz mono PCM16 (exactly what
 * the Live API sends — no resampling), USAGE_MEDIA (loudspeaker /
 * headphones, never the earpiece), and a LARGE buffer (~96,000 bytes,
 * 2 s of headroom). P7 removed the low-latency performance mode because
 * it caused underrun loops on MediaTek/Qualcomm FastMixers — we keep
 * that lesson.
 *
 * A dedicated writer thread drains an internal queue. [flush] is the
 * barge-in primitive: drop everything queued and reset the hardware
 * buffer so Jarvis stops talking the moment the server says the user
 * interrupted.
 */
class AudioPlayer(
    private val onAmplitude: (Float) -> Unit = {}
) {
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var track: AudioTrack? = null
    private var writer: Thread? = null

    @Volatile
    private var running = false

    /** Silent Execution: when true, incoming chunks are dropped, not played. */
    @Volatile
    var muted: Boolean = false

    fun start() {
        if (running) return
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(if (minBuffer > 0) minBuffer * 8 else 0, 96_000)
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return
        }
        track = audioTrack
        running = true
        runCatching { audioTrack.play() }
        writer = Thread({ writeLoop(audioTrack) }, "jarvis-audio-player").apply {
            isDaemon = true
            start()
        }
    }

    private fun writeLoop(audioTrack: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val chunk = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            if (muted) continue
            onAmplitude(AudioCapture.rmsOf(chunk, chunk.size))
            var offset = 0
            while (offset < chunk.size && running) {
                val written = try {
                    audioTrack.write(chunk, offset, chunk.size - offset)
                } catch (e: Exception) {
                    -1
                }
                if (written < 0) break
                offset += written
            }
        }
    }

    /** Queue one PCM chunk from the server. Dropped when muted or stopped. */
    fun enqueue(bytes: ByteArray) {
        if (!running || muted || bytes.isEmpty()) return
        if (!queue.offer(bytes)) {
            // Queue full — drop the oldest chunk rather than block the WS thread.
            queue.poll()
            queue.offer(bytes)
        }
    }

    /** Barge-in: stop current speech immediately (queue + hardware buffer). */
    fun flush() {
        queue.clear()
        val audioTrack = track ?: return
        try {
            audioTrack.pause()
            audioTrack.flush()
            if (running) audioTrack.play()
        } catch (e: Exception) { /* track may already be released */ }
        onAmplitude(0f)
    }

    fun stop() {
        running = false
        queue.clear()
        writer?.let {
            it.interrupt()
            runCatching { it.join(300) }
        }
        writer = null
        val audioTrack = track
        track = null
        if (audioTrack != null) {
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }
        onAmplitude(0f)
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val QUEUE_CAPACITY = 256
    }
}

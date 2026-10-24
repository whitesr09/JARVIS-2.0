package com.nshd.jarvis2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The Silent Execution finish chime (locked preference §2.7): when task
 * narration is muted, this subtle two-tone chime is the "done" signal.
 * Fully synthesized in code (880 → 1318 Hz sines with a fade envelope) —
 * no audio asset ships with the app. Also provides a soft error tone.
 */
class ChimePlayer {

    fun playSuccess() {
        playTones(listOf(Tone(880f, 0.16f), Tone(1318f, 0.20f)))
    }

    fun playError() {
        playTones(listOf(Tone(330f, 0.15f)))
    }

    private data class Tone(val frequency: Float, val seconds: Float)

    private fun playTones(tones: List<Tone>) {
        // Playback on a throwaway thread: AudioTrack write blocks.
        Thread {
            try {
                val totalSamples = tones.sumOf { (it.seconds * SAMPLE_RATE).toInt() }
                if (totalSamples <= 0) return@Thread
                val pcm = ByteArray(totalSamples * 2)
                var sampleIndex = 0
                for (tone in tones) {
                    val count = (tone.seconds * SAMPLE_RATE).toInt()
                    for (n in 0 until count) {
                        val t = n.toDouble() / SAMPLE_RATE
                        val frac = n.toDouble() / count
                        // Fade in fast, fade out over the tail — a soft "blip", never a beep blast.
                        val attack = min(1.0, frac / 0.08)
                        val release = min(1.0, (1.0 - frac) / 0.35)
                        val envelope = min(attack, release) * VOLUME
                        val value = (sin(2.0 * PI * tone.frequency * t) * envelope * 32767).toInt()
                        val out = sampleIndex * 2
                        pcm[out] = (value and 0xFF).toByte()
                        pcm[out + 1] = ((value shr 8) and 0xFF).toByte()
                        sampleIndex++
                    }
                }
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, pcm.size))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                try {
                    track.play()
                    track.write(pcm, 0, pcm.size)
                    // Let the tail actually leave the speaker before releasing.
                    Thread.sleep((totalSamples * 1000L / SAMPLE_RATE) + 120)
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                }
            } catch (e: Exception) {
                // A chime must never crash a session — stay silent on failure.
            }
        }.start()
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val VOLUME = 0.32
    }
}

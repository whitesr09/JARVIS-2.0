package com.nshd.jarvis2.voice

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricsRecorderTest {

    @Test
    fun acceptsExactlyThreeSecondPcm16MonoWaveFile() {
        val file = File.createTempFile("jarvis-biometric-valid", ".wav")
        try {
            val pcm = ByteArray(
                BiometricsRecorder.SAMPLE_RATE * 2 * BiometricsRecorder.SAMPLE_SECONDS
            )
            BiometricsRecorder.writeWav(file, pcm)

            assertTrue(BiometricsRecorder.isValidWavFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsPartialEnrollmentSample() {
        val file = File.createTempFile("jarvis-biometric-short", ".wav")
        try {
            BiometricsRecorder.writeWav(file, ByteArray(16_000))
            assertFalse(BiometricsRecorder.isValidWavFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsCorruptedWaveHeader() {
        val file = File.createTempFile("jarvis-biometric-corrupt", ".wav")
        try {
            val pcm = ByteArray(
                BiometricsRecorder.SAMPLE_RATE * 2 * BiometricsRecorder.SAMPLE_SECONDS
            )
            BiometricsRecorder.writeWav(file, pcm)
            RandomAccessFile(file, "rw").use { it.seek(0); it.writeByte(0) }

            assertFalse(BiometricsRecorder.isValidWavFile(file))
        } finally {
            file.delete()
        }
    }
}

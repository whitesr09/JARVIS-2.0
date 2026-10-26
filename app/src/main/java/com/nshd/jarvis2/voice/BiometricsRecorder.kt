package com.nshd.jarvis2.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Voice-biometrics enrollment capture (Level 6): records
 * [TOTAL_SAMPLES] short samples of the user saying the wake phrase into
 * `filesDir/biometrics/sample_N.wav` (16 kHz mono PCM16 WAV).
 *
 * HONEST SCOPE: this is enrollment only. The samples are stored on this
 * device so a later update can build speaker embeddings and verify the
 * wake phrase against them; no matching happens in this build, and the
 * Settings UI says exactly that.
 */
object BiometricsRecorder {

    const val TOTAL_SAMPLES = 3
    const val SAMPLE_SECONDS = 3
    const val SAMPLE_RATE = 16_000
    const val WAKE_PHRASE = "Hey Jarvis, wake up"

    private const val DIR_NAME = "biometrics"

    fun sampleDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun sampleFile(context: Context, index: Int): File =
        File(sampleDir(context), "sample_$index.wav")

    /** How many complete, correctly formatted three-second samples are on disk. */
    fun countSamples(context: Context): Int =
        (1..TOTAL_SAMPLES).count { isValidWavFile(sampleFile(context, it)) }

    /** First sample slot needing capture, so a corrupt earlier slot cannot block enrollment. */
    fun nextMissingSampleIndex(context: Context): Int =
        nextMissingSampleIndex((1..TOTAL_SAMPLES).filter {
            isValidWavFile(sampleFile(context, it))
        }.toSet())

    internal fun nextMissingSampleIndex(validSampleIndexes: Set<Int>): Int =
        (1..TOTAL_SAMPLES).firstOrNull { it !in validSampleIndexes } ?: TOTAL_SAMPLES

    fun deleteSamples(context: Context) {
        (1..TOTAL_SAMPLES).forEach { runCatching { sampleFile(context, it).delete() } }
    }

    /**
     * Record one sample ([SAMPLE_SECONDS] seconds) into sample_[index].wav.
     * Blocks on Dispatchers.IO (AudioRecord reads are synchronous).
     * @return true when a complete WAV was written.
     */
    suspend fun recordSample(context: Context, index: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (index !in 1..TOTAL_SAMPLES) return@withContext false
            val file = sampleFile(context, index)
            // A failed re-record must not leave an older sample looking current.
            runCatching { file.delete() }
            currentCoroutineContext().ensureActive()

            val minBuffer = try {
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (e: Exception) {
                return@withContext false
            }
            if (minBuffer <= 0) return@withContext false
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, 16_000)
                )
            } catch (e: Exception) {
                return@withContext false
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                return@withContext false
            }
            val targetBytes = SAMPLE_RATE * 2 * SAMPLE_SECONDS
            val pcm = ByteArray(targetBytes)
            var filled = 0
            try {
                record.startRecording()
                while (filled < targetBytes) {
                    currentCoroutineContext().ensureActive()
                    val read = record.read(pcm, filled, targetBytes - filled)
                    if (read <= 0) break
                    filled += read
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext false
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
            currentCoroutineContext().ensureActive()
            if (filled != targetBytes) return@withContext false
            try {
                writeWav(file, pcm)
                isValidWavFile(file)
            } catch (e: CancellationException) {
                runCatching { file.delete() }
                throw e
            } catch (e: Exception) {
                runCatching { file.delete() }
                false
            }
        }

    /** Minimal RIFF/WAVE writer: 44-byte header + PCM16 mono data. */
    internal fun writeWav(file: File, pcm: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(wavHeader(pcm.size))
            out.write(pcm)
        }
        // Header is written up-front with the final size; RandomAccessFile
        // keeps the door open for streamed writes later — sanity-fix the
        // sizes once more so strict parsers accept the file.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.writeIntLE(36 + pcm.size)
            raf.seek(40)
            raf.writeIntLE(pcm.size)
        }
    }

    /** Validate RIFF/WAVE PCM16 mono format, exact three-second payload, and file size. */
    internal fun isValidWavFile(file: File): Boolean = runCatching {
        val expectedDataBytes = SAMPLE_RATE * 2 * SAMPLE_SECONDS
        if (!file.isFile || file.length() != 44L + expectedDataBytes) return@runCatching false
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            fun ascii(offset: Int, length: Int) =
                String(header, offset, length, Charsets.US_ASCII)
            fun intLe(offset: Int): Int =
                (header[offset].toInt() and 0xff) or
                    ((header[offset + 1].toInt() and 0xff) shl 8) or
                    ((header[offset + 2].toInt() and 0xff) shl 16) or
                    ((header[offset + 3].toInt() and 0xff) shl 24)
            fun shortLe(offset: Int): Int =
                (header[offset].toInt() and 0xff) or
                    ((header[offset + 1].toInt() and 0xff) shl 8)
            ascii(0, 4) == "RIFF" &&
                intLe(4) == 36 + expectedDataBytes &&
                ascii(8, 4) == "WAVE" &&
                ascii(12, 4) == "fmt " &&
                intLe(16) == 16 &&
                shortLe(20) == 1 &&
                shortLe(22) == 1 &&
                intLe(24) == SAMPLE_RATE &&
                intLe(28) == SAMPLE_RATE * 2 &&
                shortLe(32) == 2 &&
                shortLe(34) == 16 &&
                ascii(36, 4) == "data" &&
                intLe(40) == expectedDataBytes
        }
    }.getOrDefault(false)

    private fun wavHeader(dataBytes: Int): ByteArray {
        val header = ByteArray(44)
        fun putString(offset: Int, value: String) {
            value.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putString(0, "RIFF")
        putInt(4, 36 + dataBytes)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)               // fmt chunk size
        putShort(20, 1)              // PCM
        putShort(22, 1)              // mono
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)  // byte rate
        putShort(32, 2)              // block align
        putShort(34, 16)             // bits per sample
        putString(36, "data")
        putInt(40, dataBytes)
        return header
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}

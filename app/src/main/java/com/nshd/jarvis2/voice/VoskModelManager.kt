package com.nshd.jarvis2.voice

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * On-demand download of the small English Vosk model (Level 6).
 *
 * The model (~41 MB) is deliberately NOT bundled in the APK: it is
 * fetched once from alphacephei.com, unzipped into
 * `filesDir/vosk-model-small-en-us/` (the zip's own top folder stripped,
 * so the model dir directly holds `am/`, `conf/`, `graph/`…) and reused
 * forever after. Everything runs on Dispatchers.IO; progress is exposed
 * as a [StateFlow] the Settings wake-word card renders.
 */
object VoskModelManager {

    enum class ModelStatus { NOT_DOWNLOADED, READY }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val percent: Int) : DownloadState()
        object Extracting : DownloadState()
        object Done : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us"
    private const val ZIP_NAME = "vosk-model-small-en-us-0.15.zip"

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    /** Absolute path handed to the Vosk [org.vosk.Model] constructor. */
    fun modelPath(context: Context): String = modelDir(context).absolutePath

    /**
     * READY when the extracted tree really looks like a Vosk model: the
     * acoustic-model (`am`) and config (`conf`) folders must both exist.
     */
    fun status(context: Context): ModelStatus {
        val dir = modelDir(context)
        val ready = File(dir, "conf").isDirectory && File(dir, "am").isDirectory
        return if (ready) ModelStatus.READY else ModelStatus.NOT_DOWNLOADED
    }

    /** Download + extract, reporting through [downloadState]. Safe to retry. */
    suspend fun downloadModel(context: Context) {
        if (_downloadState.value is DownloadState.Downloading ||
            _downloadState.value is DownloadState.Extracting
        ) {
            return // one download at a time
        }
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            val zipFile = File(appContext.cacheDir, ZIP_NAME)
            try {
                _downloadState.value = DownloadState.Downloading(0)
                val request = Request.Builder().url(MODEL_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${response.code})")
                    }
                    val body = response.body
                        ?: throw IllegalStateException("Download failed (empty response)")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(zipFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var readTotal = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                readTotal += read
                                if (total > 0) {
                                    val percent = ((readTotal * 100) / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        _downloadState.value =
                                            DownloadState.Downloading(percent.coerceIn(0, 100))
                                    }
                                }
                            }
                        }
                    }
                }
                _downloadState.value = DownloadState.Extracting
                extractZip(zipFile, modelDir(appContext))
                zipFile.delete()
                if (status(appContext) == ModelStatus.READY) {
                    _downloadState.value = DownloadState.Done
                } else {
                    throw IllegalStateException("Model extracted but looks incomplete")
                }
            } catch (e: Exception) {
                runCatching { zipFile.delete() }
                _downloadState.value = DownloadState.Error(
                    e.message ?: "Model download failed"
                )
            }
        }
    }

    /** Reset the flow to Idle (e.g. after the UI consumed a Done/Error). */
    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Unzip [zip] into [destDir], stripping the archive's single top-level
     * folder ("vosk-model-small-en-us-0.15/…"). Every entry is checked
     * against zip-slip: nothing may resolve outside [destDir].
     */
    private fun extractZip(zip: File, destDir: File) {
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        val destCanonical = destDir.canonicalPath
        ZipInputStream(FileInputStream(zip)).use { zis ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                // Drop the first path segment (the zip's root folder).
                val relative = entry.name.substringAfter('/', "")
                if (relative.isNotBlank()) {
                    val target = File(destDir, relative)
                    val targetCanonical = target.canonicalPath
                    if (!targetCanonical.startsWith(destCanonical)) {
                        throw SecurityException("Blocked bad zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }
}

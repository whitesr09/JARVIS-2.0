package com.nshd.jarvis2.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nshd.jarvis2.core.AppLog
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * F8 (#5): offline COMMAND recognition for the Universal engine.
 *
 * When SpeechRecognizer dies with a network error (or the device is
 * offline at engine start), the session manager swaps recognition to
 * this recognizer: the SAME downloaded Vosk model the wake word uses
 * ([VoskModelManager]), but loaded WITHOUT the wake grammar — free-form
 * recognition over the small model's vocabulary. Command-length
 * utterances ("open WhatsApp", "set volume to fifty") are well inside
 * what the small English model handles; long dictation is not its job.
 *
 * One-shot semantics mirror SpeechRecognizer: a final result stops
 * capture (the mic frees while the pipeline runs + TTS speaks), and
 * the manager restarts listening via [startListening] when the turn
 * completes. Final text is delivered to the manager's Universal
 * listener, so results flow through the exact same dispatch pipeline
 * as SpeechRecognizer finals.
 *
 * Threading (F4.1 rule): the native Model load runs on a background
 * thread; SpeechService start/stop are funnelled to the main thread —
 * the same pattern WakeWordService uses.
 */
class VoskCommandRecognizer(
    context: Context,
    private val listener: Listener
) : RecognitionListener {

    interface Listener {
        /** One finished command utterance (trimmed, non-blank). */
        fun onFinalResult(text: String)

        /** Interim hypothesis while the user is still speaking. */
        fun onPartialResult(text: String)

        /** Recognizer failed — the manager falls back to SpeechRecognizer. */
        fun onError(message: String)

        /** Capture actually started (fresh listen cycle). */
        fun onListeningStarted()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    @Volatile
    private var engineReady = false

    @Volatile
    private var engineFailed = false

    @Volatile
    private var destroyed = false

    /** True between startListening and the final result / stop. */
    @Volatile
    private var listening = false

    /** Start requested before the model finished loading. */
    @Volatile
    private var startWhenReady = false

    init {
        // Integrity gate first (F4.1): a partial model tree must never
        // reach the native Model constructor — it can abort the whole
        // process with no Java exception to catch.
        if (!VoskModelManager.isModelComplete(appContext)) {
            engineFailed = true
            AppLog.w("STT", "vosk command recognizer: model incomplete — unavailable")
        } else {
            Thread({
                try {
                    val loaded = Model(VoskModelManager.modelPath(appContext))
                    // NO grammar argument → free-form recognition.
                    val rec = Recognizer(loaded, SAMPLE_RATE)
                    model = loaded
                    recognizer = rec
                    speechService = SpeechService(rec, SAMPLE_RATE)
                    engineReady = true
                    AppLog.i("STT", "vosk command recognizer ready (free-form)")
                    if (startWhenReady && !destroyed) {
                        mainHandler.post { startListening() }
                    }
                } catch (e: Exception) {
                    engineFailed = true
                    AppLog.e("STT", "vosk command recognizer failed to load: ${e.message}")
                    if (!destroyed) {
                        mainHandler.post {
                            listener.onError("Offline recognizer failed to start")
                        }
                    }
                }
            }, "vosk-command-load").apply { isDaemon = true }.start()
        }
    }

    /** Begin (or resume) one listen cycle. Safe from any thread. */
    fun startListening() {
        if (destroyed || engineFailed) return
        if (!engineReady) {
            startWhenReady = true
            return
        }
        mainHandler.post {
            if (destroyed || listening) return@post
            try {
                speechService?.startListening(this)
                listening = true
                listener.onListeningStarted()
            } catch (e: Exception) {
                AppLog.w("STT", "vosk command start failed: ${e.message}")
                listener.onError("Offline recognizer could not start")
            }
        }
    }

    /** Stop capture without tearing the engine down. */
    fun stopListening() {
        mainHandler.post {
            if (listening) {
                runCatching { speechService?.stop() }
                listening = false
            }
        }
    }

    /** Full teardown: service, recognizer, model. */
    fun shutdown() {
        destroyed = true
        mainHandler.post {
            runCatching { speechService?.shutdown() }
            speechService = null
            runCatching { recognizer?.close() }
            recognizer = null
            runCatching { model?.close() }
            model = null
            listening = false
        }
    }

    // ── Vosk RecognitionListener (SpeechService thread) ──────────────

    override fun onPartialResult(hypothesis: String?) {
        val text = parseText(hypothesis, partial = true) ?: return
        if (text.isNotBlank()) listener.onPartialResult(text)
    }

    override fun onResult(hypothesis: String?) {
        deliverFinal(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        deliverFinal(hypothesis)
    }

    override fun onError(exception: Exception?) {
        listening = false
        AppLog.w("STT", "vosk command recognizer error: ${exception?.message ?: "unknown"}")
        if (!destroyed) {
            mainHandler.post { listener.onError("Offline recognizer error") }
        }
    }

    override fun onTimeout() {
        // Free-form sessions have no timeout in practice; ignore.
    }

    /**
     * One utterance finished: stop capture (one-shot semantics) and
     * hand the text to the manager's normal dispatch. Blank results
     * just restart the cycle via the manager's error path equivalent —
     * here, simply listen again.
     */
    private fun deliverFinal(hypothesis: String?) {
        if (!listening) return
        listening = false
        mainHandler.post { runCatching { speechService?.stop() } }
        val text = parseText(hypothesis, partial = false)
        if (text.isNullOrBlank()) {
            // Heard nothing usable — keep the offline loop going.
            mainHandler.post { startListening() }
            return
        }
        AppLog.d("STT", "vosk final result (${text.length} chars): ${text.take(80)}")
        listener.onFinalResult(text)
    }

    /** Vosk hypotheses are JSON: {"text": "…"} / {"partial": "…"}. */
    private fun parseText(hypothesis: String?, partial: Boolean): String? {
        if (hypothesis.isNullOrBlank()) return null
        return try {
            val json = JSONObject(hypothesis)
            json.optString(if (partial) "partial" else "text").trim()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000f
    }
}

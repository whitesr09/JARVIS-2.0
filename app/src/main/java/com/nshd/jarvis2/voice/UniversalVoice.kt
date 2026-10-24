package com.nshd.jarvis2.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import com.nshd.jarvis2.CrashLogger
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.nshd.jarvis2.core.AppLog
import java.util.Locale

/**
 * UNIVERSAL voice engine (F2): microphone → text → the user's chat
 * model (via AgentPipeline) → TEXT reply (v2.0.2: spoken replies were
 * removed — the reply is surfaced as a caption / chat message only).
 * It works with ANY provider and ANY chat model — no native-audio
 * model required.
 *
 * This class owns only the device mechanics:
 *  - SpeechRecognizer passes with partial results (the manager renders
 *    them as a live-typing user caption) in the app's STT locale.
 *
 * F4.1 threading rule: SpeechRecognizer is a main-thread-only API,
 * and this class confines it ITSELF — every recognizer
 * create/start/stop/destroy is funnelled through [runOnMain], so
 * callers may invoke any method from any thread (the Live-failure →
 * Universal switch arrives on an OkHttp WebSocket thread).
 */
class UniversalVoice(
    context: Context,
    private val languageName: String,
    private val listener: Listener,
) {
    interface Listener {
        /** Partial transcription while the user is still speaking. */
        fun onPartialResult(text: String)

        /** Final recognized text from one listen pass. */
        fun onFinalResult(text: String)

        /** A new recognizer pass actually started listening (F5: clears stale STT hints). */
        fun onListeningStarted()

        /** The user started speaking (F5: clears stale STT hints). */
        fun onBeginningOfSpeech()

        /** Recognizer RMS changed (dB) — for the orb. */
        fun onRms(rmsDb: Float)

        /**
         * Recognizer error. [fatal] is true only for
         * ERROR_INSUFFICIENT_PERMISSIONS; everything else is a
         * pause-and-listen-again situation.
         */
        fun onError(message: String, fatal: Boolean)

        /**
         * F8 (#5): the recognizer hit a NETWORK-class error
         * (ERROR_NETWORK / ERROR_NETWORK_TIMEOUT) — the manager may
         * switch recognition to the offline Vosk fallback. Fires in
         * addition to [onError].
         */
        fun onNetworkError() {}

    }

    private val appContext = context.applicationContext
    private val languageTag: String = sttLanguageTag(languageName)
    private val locale: Locale = Locale.forLanguageTag(languageTag)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var destroyed = false

    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Run [block] on the main thread — inline when already there,
     * posted otherwise. Handler posting preserves call order, so a
     * start posted from a WS thread still lands before a later stop.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    // ── Recognition ────────────────────────────────────────────────────
    //
    // F7 lifecycle: ONE SpeechRecognizer per engine instance, reused
    // across listen cycles (cancel between cycles). The pre-F7 code
    // destroyed + recreated the recognizer on EVERY cycle — create +
    // service bind cost ~100–300 ms of dead air per turn. The instance
    // is destroyed and recreated only on ERROR_RECOGNIZER_BUSY /
    // ERROR_CLIENT (a genuinely wedged recognizer) and at shutdown.

    /** F7/I: one-shot language override for the next listen cycle. */
    private var languageOverrideTag: String? = null

    /** F7/I: the effective language is logged once per engine start. */
    private var languageLogged = false

    fun startListening() {
        runOnMain { startListeningOnMain() }
    }

    /**
     * F7/I: listen once with [tag] instead of the configured language
     * (the mis-script guard's alternate-English re-listen). The
     * override is consumed by that one cycle; later cycles return to
     * the configured language.
     */
    fun startListeningWithLanguage(tag: String) {
        runOnMain {
            languageOverrideTag = tag
            startListeningOnMain()
        }
    }

    private fun createRecognitionListener(): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() = listener.onBeginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) = listener.onRms(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { partial ->
                    listener.onPartialResult(partial)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                if (text.isNullOrBlank()) {
                    listener.onError("Didn't catch that", fatal = false)
                } else {
                    listener.onFinalResult(text)
                }
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                ) {
                    // F8 (#5): let the manager offer the offline
                    // Vosk fallback before the generic error path.
                    listener.onNetworkError()
                }
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT
                ) {
                    // F7: a wedged recognizer is the ONLY mid-session
                    // destroy — deferred until this callback returns,
                    // so the next startListening builds a fresh one.
                    AppLog.w("STT", "recognizer wedged (error $error) — recreating next cycle")
                    mainHandler.post {
                        recognizer?.let { runCatching { it.destroy() } }
                        recognizer = null
                    }
                }
                listener.onError(
                    describeError(error),
                    fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                )
            }
        }

    private fun startListeningOnMain() {
        if (destroyed) return
        // F7/I language discipline: the effective tag is RESOLVED,
        // never omitted — "Auto" maps to en-IN in sttLanguageTag, so
        // the recognizer can never drift to the device/Google-app
        // default language (a Malayalam/Hindi system default was
        // transcribing his English commands into the wrong script).
        val effectiveTag = languageOverrideTag ?: languageTag
        languageOverrideTag = null
        if (!languageLogged) {
            languageLogged = true
            AppLog.i(
                "STT",
                "recognizer language=$languageTag (setting=$languageName)"
            )
        }
        var rec = recognizer
        if (rec == null) {
            CrashLogger.stage("universal", "recognizer create")
            rec = SpeechRecognizer.createSpeechRecognizer(appContext)
            rec.setRecognitionListener(createRecognitionListener())
            recognizer = rec
        } else {
            // Reuse: cancel the previous cycle on the same instance.
            runCatching { rec.cancel() }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, effectiveTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, effectiveTag)
            // F7/I: was false — the service was free to return a
            // result in a DIFFERENT language than requested. True pins
            // results to the requested language where supported.
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Online recognition preferred: the on-device pack mishears
            // names ("Hey Jarvis" → "Hey, James") far more often.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        try {
            CrashLogger.stage("universal", "recognizer listening")
            AppLog.d("STT", "recognizer start lang=$effectiveTag")
            rec.startListening(intent)
            listener.onListeningStarted()
        } catch (e: Exception) {
            listener.onError("Speech recognition failed to start", fatal = false)
        }
    }

    fun stopListening() {
        runOnMain { stopListeningOnMain() }
    }

    /** F7: cancel only — the instance survives for the next cycle. */
    private fun stopListeningOnMain() {
        recognizer?.let { runCatching { it.cancel() } }
    }

    /** Destroy the recognizer instance (shutdown only). */
    private fun destroyRecognizerOnMain() {
        val rec = recognizer
        recognizer = null
        if (rec != null) {
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything"
        SpeechRecognizer.ERROR_NETWORK -> "Speech service network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service timed out"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is needed for speech recognition"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
        SpeechRecognizer.ERROR_SERVER -> "Speech service error"
        else -> "Speech recognition error ($error)"
    }

    fun shutdown() {
        destroyed = true
        runOnMain { destroyRecognizerOnMain() }
    }


    companion object {
        /**
         * App language name → BCP-47 tag for speech recognition. India
         * variants are the default — the user's accent and names are
         * Indian, and recognising en-US against them was a top cause of
         * misheard commands. Bhojpuri has no widely deployed recognizer,
         * so Hindi (its closest) is used; "Auto" also lands on en-IN.
         */
        fun sttLanguageTag(language: String): String = when (language) {
            "Hindi", "Hinglish", "Bhojpuri" -> "hi-IN"
            "Malayalam" -> "ml-IN"
            "Tamil" -> "ta-IN"
            "Telugu" -> "te-IN"
            "Kannada" -> "kn-IN"
            "Bengali" -> "bn-IN"
            "Marathi" -> "mr-IN"
            "Gujarati" -> "gu-IN"
            else -> "en-IN" // "English" and "Auto"
        }

        /**
         * F7/I: human label of the EFFECTIVE listening language for
         * the Voice screen, so a language mismatch is visible at a
         * glance. "Auto" resolves to en-IN (see sttLanguageTag) and
         * is labelled as such instead of pretending to be magic.
         */
        fun sttDisplayName(language: String): String = when (language) {
            "Auto" -> "English (India) · Auto"
            "English" -> "English (India)"
            else -> language
        }

        /**
         * Language code for the Gemini Live speech_config, or null when
         * the selection shouldn't pin one ("Auto", "Hinglish").
         *
         * F2 fix: English maps to "en-US" — the native-audio Live models
         * reject "en-IN" outright ("Unsupported language code 'en-IN'
         * for model models/gemini-2.5-flash-native-audio-preview-…"),
         * which killed the session in the user's QA. The Indian locales
         * below are the ones those models accept.
         */
        fun liveLanguageCode(language: String): String? = when (language) {
            "Auto", "Hinglish" -> null
            "English" -> "en-US"
            "Hindi", "Bhojpuri" -> "hi-IN"
            else -> sttLanguageTag(language)
        }
    }
}

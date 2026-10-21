package com.nshd.jarvis2.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Fallback voice path for when no Gemini key is configured (or for
 * devices where Live is unreachable): Android's on-device / system
 * SpeechRecognizer for one-shot listening + TextToSpeech for replies.
 * The manager loops listen → route (FailoverRouter) → speak → listen
 * until the user ends the session.
 *
 * MUST be created and driven from the main thread (SpeechRecognizer
 * requirement) — VoiceSessionManager's scope is Main, which covers this.
 */
class FallbackVoice(
    context: Context,
    private val languageName: String,
    private val listener: Listener
) {
    interface Listener {
        /** Final recognized text from one listen pass. */
        fun onResult(text: String)

        /** Partial transcription while the user is still speaking. */
        fun onPartial(text: String)

        /** Recognizer RMS changed (dB) — for the orb. */
        fun onRms(rmsDb: Float)

        /**
         * Recognizer error. [recoverable] = true for no-match / silence
         * timeouts where simply listening again is the right move.
         */
        fun onError(message: String, recoverable: Boolean)

        /** TTS finished (or failed) speaking one reply. */
        fun onSpeakDone()

        /** TTS engine missing/unusable — caller should continue text-only. */
        fun onTtsUnavailable()
    }

    private val appContext = context.applicationContext
    private val locale: Locale = localeForLanguage(languageName)

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var destroyed = false

    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    // ── Recognition ────────────────────────────────────────────────────

    fun startListening() {
        if (destroyed) return
        stopListening()
        val rec = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onRms(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { listener.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                if (text.isNullOrBlank()) {
                    listener.onError("Didn't catch that", recoverable = true)
                } else {
                    listener.onResult(text)
                }
            }
            override fun onError(error: Int) {
                listener.onError(describeError(error), isRecoverableError(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            listener.onError("Speech recognition failed to start", recoverable = false)
        }
    }

    fun stopListening() {
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

    private fun isRecoverableError(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
        else -> false
    }

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

    // ── Text-to-speech ─────────────────────────────────────────────────

    /** Lazily creates the TTS engine. Safe to call before every speak. */
    private fun ensureTts() {
        if (tts != null || destroyed) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.setLanguage(locale) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = listener.onSpeakDone()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = listener.onSpeakDone()
                    override fun onError(utteranceId: String?, errorCode: Int) =
                        listener.onSpeakDone()
                })
                ttsReady = true
            } else {
                ttsReady = false
                listener.onTtsUnavailable()
            }
        }
    }

    /**
     * Speak [text]; [Listener.onSpeakDone] fires when finished. If the
     * engine never becomes ready, onTtsUnavailable fires instead.
     */
    fun speak(text: String) {
        ensureTts()
        val engine = tts
        if (engine == null || !ttsReady) {
            // Engine still initializing (first call) or failed: treat this
            // reply as text-only rather than stalling the loop forever.
            if (engine == null) listener.onTtsUnavailable() else listener.onSpeakDone()
            return
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) listener.onSpeakDone()
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        destroyed = true
        stopListening()
        val engine = tts
        tts = null
        ttsReady = false
        if (engine != null) {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    companion object {
        private const val UTTERANCE_ID = "jarvis-fallback-reply"

        /** Maps the app's 12 language names (Settings) to locales. */
        fun localeForLanguage(language: String): Locale = when (language) {
            "English" -> Locale.ENGLISH
            "Hindi", "Hinglish" -> Locale("hi", "IN")
            "Telugu" -> Locale("te", "IN")
            "Tamil" -> Locale("ta", "IN")
            "Kannada" -> Locale("kn", "IN")
            "Malayalam" -> Locale("ml", "IN")
            "Bengali" -> Locale("bn", "IN")
            "Marathi" -> Locale("mr", "IN")
            "Gujarati" -> Locale("gu", "IN")
            "Bhojpuri" -> Locale("bho", "IN")
            else -> Locale.getDefault() // "Auto"
        }
    }
}

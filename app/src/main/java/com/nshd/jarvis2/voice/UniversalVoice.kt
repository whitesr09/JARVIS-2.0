package com.nshd.jarvis2.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * UNIVERSAL voice engine (F2): microphone → text → the user's chat
 * model (via AgentPipeline) → spoken reply. It works with ANY provider
 * and ANY chat model — no native-audio model required.
 *
 * This class owns only the device mechanics:
 *  - SpeechRecognizer passes with partial results (the manager renders
 *    them as a live-typing user caption) in the app's STT locale.
 *  - Sentence-queued TextToSpeech: the manager feeds completed
 *    sentences while the reply is still streaming, so the first audio
 *    starts at the first sentence boundary. [Listener.onTtsQueueDrained]
 *    fires (on the main thread) when everything queued has been spoken.
 *
 * Recognizer and TTS calls that must run on the main thread are the
 * caller's responsibility for startListening (VoiceSessionManager's
 * scope is Main); TTS listener callbacks are re-posted to Main here.
 */
class UniversalVoice(
    context: Context,
    private val languageName: String,
    private val listener: Listener
) {
    interface Listener {
        /** Partial transcription while the user is still speaking. */
        fun onPartialResult(text: String)

        /** Final recognized text from one listen pass. */
        fun onFinalResult(text: String)

        /** Recognizer RMS changed (dB) — for the orb. */
        fun onRms(rmsDb: Float)

        /**
         * Recognizer error. [fatal] is true only for
         * ERROR_INSUFFICIENT_PERMISSIONS; everything else is a
         * pause-and-listen-again situation.
         */
        fun onError(message: String, fatal: Boolean)

        /** Every queued utterance finished (or speaking was stopped). */
        fun onTtsQueueDrained()

        /** TTS engine missing/unusable — replies are text-only. */
        fun onTtsUnavailable()
    }

    private val appContext = context.applicationContext
    private val languageTag: String = sttLanguageTag(languageName)
    private val locale: Locale = Locale.forLanguageTag(languageTag)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var destroyed = false

    private val pendingUtterances = AtomicInteger(0)
    private val utteranceSeq = AtomicInteger(0)

    init {
        ensureTts()
    }

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
                firstResult(partialResults)?.let { listener.onPartialResult(it) }
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
                listener.onError(
                    describeError(error),
                    fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Online recognition preferred: the on-device pack mishears
            // names ("Hey Jarvis" → "Hey, James") far more often.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            listener.onError("Speech recognition failed to start", fatal = false)
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

    // ── Text-to-speech (sentence queue) ────────────────────────────────

    private fun ensureTts() {
        if (tts != null || destroyed) return
        tts = TextToSpeech(appContext) { status ->
            if (destroyed) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.setLanguage(locale) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = utteranceFinished()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = utteranceFinished()
                    override fun onError(utteranceId: String?, errorCode: Int) =
                        utteranceFinished()
                })
                ttsReady = true
            } else {
                ttsReady = false
                mainHandler.post { if (!destroyed) listener.onTtsUnavailable() }
            }
        }
    }

    private fun utteranceFinished() {
        val remaining = pendingUtterances.decrementAndGet()
        if (remaining <= 0) {
            pendingUtterances.set(0)
            mainHandler.post { if (!destroyed) listener.onTtsQueueDrained() }
        }
    }

    /**
     * Queue one completed sentence (QUEUE_ADD) — speech starts at the
     * first sentence boundary while the rest of the reply still streams.
     * If TTS is unusable the sentence is skipped and the drained signal
     * fires so the conversation loop never stalls.
     */
    fun speakSentence(sentence: String) {
        val text = sentence.trim()
        if (text.isBlank() || destroyed) return
        ensureTts()
        val engine = tts
        if (engine == null) {
            mainHandler.post { if (!destroyed) listener.onTtsUnavailable() }
            return
        }
        if (!ttsReady) {
            // Still initialising: don't stall the loop on this sentence.
            mainHandler.post { if (!destroyed) listener.onTtsQueueDrained() }
            return
        }
        val id = "universal-${utteranceSeq.incrementAndGet()}"
        pendingUtterances.incrementAndGet()
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result == TextToSpeech.ERROR) utteranceFinished()
    }

    /** Barge-in: stop speaking now and report the queue as drained. */
    fun stopSpeaking() {
        runCatching { tts?.stop() }
        pendingUtterances.set(0)
        mainHandler.post { if (!destroyed) listener.onTtsQueueDrained() }
    }

    fun shutdown() {
        destroyed = true
        stopListening()
        val engine = tts
        tts = null
        ttsReady = false
        pendingUtterances.set(0)
        if (engine != null) {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
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

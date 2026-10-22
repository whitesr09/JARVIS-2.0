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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.nshd.jarvis2.core.AppLog
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
 * F4.1 threading rule: SpeechRecognizer and TextToSpeech are
 * main-thread-only APIs, and this class now confines them ITSELF —
 * every recognizer create/start/stop/destroy and every TTS
 * create/speak/stop/shutdown is funnelled through [runOnMain], so
 * callers may invoke any method from any thread (the Live-failure →
 * Universal switch arrives on an OkHttp WebSocket thread). TTS
 * listener callbacks are re-posted to Main as well.
 */
class UniversalVoice(
    context: Context,
    private val languageName: String,
    private val listener: Listener,
    /** F5: persona display name — drives the TTS pitch tuning. */
    private val personaName: String = ""
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

        /** F5: device TTS initialised — the voice actually in use. */
        fun onTtsVoiceSelected(voiceName: String)

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

    fun startListening() {
        runOnMain { startListeningOnMain() }
    }

    private fun startListeningOnMain() {
        if (destroyed) return
        stopListeningOnMain()
        CrashLogger.stage("universal", "recognizer create")
        val rec = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() = listener.onBeginningOfSpeech()
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
            CrashLogger.stage("universal", "recognizer listening")
            AppLog.d("STT", "recognizer start lang=$languageTag")
            rec.startListening(intent)
            listener.onListeningStarted()
        } catch (e: Exception) {
            listener.onError("Speech recognition failed to start", fatal = false)
        }
    }

    fun stopListening() {
        runOnMain { stopListeningOnMain() }
    }

    private fun stopListeningOnMain() {
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

    @Volatile
    private var ttsEnginePackage: String = ""

    private fun ensureTts() {
        if (tts != null || destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // TextToSpeech construction is main-thread-only; the
            // engine's init callback fills `tts` in when it runs.
            runOnMain { ensureTts() }
            return
        }
        CrashLogger.stage("universal", "tts init")
        // F5: prefer Google TTS — its voices are markedly better than
        // most OEM defaults. Detect it via the TTS service intent,
        // construct with that engine, and fall back to the system
        // default engine when it isn't installed (or throws).
        val googleInstalled = runCatching {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            appContext.packageManager.queryIntentServices(intent, 0)
                .any { it.serviceInfo.packageName == GOOGLE_TTS_ENGINE }
        }.getOrDefault(false)
        val onInit = TextToSpeech.OnInitListener { status ->
            if (destroyed) return@OnInitListener
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
                ttsReady = true
            } else {
                ttsReady = false
                AppLog.w("TTS", "engine init failed (status=$status)")
                mainHandler.post { if (!destroyed) listener.onTtsUnavailable() }
            }
        }
        val created: TextToSpeech? = if (googleInstalled) {
            runCatching {
                TextToSpeech(appContext, onInit, GOOGLE_TTS_ENGINE)
            }.getOrNull()?.also { ttsEnginePackage = GOOGLE_TTS_ENGINE }
        } else {
            null
        }
        if (created != null) {
            tts = created
        } else {
            ttsEnginePackage = "default"
            tts = TextToSpeech(appContext, onInit)
        }
        AppLog.i("TTS", "engine init requested: $ttsEnginePackage")
    }

    /**
     * F5 voice selection, run once on TTS init (main thread): among the
     * engine's voices for the session locale, prefer NETWORK voices
     * (markedly higher quality) at the highest quality level; otherwise
     * the best offline voice. Persona pitch is applied here too —
     * Girlfriend Mode speaks slightly higher (1.08), Jarvis Pro stays
     * neutral (1.0); rate is 1.0 for both.
     */
    private fun configureTts() {
        val engine = tts ?: return
        runCatching { engine.setLanguage(locale) }
        val selected = selectBestVoice(engine)
        if (selected != null) {
            runCatching { engine.setVoice(selected) }
        }
        val pitch = if (personaName.contains("Girlfriend", ignoreCase = true)) {
            GIRLFRIEND_PITCH
        } else {
            NEUTRAL_PITCH
        }
        runCatching { engine.setPitch(pitch) }
        runCatching { engine.setSpeechRate(SPEECH_RATE) }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = utteranceFinished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                AppLog.w("TTS", "utterance error (legacy callback)")
                utteranceFinished()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                AppLog.w("TTS", "utterance error code=$errorCode")
                utteranceFinished()
            }
        })
        val voiceName = selected?.name
            ?: runCatching { engine.voice?.name }.getOrNull()
            ?: runCatching { engine.defaultVoice?.name }.getOrNull()
            ?: "unknown"
        AppLog.i(
            "TTS",
            "voice selected: $voiceName (engine=$ttsEnginePackage, " +
                "locale=$languageTag, pitch=$pitch, rate=$SPEECH_RATE)"
        )
        mainHandler.post { if (!destroyed) listener.onTtsVoiceSelected(voiceName) }
    }

    /** Best voice for [locale]: exact-locale → same-language → any; network first, then quality. */
    private fun selectBestVoice(engine: TextToSpeech): Voice? {
        val all = runCatching { engine.voices }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val sameLanguage = all.filter { voice ->
            runCatching { voice.locale?.language }.getOrNull() == locale.language
        }
        val exact = sameLanguage.filter { voice ->
            runCatching { voice.locale }.getOrNull() == locale
        }
        val pool = when {
            exact.isNotEmpty() -> exact
            sameLanguage.isNotEmpty() -> sameLanguage
            else -> all.toList()
        }
        val network = pool.filter { voice ->
            runCatching { voice.isNetworkConnectionRequired }.getOrDefault(false)
        }
        val candidates = network.ifEmpty { pool }
        return candidates.maxByOrNull { voice ->
            runCatching { voice.quality }.getOrDefault(0)
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
        runOnMain { speakSentenceOnMain(sentence) }
    }

    private fun speakSentenceOnMain(sentence: String) {
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
        runOnMain { runCatching { tts?.stop() } }
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
            // stop()/shutdown() are main-confined like all TTS calls;
            // `destroyed` is already set, so the posted block still runs.
            runOnMain {
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
            }
        }
    }

    companion object {
        /** F5: preferred TTS engine package (best stock voices on Android). */
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        private const val GIRLFRIEND_PITCH = 1.08f
        private const val NEUTRAL_PITCH = 1.0f
        private const val SPEECH_RATE = 1.0f

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

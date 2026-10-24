package com.nshd.jarvis2.voice

/**
 * v2.0.3: the Universal SYSTEM-VOICE engine is DELETED. This file
 * keeps only the language-mapping statics, because three unrelated
 * call sites still need them:
 *  - VoiceSessionManager: [liveLanguageCode] for the Gemini Live
 *    speech_config (Live audio, not system TTS).
 *  - ChatScreen composer dictation: [sttLanguageTag] for the
 *    inline SpeechRecognizer that writes into the typed-chat composer
 *    (the ONLY remaining SpeechRecognizer use in a voice-adjacent
 *    path — it is text input, never a voice session).
 *  - VoiceScreen / SelfTest: [sttDisplayName] labels.
 *
 * No SpeechRecognizer, no TextToSpeech, no session engine lives here
 * anymore. A voice SESSION is Live-only (see VoiceSessionManager).
 */
class UniversalVoice {
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

package com.nshd.jarvis2.ui

/**
 * User-facing error mapping (F2). Raw exception/API strings must never
 * be the primary error UI — the QA screenshots showed
 * "Unsupported language code 'en-IN' for model models/…" as the whole
 * error screen. [friendlyError] returns a plain-language headline; the
 * raw text stays available behind a "Details" expander in the UI.
 */
object FriendlyErrors {

    /**
     * @return headline to show big; [rawDetail] for the Details expander
     * (null when the headline already IS the whole story).
     */
    data class Mapped(val headline: String, val rawDetail: String?)

    fun map(raw: String?): Mapped {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) {
            return Mapped("Something went wrong.", null)
        }
        val lower = text.lowercase()
        return when {
            lower.contains("unsupported language code") || lower.contains("language code") ->
                Mapped(
                    "That voice model doesn't support the selected language — " +
                        "switch the Voice Engine to Universal, or pick another language.",
                    text
                )

            lower.contains("401") || lower.contains("403") ||
                lower.contains("api key") && (lower.contains("reject") ||
                lower.contains("invalid") || lower.contains("missing")) ->
                Mapped("API key rejected — check it in Settings → API & Connection.", text)

            lower.contains("not found") && lower.contains("model") ||
                lower.contains("model") && lower.contains("not available") ||
                lower.contains("404") ->
                Mapped(
                    "That model isn't available on your API — pick another in " +
                        "Settings → API & Connection.",
                    text
                )

            lower.contains("network") || lower.contains("unable to resolve") ||
                lower.contains("timeout") || lower.contains("timed out") ||
                lower.contains("connect") || lower.contains("internet") ->
                Mapped("Connection problem — check your internet and API key.", text)

            // Short, already-human messages (our own fail() strings)
            // pass through untouched; anything that smells like a raw
            // exception/HTTP dump becomes a calm generic headline.
            text.length <= 160 && !text.contains("HTTP ") &&
                !lower.contains("exception") && !text.contains("://") ->
                Mapped(text, null)

            else -> Mapped("Something went wrong with the voice session.", text)
        }
    }
}

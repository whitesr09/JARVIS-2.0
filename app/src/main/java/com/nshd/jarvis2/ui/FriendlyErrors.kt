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
        // F5: raw provider text in Details is sanitized + capped at 200
        // chars — the full body belongs in AppLog, never on screen.
        val detail = detailSnippet(text)
        return when {
            // v2.0.1: Gemini rejected one setup VALUE ("Invalid value
            // at 'setup.…'") — e.g. the invalid START_SENSITIVITY_MED
            // enum v1.8.0 sent. Not the user's fault and not the
            // model's availability: Jarvis strips the field and
            // retries by itself, so say exactly that.
            lower.contains("invalid value at 'setup.") ||
                lower.contains("invalid value at \"setup.") ->
                Mapped(
                    "Gemini rejected one of Jarvis's Live setup options — " +
                        "Jarvis removes it and retries automatically, so " +
                        "just tap Retry.",
                    detail
                )

            lower.contains("unsupported language code") || lower.contains("language code") ->
                Mapped(
                    "That voice model doesn't support the selected language — " +
                        "switch the Voice Engine to Universal, or pick another language.",
                    detail
                )

            lower.contains("401") || lower.contains("403") ||
                lower.contains("api key") && (lower.contains("reject") ||
                lower.contains("invalid") || lower.contains("missing")) ->
                Mapped("API key rejected — check it in Settings → API & Connection.", detail)

            // F5.1: a rejected Live session/resumption handle is NOT
            // a model problem — say so before the model branch below.
            lower.contains("session") &&
                (lower.contains("handle") || lower.contains("not found") ||
                    lower.contains("expired")) ->
                Mapped(
                    "Gemini Live rejected the saved session (it had expired) — " +
                        "Jarvis retries once with a clean session automatically.",
                    detail
                )

            lower.contains("not found") && lower.contains("model") ||
                lower.contains("model") && lower.contains("not available") ||
                lower.contains("404") ->
                Mapped(
                    "That model isn't available on your API — pick another in " +
                        "Settings → API & Connection.",
                    detail
                )

            lower.contains("network") || lower.contains("unable to resolve") ||
                lower.contains("timeout") || lower.contains("timed out") ||
                lower.contains("connect") || lower.contains("internet") ->
                Mapped("Connection problem — check your internet and API key.", detail)

            // Short, already-human messages (our own fail() strings)
            // pass through untouched; anything that smells like a raw
            // exception/HTTP dump becomes a calm generic headline.
            text.length <= 160 && !text.contains("HTTP ") &&
                !lower.contains("exception") && !text.contains("://") ->
                Mapped(text, null)

            else -> Mapped("Something went wrong with the voice session.", detail)
        }
    }

    /**
     * F5: one short cause phrase for compact surfaces — the Universal
     * switch caption ("Native Live unavailable (<reason>) — …") and
     * logs. Never contains raw provider text.
     */
    fun shortReason(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return "connection failed"
        val lower = text.lowercase()
        return when {
            // F5.1 FIRST: session/handle rejections must win over the
            // generic "not found" mapping — "BidiGenerateContent
            // session not found" used to surface as "model not found",
            // sending the user to fix a model that was never reached.
            lower.contains("session") &&
                (lower.contains("handle") || lower.contains("not found") ||
                    lower.contains("expired")) ->
                "Live session rejected (stale session)"
            lower.contains("gateway") -> "needs a Google AI Studio key"
            lower.contains("unsupported language") || lower.contains("language code") ||
                lower.contains("language not supported") -> "language not supported"
            lower.contains("setup timeout") || lower.contains("setup timed out") ->
                "setup timeout"
            // v2.0.1: a rejected setup value (invalid enum/field) —
            // checked before the generic branches: the raw text
            // ("…type.googleapis.com/…") matches none of them, but
            // the caption deserves the precise cause.
            lower.contains("invalid value at 'setup.") ||
                lower.contains("invalid value at \"setup.") ->
                "Live setup option rejected (auto-fixed)"
            lower.contains("401") || lower.contains("403") ||
                lower.contains("api key") || lower.contains("permission denied") ->
                "API key rejected"
            // F5.1: "model not found" only when the text actually
            // references a model — a "models/…" path with a 404, or
            // "not found" alongside "model".
            lower.contains("404") && lower.contains("models/") -> "model not found"
            (lower.contains("not found") || lower.contains("not_found")) &&
                lower.contains("model") -> "model not found"
            lower.contains("404") -> "not found (404)"
            lower.contains("only supports") && lower.contains("websocket") ->
                "live-only model used for chat"
            lower.contains("timeout") || lower.contains("timed out") -> "timed out"
            lower.contains("unable to resolve") || lower.contains("network") ||
                lower.contains("internet") -> "network error"
            lower.contains("closed") -> "connection closed"
            lower.contains("missing") && lower.contains("key") -> "API key missing"
            else -> "connection failed"
        }
    }

    /** Sanitized raw text for a Details expander — capped at 200 chars (F5). */
    fun detailSnippet(raw: String?, maxChars: Int = 200): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        val clean = com.nshd.jarvis2.core.AppLog.sanitize(text).replace('\n', ' ')
        return if (clean.length <= maxChars) clean else clean.take(maxChars - 1) + "…"
    }
}

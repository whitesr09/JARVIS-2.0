package com.nshd.jarvis2.brain

enum class ToolEventKind { STARTED, SUCCESS, FAILURE, INFO }

/**
 * One structured phone-tool activity event (F2). Until F1 these travelled
 * as pre-formatted strings ("🔧 name", "✅ name: raw result…") and were
 * rendered — and even persisted — verbatim, which leaked raw tool output
 * like `❌ open_app: No installed app matched "Spotify"` into the voice
 * captions and chat history. Now the pipeline emits structure; each UI
 * renders [chipText] as a small status chip, the overlay HUD uses
 * [hudText], and raw result strings NEVER become message text or history.
 */
data class ToolEvent(
    val kind: ToolEventKind,
    val toolName: String,
    /** Full tool result text (model-facing detail) — never shown raw. */
    val message: String = ""
) {
    /** Legacy one-liner for the floating Task HUD (plain Views there). */
    val hudText: String
        get() = when (kind) {
            ToolEventKind.STARTED -> "🔧 $toolName"
            ToolEventKind.SUCCESS -> "✅ $toolName: ${firstLine()}"
            ToolEventKind.FAILURE -> "❌ $toolName: ${firstLine()}"
            ToolEventKind.INFO -> message
        }

    /** Short, friendly text for the in-app status chip. */
    val chipText: String
        get() = when (kind) {
            ToolEventKind.STARTED -> "${prettyTool(toolName)}…"
            ToolEventKind.INFO -> message.take(90)
            ToolEventKind.SUCCESS -> friendlyResult(success = true)
            ToolEventKind.FAILURE -> friendlyResult(success = false)
        }

    private fun firstLine(): String = message.lineSequence().firstOrNull().orEmpty()

    private fun friendlyResult(success: Boolean): String {
        // open_app failures get a dedicated phrasing built from the app
        // name the model asked for (quoted in the raw result).
        if (toolName == "open_app") {
            val app = QUOTED.find(message)?.groupValues?.get(1)
            return if (success) {
                "Opened ${app ?: "app"}"
            } else {
                "Couldn't open ${app ?: "that app"}"
            }
        }
        val line = firstLine().trim().trimEnd('.')
        if (line.isBlank()) return if (success) "Done" else "Something failed"
        // Success/failure lines from ToolExecutor are already short
        // human sentences ("Opened Camera", "WhatsApp message sent…");
        // cap them so a verbose result can't flood the chip.
        return if (line.length <= 64) line else line.take(61).trimEnd() + "…"
    }

    companion object {
        private val QUOTED = Regex("\"([^\"]+)\"")

        /** "open_app" → "Open app", "whatsapp_message" → "Whatsapp message". */
        fun prettyTool(name: String): String =
            name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

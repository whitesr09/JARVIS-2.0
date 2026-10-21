package com.nshd.jarvis2.brain

import android.content.Context
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One shared text pipeline for Chat and the fallback voice path:
 *
 *   local memory commands → deterministic quick commands → tool agent loop
 *
 * The pipeline returns the final assistant text. Callers own presentation
 * and persistence of the user/assistant turns; intermediate tool lines are
 * delivered through [onEvent] so each UI can render and persist them in its
 * own way.
 */
object AgentPipeline {

    suspend fun handleUserText(
        context: Context,
        text: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean = false,
        onEvent: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext "I didn't catch that — please try again."

        // ── Local memory commands (no model or API key required) ───────
        extractRemember(clean)?.let { fact ->
            if (fact.isBlank()) {
                return@withContext "Tell me what to remember after \"remember\" — " +
                    "e.g. \"remember my exam is on Friday\"."
            }
            val category = inferCategory(fact)
            memoryDb.addFact(category, fact)
            return@withContext "Saved to memory ✅ — I'll remember: $fact"
        }
        if (isMemoryListRequest(clean)) {
            val facts = memoryDb.allFacts(null, null)
            return@withContext if (facts.isEmpty()) {
                "You haven't saved any memories yet. Tell me \"remember …\" and " +
                    "I'll keep it in my Memory Core."
            } else {
                buildString {
                    append("Here's what I remember about you, ${settings.callsign}:")
                    facts.forEach { fact -> append("\n• [${fact.category}] ${fact.text}") }
                }
            }
        }

        // ── Deterministic quick commands (work with no API key) ────────
        quickCommand(context, clean)?.let { quick ->
            val executor = ToolExecutor(context, memoryDb)
            onEvent("🔧 ${quick.toolName}")
            val result = executor.execute(
                quick.toolName,
                quick.args,
                includeScreenSummary = false
            )
            onEvent("${if (result.success) "✅" else "❌"} ${quick.toolName}: " +
                result.message.lineSequence().firstOrNull().orEmpty())
            return@withContext result.message
        }

        // ── Model + tools ──────────────────────────────────────────────
        val facts = memoryDb.allFacts(null, null)
        val systemPrompt = PersonaPrompts.build(settings, facts, codeMode)
        val history = memoryDb.lastMessages(20)
            .filter { it.role == "user" || it.role == "assistant" }
            .map { ChatMsg(role = it.role, content = it.content) }
        val messages = mutableListOf(ChatMsg("system", systemPrompt))
        messages += history
        if (history.lastOrNull()?.content != clean) {
            messages += ChatMsg("user", clean)
        }
        val executor = ToolExecutor(context, memoryDb)
        FailoverRouter(providerStore.orderedConfigs(), executor).agentLoop(
            systemAndHistory = messages,
            maxSteps = 10,
            preferReasoningModel = codeMode,
            onEvent = onEvent
        )
    }

    // ── Local memory parsing ───────────────────────────────────────────

    private val rememberPatterns = listOf(
        Regex("(?i)^remember that\\s+(.+)$"),
        Regex("(?i)^remember:\\s*(.+)$"),
        Regex("(?i)^remember\\s+(.+)$"),
        Regex("(?i)^save memory:\\s*(.+)$"),
        Regex("(?i)^save this to memory:\\s*(.+)$"),
        Regex("(?i)^save to memory:\\s*(.+)$")
    )

    private fun extractRemember(text: String): String? {
        for (pattern in rememberPatterns) {
            pattern.matchEntire(text.trim())?.let { return it.groupValues[1].trim() }
        }
        return null
    }

    private fun inferCategory(fact: String): String {
        val lower = fact.lowercase()
        val preferenceStarters = listOf("i like", "i love", "i prefer", "i enjoy", "i hate")
        return if (preferenceStarters.any { lower.startsWith(it) }) {
            MemoryCategories.PREFERENCES
        } else {
            MemoryCategories.GENERAL
        }
    }

    private fun isMemoryListRequest(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower.contains("what do you remember") ||
            lower.contains("list memories") ||
            lower.contains("what do you know about me") ||
            lower == "show memories" ||
            lower == "my memories"
    }

    // ── Quick commands ─────────────────────────────────────────────────

    private data class QuickCommand(val toolName: String, val args: JSONObject)

    private fun quickCommand(context: Context, rawText: String): QuickCommand? {
        val text = rawText.trim().trimEnd('.', '!', '?')
        val lower = text.lowercase()

        Regex("(?i)^whatsapp\\s+(.+?):\\s*(.+)$").find(text)?.let { match ->
            return QuickCommand(
                "whatsapp_message",
                JSONObject()
                    .put("contact", match.groupValues[1].trim())
                    .put("message", match.groupValues[2].trim())
            )
        }
        Regex("(?i)^(?:send\\s+(?:a\\s+)?)?whatsapp\\s+(?:message\\s+)?to\\s+(.+?)\\s+saying\\s+(.+)$")
            .find(text)?.let { match ->
                return QuickCommand(
                    "whatsapp_message",
                    JSONObject()
                        .put("contact", match.groupValues[1].trim())
                        .put("message", match.groupValues[2].trim())
                )
            }

        Regex("(?i)^play\\s+(.+?)\\s+on\\s+youtube$").find(text)?.let { match ->
            return QuickCommand(
                "youtube_play",
                JSONObject().put("query", match.groupValues[1].trim())
            )
        }

        Regex("(?i)^(?:turn\\s+)?(?:the\\s+)?(torch|flashlight)\\s+(on|off)$")
            .find(lower)?.let { match ->
                return QuickCommand(
                    "toggle_torch",
                    JSONObject().put("on", match.groupValues[2] == "on")
                )
            }
        Regex("(?i)^(?:turn\\s+)?(on|off)\\s+(?:the\\s+)?(torch|flashlight)$")
            .find(lower)?.let { match ->
                return QuickCommand(
                    "toggle_torch",
                    JSONObject().put("on", match.groupValues[1] == "on")
                )
            }

        Regex("^volume\\s+(?:to\\s+)?(\\d{1,3})\\s*%?$").find(lower)?.let { match ->
            return QuickCommand(
                "set_volume",
                JSONObject().put("percent", match.groupValues[1].toInt().coerceIn(0, 100))
            )
        }
        if (lower == "volume up" || lower == "increase volume" || lower == "raise volume") {
            val percent = DeepIntents(context).currentVolumePercent() + 20
            return QuickCommand("set_volume", JSONObject().put("percent", percent.coerceIn(0, 100)))
        }
        if (lower == "volume down" || lower == "decrease volume" || lower == "lower volume") {
            val percent = DeepIntents(context).currentVolumePercent() - 20
            return QuickCommand("set_volume", JSONObject().put("percent", percent.coerceIn(0, 100)))
        }

        Regex("(?i)^open\\s+(.+)$").find(text)?.let { match ->
            val app = match.groupValues[1].trim()
            // Avoid swallowing ordinary model requests that merely begin
            // with "open" plus a long instruction.
            if (app.isNotBlank() && app.split(Regex("\\s+")).size <= 4) {
                return QuickCommand("open_app", JSONObject().put("app", app))
            }
        }
        return null
    }
}

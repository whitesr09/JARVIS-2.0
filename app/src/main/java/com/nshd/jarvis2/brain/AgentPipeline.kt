package com.nshd.jarvis2.brain

import android.content.Context
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One shared text pipeline for Chat and the fallback voice path:
 *
 *   local memory commands → deterministic quick commands → tool agent loop
 *
 * The pipeline returns the final assistant text. Callers own presentation
 * and persistence of the user/assistant turns; intermediate tool activity
 * is delivered as structured [ToolEvent]s through [onEvent] so each UI
 * can render it as a status chip (raw tool output is never message text).
 *
 * F3 additions:
 *  - Every turn (and every queue sub-task) gets a fresh [ExecutionLedger]
 *    so an identical tool call can never execute twice in one turn.
 *  - Compound requests are split into a [TaskQueue] of ordered sub-tasks
 *    that run sequentially through the same paths.
 */
object AgentPipeline {

    suspend fun handleUserText(
        context: Context,
        text: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean = false,
        onEvent: (ToolEvent) -> Unit = {},
        onAssistantDelta: (String) -> Unit = {}
    ): String {
        // Feed the floating overlay's Task HUD (Level 5): the pipeline
        // runs under the caller's own coroutine Job so the overlay's
        // abort can cancel it. dispatch() calls TaskState.begin with
        // this job (once for a single task, once per queue sub-task).
        val job = currentCoroutineContext()[Job] ?: Job()
        return try {
            val result = withContext(Dispatchers.IO) {
                dispatch(
                    context, text, settings, memoryDb, providerStore,
                    codeMode, job, onEvent, onAssistantDelta
                )
            }
            TaskState.finish(
                success = !result.startsWith("I couldn't") && !result.startsWith("Please configure"),
                summary = result.lineSequence().firstOrNull()?.take(80).orEmpty()
            )
            result
        } catch (e: CancellationException) {
            // Abort path (overlay stop / caller cancel): the current
            // sub-task dies with this coroutine and the queue is aborted
            // so nothing queued can run afterwards.
            TaskQueue.abortAll()
            TaskState.finish(false, "Aborted")
            throw e
        } catch (e: Exception) {
            TaskState.finish(false, e.message ?: "Failed")
            throw e
        }
    }

    private suspend fun dispatch(
        context: Context,
        text: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean = false,
        job: Job,
        onEvent: (ToolEvent) -> Unit = {},
        onAssistantDelta: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext "I didn't catch that — please try again."

        val hudEvent: (ToolEvent) -> Unit = { event ->
            TaskState.step(event.hudText)
            onEvent(event)
        }
        TaskState.begin(goal = clean.take(60), job = job, maxSteps = 10)

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
            return@withContext runQuick(context, quick, memoryDb, ExecutionLedger(), hudEvent)
        }

        // ── Multitask queue (F3): compound request → ordered sub-tasks ─
        if (looksCompound(clean)) {
            val titles = splitTasks(clean, providerStore)
            if (titles.size >= 2) {
                return@withContext runTaskQueue(
                    context, titles, settings, memoryDb, providerStore,
                    codeMode, job, hudEvent, onAssistantDelta
                )
            }
            // Splitter could not separate it — fall through and let the
            // agent loop handle the request as a single task.
        }

        // ── Model + tools (single task) ────────────────────────────────
        runAgentTurn(
            context, clean, settings, memoryDb, providerStore,
            codeMode, hudEvent, onAssistantDelta, ExecutionLedger()
        )
    }

    /** Execute one deterministic quick command, with HUD events. */
    private suspend fun runQuick(
        context: Context,
        quick: QuickCommand,
        memoryDb: MemoryDb,
        ledger: ExecutionLedger,
        onEvent: (ToolEvent) -> Unit
    ): String {
        val executor = ToolExecutor(context, memoryDb, ledger)
        onEvent(ToolEvent(ToolEventKind.STARTED, quick.toolName))
        val result = executor.execute(
            quick.toolName,
            quick.args,
            includeScreenSummary = false
        )
        onEvent(
            ToolEvent(
                kind = if (result.success) ToolEventKind.SUCCESS else ToolEventKind.FAILURE,
                toolName = quick.toolName,
                message = result.message
            )
        )
        return result.message
    }

    /** The model + tools agent loop for exactly one user goal. */
    private suspend fun runAgentTurn(
        context: Context,
        userText: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean,
        onEvent: (ToolEvent) -> Unit,
        onAssistantDelta: (String) -> Unit,
        ledger: ExecutionLedger
    ): String {
        val facts = memoryDb.allFacts(null, null)
        val systemPrompt = PersonaPrompts.build(settings, facts, codeMode)
        // Trim what's SENT (stored history stays intact): last 16 turns
        // keeps prompts small, which keeps free-tier latency down.
        val history = memoryDb.lastMessages(16)
            .filter { it.role == "user" || it.role == "assistant" }
            .map { ChatMsg(role = it.role, content = it.content) }
        val messages = mutableListOf(ChatMsg("system", systemPrompt))
        messages += history
        if (history.lastOrNull()?.content != userText) {
            messages += ChatMsg("user", userText)
        }
        val executor = ToolExecutor(context, memoryDb, ledger)
        return FailoverRouter(providerStore.orderedConfigs(), executor).agentLoop(
            systemAndHistory = messages,
            maxSteps = 10,
            preferReasoningModel = codeMode,
            onEvent = onEvent,
            onAssistantDelta = onAssistantDelta
        )
    }

    // ── Multitask queue (F3) ───────────────────────────────────────────

    /**
     * Run [titles] sequentially through the existing paths (quick
     * commands first, then the agent loop), each with a FRESH ledger.
     * A failed sub-task is marked FAILED and the queue continues; the
     * returned summary lists done/failed counts.
     */
    private suspend fun runTaskQueue(
        context: Context,
        titles: List<String>,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean,
        job: Job,
        onEvent: (ToolEvent) -> Unit,
        onAssistantDelta: (String) -> Unit
    ): String {
        val items = TaskQueue.submit(titles)
        var done = 0
        var failed = 0
        val lines = mutableListOf<String>()
        for ((index, item) in items.withIndex()) {
            // Cancellation (abort) propagates to handleUserText, which
            // aborts the queue — queued items never start.
            currentCoroutineContext().ensureActive()
            TaskQueue.markRunning(item.id)
            // TaskState mirrors the running sub-task's goal + steps.
            TaskState.begin(goal = item.title.take(60), job = job, maxSteps = 10)
            onEvent(
                ToolEvent(
                    ToolEventKind.INFO,
                    "",
                    "Task ${index + 1}/${items.size}: ${item.title}"
                )
            )
            val ledger = ExecutionLedger()
            val result = try {
                val quick = quickCommand(context, item.title)
                if (quick != null) {
                    runQuick(context, quick, memoryDb, ledger, onEvent)
                } else {
                    runAgentTurn(
                        context, item.title, settings, memoryDb, providerStore,
                        codeMode, onEvent, onAssistantDelta, ledger
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message ?: "unexpected error"}"
            }
            val ok = !isFailureResult(result)
            if (ok) {
                TaskQueue.markDone(item.id)
                done++
            } else {
                TaskQueue.markFailed(item.id)
                failed++
            }
            lines += (if (ok) "✅ " else "❌ ") + item.title + " — " +
                result.lineSequence().firstOrNull().orEmpty().take(90)
        }
        return buildString {
            append("Completed $done of ${items.size} tasks")
            if (failed > 0) append(" ($failed failed)")
            append(".")
            lines.forEach { append("\n").append(it) }
        }
    }

    private fun isFailureResult(result: String): Boolean =
        result.startsWith("I couldn't") ||
            result.startsWith("Please configure") ||
            result.startsWith("Failed:") ||
            result.contains("failed before the task could finish")

    /**
     * Cheap compound detection: explicit sequencing separators
     * (" and then ", " then ", "; "), numbered items, multiple lines,
     * or at least two imperative verbs.
     */
    private fun looksCompound(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains(" and then ") || lower.contains(" then ")) return true
        if (text.contains("; ") || text.contains(";\n")) return true
        if (text.lines().count { it.isNotBlank() } >= 2) return true
        if (NUMBERED_ITEM_REGEX.findAll(text).count() >= 2) return true
        if (IMPERATIVE_REGEX.findAll(lower).count() >= 2) return true
        return false
    }

    /**
     * Split a compound request into ordered sub-tasks. One lightweight
     * model call (active chat model, no tools, strict JSON array of
     * strings); ANY failure — no key, provider error, unparseable
     * output, fewer than 2 items — falls back to a regex split.
     */
    private suspend fun splitTasks(text: String, providerStore: ProviderStore): List<String> {
        try {
            val outcome = FailoverRouter(providerStore.orderedConfigs(), null).chat(
                messages = listOf(
                    ChatMsg("system", SPLIT_SYSTEM_PROMPT),
                    ChatMsg("user", text)
                ),
                preferReasoningModel = false
            )
            if (outcome is ChatOutcome.Success) {
                parseJsonStringArray(outcome.reply)?.let { parsed ->
                    if (parsed.size >= 2) return parsed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No key / provider down / bad output → regex fallback below.
        }
        return regexSplit(text)
    }

    private fun parseJsonStringArray(reply: String): List<String>? {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val array = JSONArray(reply.substring(start, end + 1))
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).trim().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Separator split used when the model splitter is unavailable. */
    private fun regexSplit(text: String): List<String> {
        val unnumbered = text
            .replace(Regex("(?m)^\\s*\\d+[.)]\\s*"), "\n")
            .replace(Regex("\\s+\\d+[.)]\\s+(?=[A-Za-z])"), "\n")
        return unnumbered
            .split(Regex("(?i)\\s+and then\\s+|\\s+then\\s+|;\\s*|\\n+|\\s*,\\s+and\\s+"))
            .map { piece ->
                piece.trim().trim('.', '!', ' ')
                    .removePrefix("and ").trim()
            }
            .filter { it.length > 1 }
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

    private const val SPLIT_SYSTEM_PROMPT =
        "You split one compound phone command into its ordered sub-tasks. " +
            "Reply with ONLY a strict JSON array of strings — each string one " +
            "self-contained sub-task, in the order they must run. No markdown, " +
            "no commentary, no numbering inside the strings."

    private val NUMBERED_ITEM_REGEX = Regex("\\d+[.)]\\s+\\S")

    private val IMPERATIVE_REGEX = Regex(
        "\\b(open|play|send|call|set|turn|search|find|create|add|delete|remove|" +
            "message|text|remind|check|show|take|start|stop|post|share|download|book)\\b"
    )
}

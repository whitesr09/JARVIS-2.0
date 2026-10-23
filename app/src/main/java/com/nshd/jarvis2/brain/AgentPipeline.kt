package com.nshd.jarvis2.brain

import android.content.Context
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.core.AppLog
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
        // F6: EVERY turn registers in the TaskQueue registry, not just
        // compound splits — before this, single tasks and quick
        // commands never submitted anything, so the HUD (which lists
        // TaskQueue items) sat at "0 of 0 done — idle" while they ran.
        // The compound path re-submits with its real sub-task titles,
        // replacing this single registration; finishRunning() below
        // only ever closes a still-RUNNING item, so queue sub-tasks
        // (marked individually) are never double-marked.
        val turnTitle = text.trim().take(60)
        if (turnTitle.isNotBlank()) TaskQueue.beginTask(turnTitle)
        val turnStartedAt = System.currentTimeMillis()
        return try {
            val result = withContext(Dispatchers.IO) {
                dispatch(
                    context, text, settings, memoryDb, providerStore,
                    codeMode, job, onEvent, onAssistantDelta
                )
            }
            val success = !result.startsWith("I couldn't") &&
                !result.startsWith("Please configure")
            // F7: panel outcome line + chat-only plan collapse. A
            // "generic" plan whose item never executed a tool was a
            // chat answer, not an action — render the honest 2-step
            // plan ["Understand goal","Answer"] instead of pretending
            // four action steps ran (screenshot spec, Part B).
            TaskQueue.runningItem()?.let { running ->
                if (running.planKind == "generic" && !running.toolsExecuted) {
                    TaskQueue.setPlan(
                        running.id,
                        listOf("Understand goal", "Answer"),
                        kind = "chat"
                    )
                    TaskQueue.setPlanStep(running.id, 0, PlanStepState.DONE)
                    TaskQueue.setPlanStep(running.id, 1, PlanStepState.DONE)
                }
                TaskQueue.setResultLine(
                    running.id,
                    result.lineSequence().firstOrNull().orEmpty()
                )
            }
            TaskQueue.finishRunning(success)
            AppLog.i(
                "Queue",
                "turn '${turnTitle.take(40)}' finished in " +
                    "${System.currentTimeMillis() - turnStartedAt}ms " +
                    "(ok=$success)"
            )
            TaskState.finish(
                success = success,
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
            TaskQueue.finishRunning(false)
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
        AppLog.d("Agent", "turn received (${clean.length} chars)")

        val hudEvent: (ToolEvent) -> Unit = { event ->
            TaskState.step(event.hudText)
            // F6: the running queue item carries the same friendly
            // step line (ToolExecutor refines it with the argument
            // gist when the tool actually starts).
            if (event.kind != ToolEventKind.INFO) {
                TaskQueue.setStepForRunning(event.chipText.take(80))
            }
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
        // F7 ORDER GUARANTEE: the quick router runs FIRST in dispatch
        // — before the editor-code route, before the splitter, before
        // the generic loop — for chat AND Universal voice (both funnel
        // into handleUserText; Live is excluded by design). Quick
        // results are TERMINAL: runQuick returns the tool's own result
        // sentence as the reply, no extra model summary call.
        quickCommand(context, clean)?.let { quick ->
            AppLog.i("Agent", "route=quick-${quick.toolName}")
            return@withContext runQuick(context, quick, memoryDb, ExecutionLedger(), hudEvent)
        }

        // ── Write-code-into-editor route (F6 D2-4) ────────────────────
        // "Write a code for X in QuickEdit" — the CHAT MODEL generates
        // the complete code (same brain as chat), then the insert_code
        // machinery delivers it into the editor. Sits with the media
        // fast paths: after deterministic quick commands, before the
        // compound splitter and the generic agent loop.
        if (isEditorCodeRequest(clean)) {
            AppLog.i("Agent", "route=code")
            return@withContext runEditorCodeRoute(
                context, clean, settings, memoryDb, providerStore,
                codeMode, hudEvent
            )
        }

        // ── Multitask queue (F3): compound request → ordered sub-tasks ─
        // F7 SPLITTER DISCIPLINE: the LLM splitter call may ONLY run
        // when the message genuinely looks compound (long, or with an
        // explicit sequence conjunction). Everything else takes the
        // instant regex/no-split path — a model round-trip was being
        // spent splitting requests that were never compound.
        if (looksCompound(clean)) {
            val lowerClean = clean.lowercase()
            val llmSplitAllowed = clean.length > 120 ||
                lowerClean.contains(" and then ") ||
                lowerClean.contains(" then ") ||
                lowerClean.contains(" also ")
            val titles: List<String>
            val usedLlm: Boolean
            if (llmSplitAllowed) {
                titles = splitTasks(clean, providerStore)
                usedLlm = true
            } else {
                titles = regexSplit(clean)
                usedLlm = false
            }
            if (titles.size >= 2) {
                AppLog.i(
                    "Agent",
                    "route=${if (usedLlm) "split-llm" else "split-regex"} tasks=${titles.size}"
                )
                return@withContext runTaskQueue(
                    context, titles, settings, memoryDb, providerStore,
                    codeMode, job, hudEvent, onAssistantDelta
                )
            }
            // Splitter could not separate it — fall through and let the
            // agent loop handle the request as a single task.
            AppLog.d("Agent", "compound suspected but <2 sub-tasks — single agent turn")
        }

        // ── Model + tools (single task) ────────────────────────────────
        AppLog.i("Agent", "route=loop")
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
        // F7: declare this command's plan for the floating task panel
        // (deep intents advance it phase by phase; ToolExecutor may
        // still replace the whatsapp plan with the send plan).
        TaskQueue.runningItem()?.let { item ->
            if (item.plan.isEmpty()) {
                val (labels, kind) = planForQuick(quick)
                TaskQueue.setPlan(item.id, labels, kind)
                TaskQueue.setPlanStep(item.id, 0, PlanStepState.DONE)
                if (labels.size > 1) {
                    TaskQueue.setPlanStep(item.id, 1, PlanStepState.RUNNING)
                }
            }
        }
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

    /**
     * F7: the floating-panel plan for a deterministic quick command.
     * Labels quote the user's own query/recipient so the checklist
     * reads like his reference screenshot ("Search 'Rathinamo'").
     */
    private fun planForQuick(quick: QuickCommand): Pair<List<String>, String> {
        return when (quick.toolName) {
            "spotify_play" -> {
                val q = quick.args.optString("query").ifBlank { "song" }
                listOf(
                    "Understand goal", "Open Spotify",
                    "Search '$q'", "Play '$q'"
                ) to "media"
            }
            "youtube_play" -> {
                val q = quick.args.optString("query").ifBlank { "video" }
                listOf(
                    "Understand goal", "Open YouTube",
                    "Search '$q'", "Play first video"
                ) to "media"
            }
            "whatsapp_message" -> {
                val c = quick.args.optString("contact").ifBlank { "contact" }
                listOf(
                    "Understand goal", "Open chat with $c",
                    "Type the message", "Confirm & send"
                ) to "send"
            }
            "insert_code" -> listOf(
                "Understand goal", "Write the code",
                "Insert into QuickEdit", "Verify"
            ) to "editor"
            else -> {
                val action = when (quick.toolName) {
                    "open_app" ->
                        "Open ${quick.args.optString("app").ifBlank { "app" }}"
                    "toggle_torch" -> "Toggle the torch"
                    "set_volume" -> "Set the volume"
                    "set_brightness" -> "Set the brightness"
                    "take_screenshot" -> "Take a screenshot"
                    else -> ToolEvent.prettyTool(quick.toolName).ifBlank { "Do it" }
                }
                listOf("Understand goal", action, "Verify") to "quick"
            }
        }
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
        // F7: the generic agent loop runs the screenshot's generic
        // 4-step plan. ToolExecutor hooks advance it (open_app →
        // step 2 done; first mutating tool → step 3 running; turn
        // end finalizes). Chat-only turns collapse to a 2-step plan
        // in handleUserText (toolsExecuted stays false).
        TaskQueue.runningItem()?.let { item ->
            if (item.plan.isEmpty()) {
                TaskQueue.setPlan(
                    item.id,
                    listOf(
                        "Understand goal",
                        "Open or confirm the target app",
                        "Perform the requested action",
                        "Verify the resulting state"
                    ),
                    kind = "generic"
                )
                TaskQueue.setPlanStep(item.id, 0, PlanStepState.DONE)
                TaskQueue.setPlanStep(item.id, 1, PlanStepState.RUNNING)
            }
        }
        return FailoverRouter(
            configsInOrder = providerStore.orderedConfigs(),
            toolExecutor = executor,
            store = providerStore,
            onSystemNote = { note ->
                onEvent(ToolEvent(ToolEventKind.INFO, "", note))
            }
        ).agentLoop(
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
        AppLog.i("Agent", "queue split into ${items.size} tasks")
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
                // Raw text → AppLog; the queue summary line is friendly.
                AppLog.e(
                    "Agent",
                    "sub-task '${item.title.take(40)}' crashed: " +
                        (e.message ?: "unexpected error"),
                    e
                )
                "Failed: " + com.nshd.jarvis2.ui.FriendlyErrors.shortReason(e.message)
            }
            val ok = !isFailureResult(result)
            AppLog.d(
                "Agent",
                "task ${index + 1}/${items.size} '${item.title.take(40)}' → " +
                    if (ok) "done" else "failed"
            )
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
            // F5.1: the mid-task failure wording ("I couldn't finish
            // that — …") already matches the first check; kept explicit.
            result.startsWith("I couldn't finish")

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
            val outcome = FailoverRouter(
                configsInOrder = providerStore.orderedConfigs(),
                toolExecutor = null,
                store = providerStore
            ).chat(
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

    // ── Write-code-into-editor route (F6 D2-4) ─────────────────────────

    /**
     * Conservative matcher for the editor-code route. Two ways in:
     *  1. the message names an editor (QuickEdit / "quick edit" /
     *     "editor") AND asks for code to be written/generated;
     *  2. QuickEdit is the foreground app AND the message is a
     *     code-generation request ("write a function…", "code for…").
     * A plain coding question with no editor target never matches —
     * it stays a normal chat answer on the code canvas.
     */
    private fun isEditorCodeRequest(text: String): Boolean {
        val lower = text.lowercase()
        val mentionsEditor = lower.contains("quickedit") ||
            lower.contains("quick edit") || lower.contains("editor")
        val asksForCode = CODE_REQUEST_REGEX.containsMatchIn(lower)
        if (mentionsEditor && asksForCode) return true
        if (!asksForCode) return false
        val foreground = com.nshd.jarvis2.service.JarvisAccessibilityService
            .instance?.screenReader()?.currentPackage()
            ?: com.nshd.jarvis2.service.JarvisAccessibilityService.lastPackageName
        return foreground == QUICKEDIT_PACKAGE &&
            CODE_GEN_LEAD_REGEX.containsMatchIn(lower)
    }

    /**
     * Generate the code with the configured chat model (streaming
     * router chat, persona prompt + an output-shape instruction),
     * harvest the code, and insert it via the insert_code tool
     * machinery. Insertion failure never loses the work: the full
     * generated code falls back into the chat reply (code canvas).
     */
    private suspend fun runEditorCodeRoute(
        context: Context,
        userText: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean,
        onEvent: (ToolEvent) -> Unit
    ): String {
        onEvent(
            ToolEvent(
                ToolEventKind.INFO, "",
                "Writing code into QuickEdit with the chat model…"
            )
        )
        TaskQueue.setStepForRunning("Writing the code…")
        // F7 plan: [Understand goal ✓, Write the code (running),
        // Insert into QuickEdit, Verify].
        TaskQueue.setPlanForRunning(
            listOf("Understand goal", "Write the code", "Insert into QuickEdit", "Verify"),
            kind = "editor"
        )
        TaskQueue.setPlanStepForRunning(0, PlanStepState.DONE)
        TaskQueue.setPlanStepForRunning(1, PlanStepState.RUNNING)
        val facts = memoryDb.allFacts(null, null)
        val systemPrompt = PersonaPrompts.build(settings, facts, codeMode) +
            EDITOR_CODE_INSTRUCTION
        val history = memoryDb.lastMessages(6)
            .filter { it.role == "user" || it.role == "assistant" }
            .map { ChatMsg(role = it.role, content = it.content) }
        val messages = mutableListOf(ChatMsg("system", systemPrompt))
        messages += history
        if (history.lastOrNull()?.content != userText) {
            messages += ChatMsg("user", userText)
        }
        val outcome = FailoverRouter(
            configsInOrder = providerStore.orderedConfigs(),
            toolExecutor = null,
            store = providerStore
        ).chat(messages, preferReasoningModel = codeMode)
        return when (outcome) {
            is ChatOutcome.NoKey ->
                "Please configure your API Key in Settings → API & Connection first."
            is ChatOutcome.AllFailed ->
                "I couldn't write the code — the model couldn't be reached " +
                    "(${outcome.summary})."
            is ChatOutcome.Success -> {
                val harvested = harvestCode(outcome.reply)
                if (harvested == null) {
                    AppLog.w(
                        "Agent",
                        "editor-code route: no code block harvested from a " +
                            "${outcome.reply.length}-char reply — returning it as chat"
                    )
                    outcome.reply.trim()
                } else {
                    val lineCount = harvested.code.lines().count { it.isNotBlank() }
                    AppLog.i(
                        "Agent",
                        "editor-code route: harvested $lineCount lines / " +
                            "${harvested.code.length} chars " +
                            "(lang=${harvested.language ?: "?"}, " +
                            "fenced=${harvested.fenced}) — inserting"
                    )
                    TaskQueue.setStepForRunning("Inserting the code into QuickEdit…")
                    TaskQueue.advancePlanForRunning(2)
                    onEvent(ToolEvent(ToolEventKind.STARTED, "insert_code"))
                    val result = ToolExecutor(
                        context, memoryDb, ExecutionLedger()
                    ).execute(
                        "insert_code",
                        JSONObject().put("code", harvested.code),
                        includeScreenSummary = false
                    )
                    onEvent(
                        ToolEvent(
                            kind = if (result.success) {
                                ToolEventKind.SUCCESS
                            } else {
                                ToolEventKind.FAILURE
                            },
                            toolName = "insert_code",
                            message = result.message
                        )
                    )
                    if (result.success) {
                        val preview = harvested.code.lines().take(6)
                            .joinToString("\n")
                        val lang = harvested.language?.takeIf { it.isNotBlank() }
                        buildString {
                            append("Done — I wrote $lineCount lines of ")
                            append(lang?.let { "$it " } ?: "")
                            append("code into QuickEdit.")
                            append("\n\n```").append(lang ?: "").append('\n')
                            append(preview)
                            if (lineCount > 6) append("\n…")
                            append("\n```")
                        }
                    } else {
                        // No work lost: the full code lands in chat.
                        val lang = harvested.language?.takeIf { it.isNotBlank() }
                        buildString {
                            append("I wrote the code, but couldn't insert it ")
                            append("into QuickEdit — ")
                            append(result.message)
                            append(" Here is the full code:\n\n```")
                            append(lang ?: "").append('\n')
                            append(harvested.code)
                            append("\n```")
                        }
                    }
                }
            }
        }
    }

    private data class HarvestedCode(
        val code: String,
        val language: String?,
        val fenced: Boolean
    )

    /**
     * Code harvesting: the LARGEST fenced block wins. With no fence,
     * the whole trimmed reply is taken only when it is predominantly
     * code (≥ 50% of non-blank lines look like code) — otherwise the
     * reply is prose and the caller shows it as a normal chat answer.
     */
    private fun harvestCode(reply: String): HarvestedCode? {
        val fences = FENCE_REGEX.findAll(reply).toList()
        if (fences.isNotEmpty()) {
            val best = fences.maxByOrNull { it.groupValues[2].length }!!
            val code = best.groupValues[2].trim('\n').trimEnd()
            if (code.isNotBlank()) {
                return HarvestedCode(
                    code = code,
                    language = best.groupValues[1].takeIf { it.isNotBlank() },
                    fenced = true
                )
            }
        }
        val trimmed = reply.trim()
        val lines = trimmed.lines().filter { it.isNotBlank() }
        if (lines.size >= 3) {
            val codeLike = lines.count { CODE_LINE_REGEX.containsMatchIn(it) }
            if (codeLike.toDouble() / lines.size >= 0.5) {
                return HarvestedCode(trimmed, language = null, fenced = false)
            }
        }
        return null
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

    /**
     * F7 normalization for command matching (chat + Universal STT):
     * trim, collapse whitespace runs, strip trailing sentence
     * punctuation [.!?…]+. STT appends periods, and the anchored
     * quick patterns silently MISSED "play rathinamo on spotify."
     * before this — matching runs on the normalized string; captured
     * groups keep their original casing for the query/contact/message.
     */
    private fun normalizeCommandText(raw: String): String =
        raw.trim()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', '!', '?', '…', ' ')
            .trim()

    /** Drop a trailing " video" from a YouTube query ("mr beast video" → "mr beast"). */
    private fun stripVideoSuffix(query: String): String {
        val trimmed = query.trim()
        return if (trimmed.lowercase().endsWith(" video")) {
            trimmed.dropLast(" video".length).trim()
        } else {
            trimmed
        }
    }

    private fun quickCommand(context: Context, rawText: String): QuickCommand? {
        val text = normalizeCommandText(rawText)
        val lower = text.lowercase()

        // ── WhatsApp sends (F7 expanded). Every hit still runs the
        // whatsapp_message tool, whose own ConfirmGate confirms with
        // recipient + full text — his standing rule is untouched.
        Regex("(?i)^send\\s+(?:a\\s+)?(?:message|whatsapp\\s+message)\\s+to\\s+(.+?)\\s+(?:saying|that\\s+says)\\s+(.+)$")
            .find(text)?.let { match ->
                AppLog.d("Send", "route hit: send-to-saying pattern")
                return QuickCommand(
                    "whatsapp_message",
                    JSONObject()
                        .put("contact", match.groupValues[1].trim())
                        .put("message", match.groupValues[2].trim())
                )
            }
        Regex("(?i)^send\\s+(?:a\\s+)?(?:message|whatsapp\\s+message)\\s+to\\s+(.+?):\\s*(.+)$")
            .find(text)?.let { match ->
                AppLog.d("Send", "route hit: send-to-colon pattern")
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
        Regex("(?i)^send\\s+(.+?)\\s+(?:a\\s+)?(?:message|whatsapp)(?:\\s+saying)?\\s*[: ]\\s*(.+)$")
            .find(text)?.let { match ->
                val contactGuess = match.groupValues[1].trim()
                // "send a message to X" (no body) would mis-parse with
                // contact "a" — that shape is P1's job above; never let
                // the article become a contact name.
                if (!contactGuess.equals("a", ignoreCase = true)) {
                    AppLog.d("Send", "route hit: send-<name>-message pattern")
                    return QuickCommand(
                        "whatsapp_message",
                        JSONObject()
                            .put("contact", contactGuess)
                            .put("message", match.groupValues[2].trim())
                    )
                }
            }
        Regex("(?i)^whatsapp\\s+(.+?):\\s*(.+)$").find(text)?.let { match ->
            return QuickCommand(
                "whatsapp_message",
                JSONObject()
                    .put("contact", match.groupValues[1].trim())
                    .put("message", match.groupValues[2].trim())
            )
        }
        Regex("(?i)^whatsapp\\s+(.+?)\\s+[: ]\\s*(.+)$").find(text)?.let { match ->
            AppLog.d("Send", "route hit: whatsapp-<name> pattern")
            return QuickCommand(
                "whatsapp_message",
                JSONObject()
                    .put("contact", match.groupValues[1].trim())
                    .put("message", match.groupValues[2].trim())
            )
        }
        Regex("(?i)^message\\s+(.+?)\\s+[: ]\\s*(.+)$").find(text)?.let { match ->
            AppLog.d("Send", "route hit: message-<name> pattern")
            return QuickCommand(
                "whatsapp_message",
                JSONObject()
                    .put("contact", match.groupValues[1].trim())
                    .put("message", match.groupValues[2].trim())
            )
        }

        // ── Media fast paths (F6 + F7 expansion) ─────────────────────
        Regex("(?i)^play\\s+(.+?)\\s+(?:on|in)\\s+youtube$").find(text)?.let { match ->
            return QuickCommand(
                "youtube_play",
                JSONObject().put("query", stripVideoSuffix(match.groupValues[1]))
            )
        }
        Regex("(?i)^open\\s+youtube\\s+and\\s+play\\s+(.+)$").find(text)?.let { match ->
            return QuickCommand(
                "youtube_play",
                JSONObject().put("query", stripVideoSuffix(match.groupValues[1]))
            )
        }
        Regex("(?i)^play\\s+(.+?)\\s+video$").find(text)?.let { match ->
            return QuickCommand(
                "youtube_play",
                JSONObject().put("query", match.groupValues[1].trim())
            )
        }
        Regex("(?i)^watch\\s+(.+)$").find(text)?.let { match ->
            return QuickCommand(
                "youtube_play",
                JSONObject().put("query", stripVideoSuffix(match.groupValues[1]))
            )
        }

        // F6: Spotify fast path — one programmatic deep intent
        // (open → search → play) instead of ~6 generic agent-loop
        // steps. These patterns must precede the generic "^open"
        // quick command below, which would otherwise swallow
        // "open spotify and play …" as an app name.
        Regex("(?i)^open\\s+spotify\\s+and\\s+play\\s+(.+)$").find(text)?.let { match ->
            return QuickCommand(
                "spotify_play",
                JSONObject().put("query", match.groupValues[1].trim())
            )
        }
        Regex("(?i)^spotify\\s*,?\\s*play\\s+(.+)$").find(text)?.let { match ->
            return QuickCommand(
                "spotify_play",
                JSONObject().put("query", match.groupValues[1].trim())
            )
        }
        Regex("(?i)^play\\s+(.+?)\\s+(?:on|in)\\s+spotify(?:\\s+app)?$").find(text)?.let { match ->
            return QuickCommand(
                "spotify_play",
                JSONObject().put("query", match.groupValues[1].trim())
            )
        }
        // "spotify <query>" — only when the remainder is a query, not
        // another command ("spotify pause" stays a model request).
        Regex("(?i)^spotify\\s+(.+)$").find(text)?.let { match ->
            val query = match.groupValues[1].trim()
            val queryLower = query.lowercase()
            val startsWithCommand = SPOTIFY_COMMAND_STARTS.any {
                queryLower == it || queryLower.startsWith("$it ")
            }
            if (query.isNotBlank() && !startsWithCommand) {
                return QuickCommand(
                    "spotify_play",
                    JSONObject().put("query", query)
                )
            }
        }
        // F7: bare "play <query>" — DEFAULT music app = Spotify
        // (his standing media app; "play mr beast video" and explicit
        // platform mentions are already claimed above).
        Regex("(?i)^play\\s+(.+)$").find(text)?.let { match ->
            return QuickCommand(
                "spotify_play",
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

    // ── F6 D2-4 editor-code route constants ────────────────────────────

    private const val QUICKEDIT_PACKAGE = "com.rhmsoft.edit"

    private const val EDITOR_CODE_INSTRUCTION =
        "\n\nEDITOR CODE REQUEST: He wants this code written INTO his " +
            "QuickEdit editor. Reply with the COMPLETE, working code for " +
            "his request as ONE fenced code block with a language tag — " +
            "the full code, never a sketch or a fragment. At most one " +
            "short sentence of prose outside the fence."

    /** "write/generate/create/make … code/program/script" or "code for …". */
    private val CODE_REQUEST_REGEX = Regex(
        "\\b(write|generate|create|make|build)\\b[^.?!]{0,60}\\b(code|program|script|function|class)\\b" +
            "|\\bcode\\s+for\\b"
    )

    /** Conservative lead-in for the foreground-QuickEdit match. */
    private val CODE_GEN_LEAD_REGEX = Regex(
        "^\\s*(write|generate|create|make|build)\\b|\\bcode\\s+for\\b"
    )

    /** One fenced block: ```lang … ``` (language tag optional). */
    private val FENCE_REGEX = Regex(
        "```([A-Za-z0-9#+._-]*)\\s*\\n(.*?)```",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Heuristic "this line looks like code" for the no-fence harvest. */
    private val CODE_LINE_REGEX = Regex(
        "[{};()]|\\b(fun|val|var|class|object|import|package|def|function|return|" +
            "if|else|for|while|when|public|private|static|void|int|long|let|const|" +
            "var|new|try|catch|extends|implements|#include|using|namespace)\\b|" +
            "System\\.|console\\.|print\\(|println\\("
    )

    /** F7: remainders after "spotify …" that are commands, not queries. */
    private val SPOTIFY_COMMAND_STARTS = listOf(
        "play", "open", "pause", "stop", "next", "previous", "skip",
        "resume", "shuffle", "repeat", "volume"
    )

    private val NUMBERED_ITEM_REGEX = Regex("\\d+[.)]\\s+\\S")

    private val IMPERATIVE_REGEX = Regex(
        "\\b(open|play|send|call|set|turn|search|find|create|add|delete|remove|" +
            "message|text|remind|check|show|take|start|stop|post|share|download|book)\\b"
    )
}

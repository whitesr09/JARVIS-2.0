package com.nshd.jarvis2.brain

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.tools.ConfirmGate
import com.nshd.jarvis2.brain.tools.SendAnswer
import com.nshd.jarvis2.brain.tools.SendAsk
import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.core.BrainStore
import com.nshd.jarvis2.core.MacroRecorder
import com.nshd.jarvis2.core.ScheduledAlarms
import com.nshd.jarvis2.hands.ActionOutcome
import com.nshd.jarvis2.hands.ContactsResolver
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.hands.LocationHelper
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.hands.TaskStoppedException
import com.nshd.jarvis2.memory.Fact
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.service.JarvisNotificationListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

    /**
     * v2.0.0 (#16): set when this turn was itself a macro replay or a
     * custom-command re-dispatch — such turns never feed the "save as
     * a command" learning (a replay must not offer to save itself).
     */
    @Volatile
    private var suppressMacroNote = false

    suspend fun handleUserText(
        context: Context,
        text: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean = false,
        onEvent: (ToolEvent) -> Unit = {},
        onAssistantDelta: (String) -> Unit = {},
        /** Plan #71: an image attached in chat, forwarded to the model. */
        imageBase64: String? = null
    ): String {
        // v2.0.2 SendAsk host (typed-chat path): if a just-in-time
        // send ask is pending, THIS message is its answer — checked
        // BEFORE any normal routing, and never routed as a command.
        // NO mic auto-start on this path: the user typed the answer.
        SendAsk.pending.value?.let { pending ->
            return when (SendAsk.answer(text)) {
                SendAnswer.YES -> "Confirmed — sending now."
                SendAnswer.NO -> "Not sent."
                SendAnswer.NON_ANSWER -> "Say yes to send, or no to cancel.\n\n" +
                    pending.askLine
            }
        }
        // v2.0.2: while this turn runs, send asks stream to the
        // caller as plain chat/caption deltas (never spoken). The
        // sink is cleared in the finally below, so a later turn can
        // never deliver an ask into a stale bubble.
        SendAsk.askSink = onAssistantDelta
        try {
            return handleUserTextInner(
                context, text, settings, memoryDb, providerStore,
                codeMode, onEvent, onAssistantDelta, imageBase64
            )
        } finally {
            SendAsk.askSink = null
        }
    }

    /**
     * The pre-v2.0.2 handleUserText body, unchanged except that the
     * v2.0.2 wrapper above owns the send-ask intercept + sink.
     */
    private suspend fun handleUserTextInner(
        context: Context,
        text: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean = false,
        onEvent: (ToolEvent) -> Unit = {},
        onAssistantDelta: (String) -> Unit = {},
        imageBase64: String? = null
    ): String {
        suppressMacroNote = false
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
            val rawResult = withContext(Dispatchers.IO) {
                dispatch(
                    context, text, settings, memoryDb, providerStore,
                    codeMode, job, onEvent, onAssistantDelta, imageBase64
                )
            }
            val success = !rawResult.startsWith("I couldn't") &&
                !rawResult.startsWith("Please configure")
            // ── v2.0.0 (#16): learn from successful turns ──────────
            // A successful turn that executed ≥2 tools is a macro
            // candidate. The FIRST time the same goal succeeds, it is
            // counted silently; the SECOND time, the reply offers
            // "save that as a command" (the save itself is a separate
            // explicit command, so nothing is ever saved unasked).
            var result = rawResult
            val recordedSteps = MacroRecorder.snapshot()
            if (success && !suppressMacroNote && recordedSteps.size >= 2) {
                val goalKey = BrainStore.normalizeKey(text)
                val alreadyMacro = BrainStore.findMacro(context, goalKey) != null
                val count = BrainStore.noteSuccessfulTurn(
                    context, text.trim(), recordedSteps
                )
                if (count == 2 && !alreadyMacro) {
                    AppLog.i(
                        "Agent",
                        "macro offer: goal succeeded 2× (${recordedSteps.size} steps)"
                    )
                    result += "\n\n💾 You've asked me to do this twice now — " +
                        "say \"save that as a command\" and I'll save these " +
                        "${recordedSteps.size} steps so next time it runs " +
                        "instantly, with no model call."
                }
            }
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
                "turn finished in ${System.currentTimeMillis() - turnStartedAt}ms " +
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
        } catch (e: TaskStoppedException) {
            // #61: a user stop is a CLEAN cancellation — caught BEFORE
            // the generic handler so it can never be treated as a
            // failure, retried, or failed over to another provider,
            // and no action is ever replayed after it.
            TaskQueue.finishRunning(false)
            TaskState.finish(false, "Stopped")
            "Stopped."
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
        onAssistantDelta: (String) -> Unit = {},
        imageBase64: String? = null,
        depth: Int = 0
    ): String = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext "I didn't catch that — please try again."
        AppLog.d("Agent", "turn received (${clean.length} chars)")
        // v2.0.0 (#16): arm the macro recorder for this turn (a
        // custom-command re-dispatch shares the OUTER turn's record).
        if (depth == 0) MacroRecorder.begin()

        // ── F8 (#15): stop / cancel — abort the running task. Runs
        // BEFORE TaskState.begin so TaskState still holds the RUNNING
        // task's job: abort() cancels that pipeline coroutine and
        // aborts the whole TaskQueue (queued sub-tasks never run).
        if (isStopCommand(clean)) {
            AppLog.i("Agent", "route=quick-stop")
            TaskState.abort()
            return@withContext "Stopped — I cancelled the current task " +
                "and cleared the queue."
        }

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
        // #61: honour a stop that raced in before this turn's work.
        TaskState.throwIfStopRequested()

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

        // ── #75: "forget X" — delete matching Memory Core facts ─────
        // Runs for chat AND voice (both funnel through handleUserText);
        // the returned text is the reply either way. Never deletes on
        // an empty target, and a <3-char target is too broad to be safe.
        extractForgetTarget(clean)?.let { target ->
            if (target.length < 3) {
                return@withContext "That's too vague to forget safely — " +
                    "tell me exactly what to forget."
            }
            val matches = memoryDb.allFacts(null, null).filter { fact ->
                fact.text.contains(target, ignoreCase = true) ||
                    fact.category.equals(target, ignoreCase = true)
            }
            if (matches.isEmpty()) {
                return@withContext "I couldn't find anything in memory " +
                    "matching \"$target\" — nothing forgotten."
            }
            matches.forEach { memoryDb.deleteFact(it.id) }
            AppLog.i("Agent", "route=forget removed=${matches.size}")
            return@withContext "Forgot: " +
                matches.joinToString("; ") { it.text }
        }

        // ── #52: contact-correction learning ─────────────────────────
        // "not Amal, Aimal" / "I said Aimal not Amal" → save the alias
        // in ContactsResolver's own format (Contacts fact "wrong = right")
        // so future calls/messages resolve the corrected name.
        extractContactCorrection(clean)?.let { (wrong, right) ->
            saveContactAlias(memoryDb, wrong, right)
            AppLog.i("Agent", "route=contact-correction alias matched")
            return@withContext "Got it — $wrong means $right. Saved."
        }

        // #61: a stop pressed during the local routes above wins here.
        TaskState.throwIfStopRequested()

        // ── v2.0.0 Brain routes ─────────────────────────────────────
        // Learned structures, all deterministic and model-free, run
        // BEFORE the quick router: teach/save commands, scheduled
        // sends (whose text would otherwise match the immediate-send
        // patterns), routine create/run, macro triggers and custom
        // commands. Order inside the block matters and is deliberate.
        val brainReply = brainRoutes(
            context, clean, settings, memoryDb, providerStore,
            codeMode, hudEvent, onAssistantDelta, depth
        )
        if (brainReply != null) return@withContext brainReply

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

        // ── v2.0.0 (#22): on-device intent classifier ───────────────
        // AFTER the exact quick patterns, BEFORE the model: catches
        // paraphrases ("make it louder", "ring Mom") for the top
        // commands with a conservative score+margin gate. A miss
        // falls through to the editor route / splitter / agent loop
        // exactly as before.
        IntentClassifier.classify(context, clean)?.let { hit ->
            AppLog.i("Agent", "route=classifier-${hit.intent}")
            val quick = QuickCommand(
                toolName = hit.toolName,
                args = hit.args,
                directReply = when (hit.directReplyKind) {
                    "battery" -> batteryReply(context)
                    "time" -> timeReply()
                    "date" -> dateReply()
                    else -> null
                }
            )
            return@withContext runQuick(
                context, quick, memoryDb, ExecutionLedger(), hudEvent
            )
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

        // ── v1.9.0 (#73): auto-extract a durable fact into the
        // REVIEW QUEUE (never straight into memory — he approves it
        // in the Memory Core). Silent: the turn continues normally.
        autoExtractCandidate(clean)?.let { (category, fact) ->
            val known = memoryDb.allFacts(null, null) +
                memoryDb.pendingFacts()
            if (known.none { it.text.equals(fact, ignoreCase = true) }) {
                memoryDb.addPendingFact(category, fact)
                AppLog.i(
                    "Agent",
                    "route=auto-extract → review queue (${fact.length} chars)"
                )
            }
        }

        // ── v1.9.0 (#82): fold old history into a memory note when
        // the table grows past the threshold (background, best-effort).
        housekeepingScope.launch {
            HistoryCompactor.maybeCompact(memoryDb, providerStore)
        }

        // ── Model + tools (single task) ────────────────────────────────
        AppLog.i("Agent", "route=loop")
        runAgentTurn(
            context, clean, settings, memoryDb, providerStore,
            codeMode, hudEvent, onAssistantDelta, ExecutionLedger(),
            imageBase64 = imageBase64
        )
    }

    // ── v2.0.0 Brain routes ──────────────────────────────────────────

    /**
     * The learned-structure routes. Returns the turn's reply when a
     * route matched, or null to continue down the normal pipeline
     * (quick commands → classifier → editor route → splitter → loop).
     */
    private suspend fun brainRoutes(
        context: Context,
        clean: String,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        codeMode: Boolean,
        hudEvent: (ToolEvent) -> Unit,
        onAssistantDelta: (String) -> Unit,
        depth: Int
    ): String? {
        val normalized = normalizeCommandText(clean)

        // ── #16: "save that as a command [called X]" ────────────────
        val saveMatch = Regex(
            "(?i)^save (?:that|this) as a (?:quick )?command(?: called (.+))?$"
        ).matchEntire(normalized) ?: Regex(
            "(?i)^make (?:that|this) a (?:quick )?command(?: called (.+))?$"
        ).matchEntire(normalized)
        if (saveMatch != null) {
            AppLog.i("Agent", "route=macro-save")
            val candidate = BrainStore.lastCandidate(context)
                ?: return "There's no finished task to save yet — ask me " +
                    "to do something first, then say \"save that as a command\"."
            val trigger = saveMatch.groupValues.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() } ?: candidate.goal
            BrainStore.saveMacro(
                context,
                BrainStore.Macro(
                    trigger = trigger,
                    steps = candidate.steps,
                    sourceGoal = candidate.goal
                )
            )
            BrainStore.clearCandidate(context)
            suppressMacroNote = true
            return "Saved ✅ — the ${candidate.steps.size} steps from " +
                "\"${candidate.goal}\" are now the quick command " +
                "\"$trigger\". Say \"$trigger\" any time and I'll run " +
                "them instantly, with no model call. (Any send inside " +
                "still asks you to confirm first.)"
        }

        // ── Custom quick commands: "when I say X, do Y" ─────────────
        val teachMatch = Regex(
            "(?i)^when i say (.+?)\\s*,\\s*(?:do|run|execute)\\s+(.+)$"
        ).matchEntire(normalized) ?: Regex(
            "(?i)^when i say (.+?)\\s+(?:do|run|execute)\\s+(.+)$"
        ).matchEntire(normalized) ?: Regex(
            "(?i)^when i say (.+?)\\s*,\\s*(.+)$"
        ).matchEntire(normalized)
        if (teachMatch != null) {
            val phrase = teachMatch.groupValues[1].trim()
            val target = teachMatch.groupValues[2].trim()
            if (phrase.length >= 2 && target.isNotBlank()) {
                AppLog.i("Agent", "route=custom-teach saved")
                BrainStore.saveCustomCommand(
                    context, BrainStore.CustomCommand(phrase, target)
                )
                suppressMacroNote = true
                return "Got it ✅ — when you say \"$phrase\", I'll run: \"$target\"."
            }
        }

        // ── #51: scheduled message (gate at schedule time) ──────────
        tryScheduleMessage(context, clean)?.let { reply ->
            AppLog.i("Agent", "route=scheduled-message")
            suppressMacroNote = true
            return reply
        }

        // ── #115: routine create ─────────────────────────────────────
        val routineCreate = Regex(
            "(?i)^create (?:a )?routine (?:called )?(.+?)\\s*:\\s*(.+)$"
        ).matchEntire(normalized) ?: Regex(
            "(?i)^create (?:a )?routine (?:called )?(.+?) with steps:?\\s+(.+)$"
        ).matchEntire(normalized)
        if (routineCreate != null) {
            val name = routineCreate.groupValues[1].trim()
            val steps = routineCreate.groupValues[2]
                .split(Regex("\\s*;\\s*|\\s+and then\\s+|\\s*\\n\\s*"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (name.isNotBlank() && steps.isNotEmpty()) {
                AppLog.i("Agent", "route=routine-create (${steps.size} steps)")
                BrainStore.saveRoutine(
                    context,
                    BrainStore.Routine(
                        name = name,
                        trigger = "MANUAL",
                        steps = steps
                    )
                )
                suppressMacroNote = true
                return "Routine \"$name\" saved with ${steps.size} step(s) ✅ — " +
                    "say \"run routine $name\" to run it. You can make it " +
                    "run on wake or app-open in Settings → Brain Upgrade."
            }
        }

        // ── #115: routine run ────────────────────────────────────────
        val routineRun = Regex(
            "(?i)^(?:run|start|do) (?:the |my )?routine (.+)$"
        ).matchEntire(normalized) ?: Regex(
            "(?i)^(?:run|start) my (.+?) routine$"
        ).matchEntire(normalized)
        if (routineRun != null) {
            val name = routineRun.groupValues[1].trim()
            val routine = BrainStore.findRoutine(context, name)
            if (routine == null) {
                val known = BrainStore.routines(context)
                return if (known.isEmpty()) {
                    "You don't have a routine called \"$name\" yet — " +
                        "create one with \"create routine $name: step 1; step 2\"."
                } else {
                    "No routine called \"$name\". Your routines: " +
                        known.joinToString(", ") { it.name } + "."
                }
            }
            AppLog.i("Agent", "route=routine-run requested")
            suppressMacroNote = true
            return runRoutineSteps(
                context, routine, settings, memoryDb, providerStore,
                hudEvent, registerTask = false
            )
        }

        if (depth == 0) {
            // ── #17: macro trigger (exact phrase) ───────────────────
            BrainStore.findMacro(context, BrainStore.normalizeKey(clean))
                ?.let { macro ->
                    AppLog.i(
                        "Agent",
                        "route=macro-replay (${macro.steps.size} steps)"
                    )
                    suppressMacroNote = true
                    return runMacro(context, macro, memoryDb, hudEvent)
                }

            // ── Custom command trigger (exact phrase) ───────────────
            BrainStore.findCustomCommand(
                context, BrainStore.normalizeKey(clean)
            )?.let { command ->
                AppLog.i(
                    "Agent",
                    "route=custom-command dispatched"
                )
                suppressMacroNote = true
                return dispatch(
                    context, command.target, settings, memoryDb,
                    providerStore, codeMode,
                    job = currentCoroutineContext()[Job] ?: Job(),
                    onEvent = hudEvent,
                    onAssistantDelta = onAssistantDelta,
                    imageBase64 = null,
                    depth = 1
                )
            }
        }
        return null
    }

    /**
     * #17: macro replay — the recorded tool sequence runs through a
     * fresh ToolExecutor + fresh ExecutionLedger, with NO model call.
     * Every step keeps its normal safety behaviour: a recorded send
     * hits the always-confirm gate again (recipient + full text),
     * because the replay calls the same tool implementations.
     */
    private suspend fun runMacro(
        context: Context,
        macro: BrainStore.Macro,
        memoryDb: MemoryDb,
        onEvent: (ToolEvent) -> Unit
    ): String {
        // A replay never records itself into a new candidate.
        MacroRecorder.disarm()
        TaskQueue.setPlanForRunning(
            macro.steps.map { ToolEvent.prettyTool(it.tool) },
            kind = "macro"
        )
        val executor = ToolExecutor(context, memoryDb, ExecutionLedger())
        val lines = mutableListOf<String>()
        var done = 0
        for ((index, step) in macro.steps.withIndex()) {
            TaskState.throwIfStopRequested()
            TaskQueue.setPlanStepForRunning(index, PlanStepState.RUNNING)
            val args = try {
                JSONObject(step.argsJson)
            } catch (e: Exception) {
                JSONObject()
            }
            val result = executor.execute(
                step.tool, args, includeScreenSummary = false
            )
            if (result.success) done++
            TaskQueue.setPlanStepForRunning(
                index,
                if (result.success) PlanStepState.DONE else PlanStepState.FAILED
            )
            lines += (if (result.success) "✅ " else "❌ ") +
                ToolEvent.prettyTool(step.tool) + " — " +
                result.message.lineSequence().firstOrNull().orEmpty().take(90)
            if (result.outcome in setOf(
                    ActionOutcome.STAGED_FOR_USER,
                    ActionOutcome.SUBMITTED_UNVERIFIED,
                    ActionOutcome.OUTCOME_UNKNOWN
                )
            ) {
                lines += "Stopped remaining steps: this external action needs user review before any retry."
                AppLog.w(
                    "Agent",
                    "macro stopped after " + step.tool + " outcome=" + result.outcome
                )
                break
            }
        }
        return buildString {
            append("Ran your \"${macro.trigger}\" command — ")
            append("$done of ${macro.steps.size} steps done.")
            lines.forEach { append("\n").append(it) }
        }
    }

    /**
     * #115: run one routine's steps through the same per-step paths
     * the task queue uses (quick commands first, then the agent
     * loop), each with a fresh ledger. Shared by the chat/voice
     * "run routine" route ([registerTask] = false — the turn already
     * owns a task item) and by RoutineRunner's headless wake /
     * app-open runs ([registerTask] = true).
     */
    suspend fun runRoutineSteps(
        context: Context,
        routine: BrainStore.Routine,
        settings: JarvisSettings,
        memoryDb: MemoryDb,
        providerStore: ProviderStore,
        onEvent: (ToolEvent) -> Unit,
        registerTask: Boolean = true
    ): String {
        val job = currentCoroutineContext()[Job] ?: Job()
        if (registerTask) TaskQueue.beginTask("Routine: ${routine.name}")
        val lines = mutableListOf<String>()
        var done = 0
        var failed = 0
        try {
            for ((index, stepText) in routine.steps.withIndex()) {
                TaskState.throwIfStopRequested()
                currentCoroutineContext().ensureActive()
                TaskState.begin(goal = stepText.take(60), job = job, maxSteps = 10)
                onEvent(
                    ToolEvent(
                        ToolEventKind.INFO, "",
                        "Routine step ${index + 1}/${routine.steps.size}: $stepText"
                    )
                )
                val result = try {
                    val quick = quickCommand(context, stepText)
                    if (quick != null) {
                        runQuick(
                            context, quick, memoryDb,
                            ExecutionLedger(), onEvent
                        )
                    } else {
                        runAgentTurn(
                            context, stepText, settings, memoryDb,
                            providerStore, codeMode = false,
                            onEvent = onEvent,
                            onAssistantDelta = {},
                            ledger = ExecutionLedger()
                        )
                    }
                } catch (e: TaskStoppedException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e(
                        "Brain",
                        "routine step failed (${e.javaClass.simpleName})"
                    )
                    "Failed: " +
                        com.nshd.jarvis2.ui.FriendlyErrors.shortReason(e.message)
                }
                val requiresOutcomeReview =
                    result.startsWith("STAGED_FOR_USER") ||
                        result.startsWith("SUBMITTED_UNVERIFIED") ||
                        result.startsWith("OUTCOME_UNKNOWN")
                val ok = !requiresOutcomeReview && !isFailureResult(result)
                if (ok) done++ else failed++
                lines += (if (ok) "✅ " else "❌ ") + stepText + " — " +
                    result.lineSequence().firstOrNull().orEmpty().take(90)
                if (requiresOutcomeReview) {
                    lines += "Stopped remaining routine steps: inspect the external app before retrying this action."
                    AppLog.w(
                        "Agent",
                        "routine stopped after uncertain action outcome at step " + (index + 1)
                    )
                    break
                }
            }
        } finally {
            if (registerTask) TaskQueue.finishRunning(failed == 0)
        }
        return buildString {
            append("☀️ Routine \"${routine.name}\" — $done of ")
            append("${routine.steps.size} steps done")
            if (failed > 0) append(" ($failed failed)")
            append(".")
            lines.forEach { append("\n").append(it) }
        }
    }

    /**
     * #51: parse + gate + store a scheduled WhatsApp message.
     * Returns the reply when the text IS a scheduled-send request
     * (scheduled, or refused at the gate), null when it isn't one —
     * the tail after "saying" must end in a parseable time ("at 7 pm",
     * "tomorrow at 9 am", "for 18:30") for the route to claim it.
     * The draft is all that is created here: at fire time the alarm
     * posts a due notification and the actual send re-enters the
     * gated whatsapp_message flow.
     */
    private suspend fun tryScheduleMessage(
        context: Context,
        clean: String
    ): String? {
        val match = Regex(
            "(?i)^(?:schedule|send) (?:a )?(?:whatsapp )?message to (.+?) saying (.+)$"
        ).matchEntire(normalizeCommandText(clean)) ?: return null
        val contact = match.groupValues[1].trim()
        val tail = match.groupValues[2].trim()
        // Split the tail into message + when, from the END: the last
        // " at "/" for " whose remainder parses as a time of day
        // (optionally wrapped in "tomorrow"). Searched on the
        // lowercase tail (indexes are identical) because STT casing
        // varies. " tomorrow at " wins over the bare " at " inside it.
        val lowerTail = tail.lowercase()
        val tomorrowAt = lowerTail.lastIndexOf(" tomorrow at ")
        val splitIndex = if (tomorrowAt > 0) {
            tomorrowAt
        } else {
            listOf(
                lowerTail.lastIndexOf(" at "),
                lowerTail.lastIndexOf(" for ")
            ).filter { it > 0 }.maxOrNull() ?: return null
        }
        val message = tail.substring(0, splitIndex).trim()
            .removeSuffix("tomorrow").trim()
        var whenText = tail.substring(splitIndex).trim()
        val explicitTomorrow = whenText.contains("tomorrow", ignoreCase = true)
        whenText = whenText
            .replace(Regex("(?i)tomorrow"), "")
            .trim()
            .removePrefix("at").trim()
            .removePrefix("for").trim()
        val (hour, minute) = parseTimeOfDay(whenText) ?: return null
        if (contact.isBlank() || message.isBlank()) return null
        val fireAt = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (explicitTomorrow ||
                timeInMillis <= System.currentTimeMillis()
            ) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        val whenLabel = java.text.SimpleDateFormat(
            "EEE d MMM, h:mm a", java.util.Locale.ENGLISH
        ).format(fireAt.time)
        val approved = ConfirmGate.confirm(
            "Schedule a WhatsApp message to $contact for $whenLabel?\n\n" +
                "Message: \"$message\"\n\n" +
                "It stays a draft — when the time comes, Jarvis will " +
                "ask you to confirm the send itself."
        )
        if (!approved) {
            return "Okay — I didn't schedule that message."
        }
        val item = BrainStore.addScheduled(
            context, contact, message, fireAt.timeInMillis
        )
        ScheduledAlarms.schedule(context, item)
        return "Scheduled ✅ — WhatsApp message to $contact for " +
            "$whenLabel: \"$message\". It's a draft: when the time " +
            "comes I'll ask you to confirm the actual send."
    }

    /** Background scope for #82-style housekeeping (never blocks a turn). */
    private val housekeepingScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * v1.9.0 (#73): conservative auto-extraction — only clear,
     * first-person durable statements ("my exam is on Friday",
     * "I live in Kochi", "I work at …"). Questions, commands and
     * long texts never match. Returns (category, fact-as-stated).
     */
    private fun autoExtractCandidate(text: String): Pair<String, String>? {
        val clean = text.trim()
        if (clean.length !in 8..140 || clean.contains('?')) return null
        val patterns = listOf(
            Regex("(?i)^my [a-z][a-z ]{1,28}? is .+$"),
            Regex("(?i)^i live in .+$"),
            Regex("(?i)^i work (?:at|for) .+$"),
            Regex("(?i)^i study (?:at|in) .+$"),
            Regex("(?i)^i am \\d{1,3} years old.*$"),
            Regex("(?i)^my favourite .+? is .+$")
        )
        if (patterns.none { it.matches(clean) }) return null
        return inferCategory(clean) to clean
    }

    /** Execute one deterministic quick command, with HUD events. */
    private suspend fun runQuick(
        context: Context,
        quick: QuickCommand,
        memoryDb: MemoryDb,
        ledger: ExecutionLedger,
        onEvent: (ToolEvent) -> Unit
    ): String {
        // #61: never START a quick tool after a stop was requested.
        TaskState.throwIfStopRequested()
        // F8 (#15): direct-reply commands (battery / time / date) are
        // answered locally — no tool, no model, instant.
        quick.directReply?.let { reply ->
            onEvent(ToolEvent(ToolEventKind.SUCCESS, quick.toolName, reply))
            return reply
        }
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
        // #61: check immediately AFTER the tool execution too.
        TaskState.throwIfStopRequested()
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

    /**
     * v2.0.0 (#76): topic-matched memory injection — only memories
     * relevant to THIS request enter the prompt (faster, and the
     * model isn't distracted by unrelated facts). Personal /
     * Preferences / Contacts facts always ride along (identity +
     * aliases are relevant to almost everything); other categories
     * join when they share a significant token with the request.
     * With nothing matched, the 5 most recent facts are used so a
     * bare "hi" still has some context. The full store is untouched —
     * "what do you remember" still lists everything.
     */
    private fun relevantFacts(memoryDb: MemoryDb, query: String): List<Fact> {
        val all = memoryDb.allFacts(null, null)
        if (all.size <= 12) return all
        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
            .toSet()
        val always = all.filter { fact ->
            fact.category == MemoryCategories.PERSONAL ||
                fact.category == MemoryCategories.PREFERENCES ||
                fact.category == MemoryCategories.CONTACTS
        }
        val matched = all.filter { fact ->
            fact !in always &&
                tokens.any { token -> fact.text.lowercase().contains(token) }
        }
        val selected = (always + matched).distinctBy { it.id }.take(12)
        AppLog.d(
            "Agent",
            "memory injection: ${selected.size}/${all.size} facts (topic-matched)"
        )
        return if (selected.isEmpty()) all.take(5) else selected
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
        ledger: ExecutionLedger,
        imageBase64: String? = null
    ): String {
        val facts = relevantFacts(memoryDb, userText)
        val systemPrompt = PersonaPrompts.build(settings, facts, codeMode)
        // Trim what's SENT (stored history stays intact): last 16 turns
        // keeps prompts small, which keeps free-tier latency down.
        val history = memoryDb.lastMessages(16)
            .filter { it.role == "user" || it.role == "assistant" }
            .map { ChatMsg(role = it.role, content = it.content) }
        val messages = mutableListOf(ChatMsg("system", systemPrompt))
        messages += history
        // Plan #71: an attached image rides on the user turn (the
        // provider layer serializes it as a vision content part).
        if (history.lastOrNull()?.content != userText || imageBase64 != null) {
            messages += ChatMsg("user", userText, imageBase64 = imageBase64)
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
        // #61: stop check at the agent-loop boundary — before the loop
        // starts, and again right after it returns (i.e. after its last
        // tool execution), so a stop pressed mid-loop wins over the
        // loop's reply. The loop itself lives in FailoverRouter (out of
        // this change's file scope), so the flag is honoured here; the
        // TaskStoppedException is caught in handleUserText BEFORE the
        // generic handler — never a failure, never provider failover,
        // never an action replay.
        TaskState.throwIfStopRequested()
        val loopResult = FailoverRouter(
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
        TaskState.throwIfStopRequested()
        return loopResult
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
            // #61: stop check at the top of every queue iteration.
            TaskState.throwIfStopRequested()
            // Cancellation (abort) propagates to handleUserText, which
            // aborts the queue — queued items never start.
            currentCoroutineContext().ensureActive()
            // v1.9.0 (#62): an item skipped/cancelled from the panel
            // while it waited never executes.
            val liveStatus = TaskQueue.tasks.value
                .firstOrNull { it.id == item.id }?.status
            if (liveStatus == TaskStatus.SKIPPED) {
                AppLog.i("Agent", "task ${index + 1}/${items.size} skipped from panel")
                lines += "⏭ " + item.title + " — skipped"
                continue
            }
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
            } catch (e: TaskStoppedException) {
                // #61: a stop is not a sub-task failure — propagate so
                // handleUserText finishes the whole turn as "Stopped."
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Raw text → AppLog; the queue summary line is friendly.
                AppLog.e(
                    "Agent",
                    "sub-task failed (${e.javaClass.simpleName})"
                )
                "Failed: " + com.nshd.jarvis2.ui.FriendlyErrors.shortReason(e.message)
            }
            // #61: stop check right after this sub-task's execution.
            TaskState.throwIfStopRequested()
            val outcomeNeedsReview = result.startsWith("STAGED_FOR_USER") ||
                result.startsWith("SUBMITTED_UNVERIFIED") ||
                result.startsWith("OUTCOME_UNKNOWN")
            if (outcomeNeedsReview) {
                // The external action may already have reached another app.
                // Do not continue a compound request into later side effects.
                TaskQueue.markFailed(item.id)
                failed++
                lines += "⚠ " + item.title + " — " +
                    result.lineSequence().firstOrNull().orEmpty().take(90)
                items.drop(index + 1).forEach { TaskQueue.skipTask(it.id) }
                lines += "Stopped remaining tasks: inspect the external app before retrying."
                AppLog.w("Agent", "task queue stopped after uncertain action outcome at step ${index + 1}")
                break
            }
            val ok = !isFailureResult(result)
            AppLog.d(
                "Agent",
                "task ${index + 1}/${items.size} → " +
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
        val facts = relevantFacts(memoryDb, userText)
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
                    // #61: never insert after a stop was requested.
                    TaskState.throwIfStopRequested()
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

    // ── #75: "forget X" parsing ──────────────────────────────────────

    private fun extractForgetTarget(text: String): String? {
        val normalized = normalizeCommandText(text)
        val patterns = listOf(
            Regex("(?i)^forget about\\s+(.+)$"),
            Regex("(?i)^forget\\s+(.+)$"),
            Regex("(?i)^remove\\s+(.+?)\\s+from memory$"),
            Regex("(?i)^delete\\s+(.+?)\\s+from memory$"),
            Regex("(?i)^delete memory[:\\s]+(.+)$"),
            Regex("(?i)^remove memory[:\\s]+(.+)$")
        )
        for (pattern in patterns) {
            pattern.matchEntire(normalized)?.let { match ->
                val target = match.groupValues[1].trim()
                    .trim('.', '!', '?')
                    .removeSuffix(" from memory")
                    .trim()
                if (target.isNotBlank()) return target
            }
        }
        return null
    }

    // ── #52: contact-correction parsing ──────────────────────────────

    /** Imperative openers that make a "X, not Y" phrase a command, not a name correction. */
    private val CORRECTION_COMMAND_STARTS = setOf(
        "play", "open", "send", "turn", "set", "watch",
        "search", "find", "remind", "message", "text"
    )

    /**
     * Detect a contact-name correction, returning (wrong, right).
     * Both sides must be 1–3 plain words; article-led sides ("the
     * title track") and command-led right sides ("play raabta") are
     * rejected so media/quick phrases are never hijacked as aliases.
     */
    private fun extractContactCorrection(text: String): Pair<String, String>? {
        val normalized = normalizeCommandText(text)

        fun valid(wrongRaw: String, rightRaw: String, guardCommand: Boolean): Pair<String, String>? {
            val wrong = wrongRaw.trim().trim(',', '—', '–', '-').trim()
            val right = rightRaw.trim().trim(',', '—', '–', '-').trim()
            if (wrong.isBlank() || right.isBlank()) return null
            if (wrong.equals(right, ignoreCase = true)) return null
            val wrongWords = wrong.split(Regex("\\s+"))
            val rightWords = right.split(Regex("\\s+"))
            if (wrongWords.size !in 1..3 || rightWords.size !in 1..3) return null
            if (!wrong.all { it.isLetter() || it == ' ' || it == '\'' } ||
                !right.all { it.isLetter() || it == ' ' || it == '\'' }
            ) return null
            val wrongLower = wrong.lowercase()
            val rightLower = right.lowercase()
            if (wrongLower.startsWith("the ") || wrongLower.startsWith("a ") ||
                wrongLower.startsWith("an ") || rightLower.startsWith("the ") ||
                rightLower.startsWith("a ") || rightLower.startsWith("an ")
            ) return null
            if (guardCommand &&
                rightWords.first().lowercase() in CORRECTION_COMMAND_STARTS
            ) return null
            return wrong to right
        }

        Regex("(?i)^i said\\s+(.+?)\\s+not\\s+(.+)$")
            .matchEntire(normalized)?.let { match ->
                return valid(
                    wrongRaw = match.groupValues[2],
                    rightRaw = match.groupValues[1],
                    guardCommand = false
                )
            }
        Regex("(?i)^(?:call|message|text|whatsapp)\\s+(?:him|her|them)\\s+(.+?)\\s+not\\s+(.+)$")
            .matchEntire(normalized)?.let { match ->
                return valid(
                    wrongRaw = match.groupValues[2],
                    rightRaw = match.groupValues[1],
                    guardCommand = false
                )
            }
        Regex("(?i)^not\\s+(.+?)\\s*(?:,|—|–|-)\\s*(.+)$")
            .matchEntire(normalized)?.let { match ->
                return valid(
                    wrongRaw = match.groupValues[1],
                    rightRaw = match.groupValues[2],
                    guardCommand = true
                )
            }
        Regex("(?i)^(.+?)\\s*,\\s*not\\s+(.+)$")
            .matchEntire(normalized)?.let { match ->
                return valid(
                    wrongRaw = match.groupValues[2],
                    rightRaw = match.groupValues[1],
                    guardCommand = true
                )
            }
        return null
    }

    /**
     * Save a learned alias in ContactsResolver's EXISTING format — a
     * Contacts-category fact whose text is "alias = real name"
     * (see ContactsResolver.loadAliasHints). A stale alias for the
     * same wrong name is replaced, never duplicated.
     */
    private fun saveContactAlias(memoryDb: MemoryDb, wrong: String, right: String) {
        val wrongKey = ContactsResolver.normalizeName(wrong)
        try {
            memoryDb.allFacts(null, MemoryCategories.CONTACTS).forEach { fact ->
                val split = fact.text.indexOf('=')
                if (split > 0 &&
                    ContactsResolver.normalizeName(fact.text.substring(0, split)) == wrongKey
                ) {
                    memoryDb.deleteFact(fact.id)
                }
            }
        } catch (e: Exception) {
            // Replacing a stale alias is best-effort; the save still runs.
        }
        memoryDb.addFact(MemoryCategories.CONTACTS, "$wrong = $right")
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

    private data class QuickCommand(
        val toolName: String,
        val args: JSONObject,
        /** F8 (#15): set for locally-answered commands (no tool runs). */
        val directReply: String? = null
    )

    /** F8 (#15): the stop/cancel command family. */
    private fun isStopCommand(text: String): Boolean =
        normalizeCommandText(text).lowercase() in STOP_COMMANDS

    /** F8 (#15): battery level + charging state from the sticky intent. */
    private fun batteryReply(context: Context): String {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return "I couldn't read the battery level just now."
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return "I couldn't read the battery level just now."
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "Battery is at $percent%" + if (charging) " and charging." else "."
    }

    /** F8 (#15): spoken-style local time, e.g. "It's 4:32 PM." */
    private fun timeReply(): String {
        val formatted = java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
        return "It's $formatted."
    }

    /** F8 (#15): spoken-style local date, e.g. "Today is Friday, 23 October 2026." */
    private fun dateReply(): String {
        val formatted = java.time.LocalDate.now()
            .format(
                java.time.format.DateTimeFormatter.ofPattern(
                    "EEEE, d MMMM yyyy", java.util.Locale.ENGLISH
                )
            )
        return "Today is $formatted."
    }

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

    /**
     * v2.0.0 (#49): explicit WhatsApp GROUP sends. Checked before the
     * 1:1 WhatsApp patterns — otherwise "the group Family" would be
     * resolved as a contact name and the send would go nowhere.
     */
    private fun groupBroadcastQuick(text: String): QuickCommand? {
        fun build(group: String, message: String): QuickCommand =
            QuickCommand(
                "whatsapp_group_message",
                JSONObject()
                    .put("group", group.trim())
                    .put("message", message.trim())
            )
        Regex("(?i)^broadcast (?:to )?(?:the )?(.+?)(?: group)?\\s*:\\s*(.+)$")
            .find(text)?.let { return build(it.groupValues[1], it.groupValues[2]) }
        Regex("(?i)^broadcast (?:to )?(?:the )?(.+?)(?: group)? saying (.+)$")
            .find(text)?.let { return build(it.groupValues[1], it.groupValues[2]) }
        Regex("(?i)^send (?:a )?(?:whatsapp )?message to (?:the )?group (.+?)\\s*:\\s*(.+)$")
            .find(text)?.let { return build(it.groupValues[1], it.groupValues[2]) }
        Regex("(?i)^send (?:a )?message to (?:the )?(.+?) group\\s*:\\s*(.+)$")
            .find(text)?.let { return build(it.groupValues[1], it.groupValues[2]) }
        Regex("(?i)^message (?:the )?group (.+?)\\s*:\\s*(.+)$")
            .find(text)?.let { return build(it.groupValues[1], it.groupValues[2]) }
        return null
    }

    /** v2.0.0 (#50): spoken-style summary of stored notifications. */
    private fun notificationReply(context: Context, whatsappOnly: Boolean): String {
        if (!BrainStore.notifReaderEnabled(context)) {
            return "The notification reader is off. Turn it on in " +
                "Settings → Brain Upgrade → Notification reader, and " +
                "grant Notification Access when Android asks — then " +
                "I can read your unread messages aloud."
        }
        if (!JarvisNotificationListener.connected) {
            return "Notification Access isn't active yet. Open " +
                "Permissions → Notification Access and allow Jarvis, " +
                "then ask me again."
        }
        val notes = if (whatsappOnly) {
            JarvisNotificationListener.recent().filter {
                it.packageName == "com.whatsapp" ||
                    it.packageName == "com.whatsapp.w4b"
            }
        } else {
            JarvisNotificationListener.recent()
        }
        if (notes.isEmpty()) {
            return if (whatsappOnly) {
                "No WhatsApp notifications right now — you're all caught up."
            } else {
                "No notifications to read right now."
            }
        }
        val shown = notes.take(8)
        return buildString {
            append(
                if (whatsappOnly) {
                    "Here are your latest WhatsApp notifications:"
                } else {
                    "Here are your latest notifications:"
                }
            )
            shown.forEach { note ->
                append("\n• ")
                if (note.title.isNotBlank()) append(note.title)
                if (note.text.isNotBlank()) {
                    if (note.title.isNotBlank()) append(": ")
                    append(note.text.take(140))
                }
            }
            if (notes.size > shown.size) {
                append("\n…and ${notes.size - shown.size} more.")
            }
        }
    }

    /** v2.0.0 (#114): "where am I" — permission-aware direct reply. */
    private fun locationReply(context: Context): String {
        if (!LocationHelper.hasPermission(context)) {
            return "I don't have location permission yet — grant " +
                "\"Precise Location\" in Permissions and ask me again."
        }
        val fix = LocationHelper.lastKnownFix(context)
            ?: return "I couldn't get a location fix — turn on Location " +
                "and open Maps once so the phone has a recent position, " +
                "then ask me again."
        return LocationHelper.describe(fix) +
            " Map: " + LocationHelper.mapsLink(fix)
    }

    private fun quickCommand(context: Context, rawText: String): QuickCommand? {
        val text = normalizeCommandText(rawText)
        val lower = text.lowercase()

        // v2.0.0 (#49): explicit group sends claim the text first —
        // see groupBroadcastQuick.
        if (lower.contains("group") || lower.startsWith("broadcast")) {
            groupBroadcastQuick(text)?.let { return it }
        }

        // ── WhatsApp sends (F7 expanded). Every hit still runs the
        // whatsapp_message tool, whose just-in-time send ask (v2.0.2)
        // confirms recipient + full text right before sending — his
        // standing rule is untouched, only the timing moved.
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

        // ── F8 (#15): the 50+ quick-pattern expansion ────────────────
        // All zero-model: local hardware / intents / direct replies.
        if (lower == "mute" || lower == "mute volume" || lower == "mute the volume") {
            return QuickCommand("set_volume", JSONObject().put("percent", 0))
        }
        if (lower == "unmute" || lower == "unmute volume") {
            return QuickCommand("set_volume", JSONObject().put("percent", 50))
        }

        // Screenshot (the take_screenshot tool saves via a11y/share).
        if (lower in SCREENSHOT_COMMANDS) {
            return QuickCommand("take_screenshot", JSONObject())
        }

        // Battery / time / date — answered locally, no tool at all.
        if (lower in BATTERY_QUERIES) {
            return QuickCommand("direct_reply", JSONObject(), directReply = batteryReply(context))
        }
        if (lower in TIME_QUERIES) {
            return QuickCommand("direct_reply", JSONObject(), directReply = timeReply())
        }
        if (lower in DATE_QUERIES) {
            return QuickCommand("direct_reply", JSONObject(), directReply = dateReply())
        }

        // Media transport (the active session gets the key event).
        MEDIA_CONTROL_COMMANDS[lower]?.let { action ->
            return QuickCommand(
                "media_control", JSONObject().put("action", action)
            )
        }

        // Brightness relative steps (set_brightness applies the %).
        if (lower in BRIGHTNESS_UP_COMMANDS) {
            val percent = DeepIntents(context).currentBrightnessPercent() + 20
            return QuickCommand(
                "set_brightness",
                JSONObject().put("percent", percent.coerceIn(0, 100))
            )
        }
        if (lower in BRIGHTNESS_DOWN_COMMANDS) {
            val percent = DeepIntents(context).currentBrightnessPercent() - 20
            return QuickCommand(
                "set_brightness",
                JSONObject().put("percent", percent.coerceIn(0, 100))
            )
        }

        // Settings panels — Android won't let apps flip Wi-Fi /
        // airplane mode directly, so these open the honest panel.
        if (WIFI_COMMAND_REGEX.matches(lower)) {
            return QuickCommand("open_panel", JSONObject().put("panel", "wifi"))
        }
        if (BLUETOOTH_COMMAND_REGEX.matches(lower)) {
            return QuickCommand("open_panel", JSONObject().put("panel", "bluetooth"))
        }
        if (AIRPLANE_COMMAND_REGEX.matches(lower)) {
            return QuickCommand("open_panel", JSONObject().put("panel", "airplane"))
        }

        // Silent mode (DND policy access handled inside the intent).
        if (lower in SILENT_ON_COMMANDS) {
            return QuickCommand("set_silent", JSONObject().put("on", true))
        }
        if (lower in SILENT_OFF_COMMANDS) {
            return QuickCommand("set_silent", JSONObject().put("on", false))
        }

        // Lock screen (a11y global action, API 28+).
        if (lower in LOCK_COMMANDS) {
            return QuickCommand("lock_screen", JSONObject())
        }

        // Navigation keys.
        if (lower in GO_HOME_COMMANDS) {
            return QuickCommand("press_key", JSONObject().put("key", "home"))
        }
        if (lower in GO_BACK_COMMANDS) {
            return QuickCommand("press_key", JSONObject().put("key", "back"))
        }

        // Call <name> — the call_contact tool's own gate rules apply
        // unchanged (numbers outside Contacts confirm first).
        Regex("(?i)^call\\s+(.+)$").find(text)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotBlank() &&
                name.split(Regex("\\s+")).size <= 3 &&
                !name.contains(" and ", ignoreCase = true)
            ) {
                return QuickCommand(
                    "call_contact", JSONObject().put("contact", name)
                )
            }
        }

        // ── v1.9.0 "More Powers" quick patterns ─────────────────────
        // Every send pattern still lands in its ToolExecutor tool,
        // whose just-in-time send ask (v2.0.2) confirms recipient +
        // full text right before sending.

        // #43: "send the latest photo to Mom" (WhatsApp).
        Regex("(?i)^send (?:the )?(?:latest|last|recent) photo to (.+)$")
            .find(text)?.let { match ->
                return QuickCommand(
                    "whatsapp_photo",
                    JSONObject().put("contact", match.groupValues[1].trim())
                )
            }

        // #45: Instagram DM.
        Regex("(?i)^(?:send (?:an? )?)?instagram (?:dm|message) to (.+?) saying (.+)$")
            .find(text)?.let { match ->
                return QuickCommand(
                    "instagram_message",
                    JSONObject()
                        .put("contact", match.groupValues[1].trim())
                        .put("message", match.groupValues[2].trim())
                )
            }
        Regex("(?i)^dm (.+?) on instagram saying (.+)$").find(text)?.let { match ->
            return QuickCommand(
                "instagram_message",
                JSONObject()
                    .put("contact", match.groupValues[1].trim())
                    .put("message", match.groupValues[2].trim())
            )
        }

        // #46: Telegram.
        Regex("(?i)^(?:send (?:a )?)?telegram (?:message )?to (.+?) saying (.+)$")
            .find(text)?.let { match ->
                return QuickCommand(
                    "telegram_message",
                    JSONObject()
                        .put("contact", match.groupValues[1].trim())
                        .put("message", match.groupValues[2].trim())
                )
            }

        // #47: SMS.
        Regex("(?i)^(?:send (?:an? )?)?sms to (.+?) saying (.+)$")
            .find(text)?.let { match ->
                return QuickCommand(
                    "sms_message",
                    JSONObject()
                        .put("contact", match.groupValues[1].trim())
                        .put("message", match.groupValues[2].trim())
                )
            }
        Regex("(?i)^text (.+?) saying (.+)$").find(text)?.let { match ->
            return QuickCommand(
                "sms_message",
                JSONObject()
                    .put("contact", match.groupValues[1].trim())
                    .put("message", match.groupValues[2].trim())
            )
        }

        // #48: email — only when the target is a real address; the
        // subject is the first sentence of the body text.
        Regex("(?i)^email (\\S+@\\S+) saying (.+)$").find(text)?.let { match ->
            val body = match.groupValues[2].trim()
            val subject = body.substringBefore('.').take(60).ifBlank { "Message from Jarvis" }
            return QuickCommand(
                "gmail_send",
                JSONObject()
                    .put("to", match.groupValues[1].trim())
                    .put("subject", subject)
                    .put("body", body)
            )
        }

        // #107: alarm — "set alarm for 6 am" / "set an alarm 6:30 pm".
        Regex("(?i)^set (?:an? )?alarm (?:for )?(.+)$").find(text)?.let { match ->
            parseTimeOfDay(match.groupValues[1])?.let { (hour, minute) ->
                return QuickCommand(
                    "set_alarm",
                    JSONObject()
                        .put("hour", hour)
                        .put("minute", minute)
                        .put("label", "")
                )
            }
        }
        // #107: timer — "set a timer for 10 minutes" / "timer 90 seconds".
        Regex("(?i)^(?:set (?:a )?)?timer (?:for )?(.+)$").find(text)?.let { match ->
            parseDurationSeconds(match.groupValues[1])?.let { seconds ->
                return QuickCommand(
                    "set_timer",
                    JSONObject()
                        .put("seconds", seconds)
                        .put("label", "")
                )
            }
        }

        // #108: calendar — "add event Dentist in 30 minutes" /
        // "add event Exam tomorrow at 9 am".
        Regex("(?i)^(?:add|create|schedule) (?:an? )?(?:event|meeting) (.+?) in (\\d+) (minutes?|hours?)$")
            .find(text)?.let { match ->
                val amount = match.groupValues[2].toIntOrNull() ?: 0
                val minutes = if (match.groupValues[3].startsWith("hour")) {
                    amount * 60
                } else {
                    amount
                }
                return QuickCommand(
                    "calendar_event",
                    JSONObject()
                        .put("title", match.groupValues[1].trim())
                        .put("start_in_minutes", minutes)
                        .put("duration_minutes", 60)
                )
            }
        Regex("(?i)^(?:add|create|schedule) (?:an? )?(?:event|meeting) (.+?) tomorrow at (.+)$")
            .find(text)?.let { match ->
                parseTimeOfDay(match.groupValues[2])?.let { (hour, minute) ->
                    val now = java.util.Calendar.getInstance()
                    val target = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, hour)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                    }
                    val inMinutes = ((target.timeInMillis - now.timeInMillis) / 60_000L)
                        .toInt().coerceAtLeast(1)
                    return QuickCommand(
                        "calendar_event",
                        JSONObject()
                            .put("title", match.groupValues[1].trim())
                            .put("start_in_minutes", inMinutes)
                            .put("duration_minutes", 60)
                    )
                }
            }

        // #109: quick note — "note that …" / "note down …" / "take a note: …".
        Regex("(?i)^note (?:that |down )?(?:this:? )?(.+)$").find(text)?.let { match ->
            val note = match.groupValues[1].trim()
            if (note.length >= 3) {
                return QuickCommand(
                    "save_note", JSONObject().put("text", note)
                )
            }
        }
        Regex("(?i)^take a note:? (.+)$").find(text)?.let { match ->
            return QuickCommand(
                "save_note",
                JSONObject().put("text", match.groupValues[1].trim())
            )
        }

        // #110: share the current screen/screenshot via the share sheet.
        if (lower in SHARE_SCREENSHOT_COMMANDS) {
            return QuickCommand("share_screenshot", JSONObject())
        }

        // ── v2.0.0 "Brain Upgrade" quick patterns ────────────────────

        // #49 (second shape): "send a whatsapp message to the group
        // X saying Y" — the colon shapes are claimed at the top of
        // quickCommand (see groupBroadcastQuick) before the 1:1
        // WhatsApp patterns can read "the group X" as a contact name.
        Regex("(?i)^send (?:a )?whatsapp (?:message )?to (?:the )?group (.+?) saying (.+)$")
            .find(text)?.let { match ->
                return QuickCommand(
                    "whatsapp_group_message",
                    JSONObject()
                        .put("group", match.groupValues[1].trim())
                        .put("message", match.groupValues[2].trim())
                )
            }

        // #50: unread reader — answered locally from the notification
        // listener's in-memory store (opt-in, see BrainStore).
        if (lower in READ_WHATSAPP_COMMANDS || lower in READ_ALL_NOTIF_COMMANDS) {
            val whatsappOnly = lower in READ_WHATSAPP_COMMANDS
            return QuickCommand(
                "direct_reply",
                JSONObject(),
                directReply = notificationReply(context, whatsappOnly)
            )
        }

        // #114: location answers.
        if (lower in WHERE_AM_I_COMMANDS) {
            return QuickCommand(
                "direct_reply",
                JSONObject(),
                directReply = locationReply(context)
            )
        }
        Regex("(?i)^(?:share|send) my location (?:with|to) (.+)$")
            .find(text)?.let { match ->
                val fix = if (LocationHelper.hasPermission(context)) {
                    LocationHelper.lastKnownFix(context)
                } else {
                    null
                }
                return if (fix != null) {
                    QuickCommand(
                        "whatsapp_message",
                        JSONObject()
                            .put("contact", match.groupValues[1].trim())
                            .put(
                                "message",
                                "My location: " + LocationHelper.mapsLink(fix)
                            )
                    )
                } else {
                    QuickCommand(
                        "direct_reply",
                        JSONObject(),
                        directReply = locationReply(context)
                    )
                }
            }

        // #111: download helper.
        Regex("(?i)^download (https?://\\S+)$").find(text)?.let { match ->
            return QuickCommand(
                "download_url",
                JSONObject().put("url", match.groupValues[1].trim())
            )
        }

        // #112: call controls (the tool checks the permission).
        if (lower in ANSWER_CALL_COMMANDS) {
            return QuickCommand("call_answer", JSONObject())
        }
        if (lower in REJECT_CALL_COMMANDS) {
            return QuickCommand("call_reject", JSONObject())
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

    /** v1.9.0 (#107): "6 am" / "6:30 pm" / "18:00" → (hour24, minute). */
    private fun parseTimeOfDay(raw: String): Pair<Int, Int>? {
        val text = raw.trim().lowercase()
        val match = Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(text)
            ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        when (match.groupValues[3]) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour != 12) hour += 12
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /** v1.9.0 (#107): "10 minutes" / "1 hour 30 minutes" / "90" (=minutes). */
    private fun parseDurationSeconds(raw: String): Int? {
        val text = raw.trim().lowercase()
        var total = 0
        var matched = false
        Regex("(\\d+)\\s*hours?").find(text)?.let {
            total += (it.groupValues[1].toIntOrNull() ?: 0) * 3600
            matched = true
        }
        Regex("(\\d+)\\s*(?:minutes?|mins?)").find(text)?.let {
            total += (it.groupValues[1].toIntOrNull() ?: 0) * 60
            matched = true
        }
        Regex("(\\d+)\\s*(?:seconds?|secs?)").find(text)?.let {
            total += it.groupValues[1].toIntOrNull() ?: 0
            matched = true
        }
        if (!matched) {
            val plain = text.toIntOrNull() ?: return null
            total = plain * 60
        }
        return total.takeIf { it > 0 }
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

    // ── F8 (#15) quick-pattern tables ────────────────────────────────

    private val STOP_COMMANDS = setOf(
        "stop", "cancel", "stop it", "cancel it", "abort",
        "stop task", "cancel task", "stop everything"
    )

    private val SCREENSHOT_COMMANDS = setOf(
        "take a screenshot", "take screenshot", "screenshot",
        "capture screenshot", "take a screen shot", "grab a screenshot"
    )

    private val BATTERY_QUERIES = setOf(
        "battery", "battery status", "battery level", "battery percentage",
        "how much battery", "how much battery is left", "check battery",
        "battery check", "what is the battery", "what's the battery"
    )

    private val TIME_QUERIES = setOf(
        "what time is it", "what's the time", "what is the time",
        "current time", "tell me the time", "time", "the time"
    )

    private val DATE_QUERIES = setOf(
        "what date is it", "what's the date", "what is the date",
        "today's date", "what day is it", "what is today",
        "date", "today date"
    )

    /** Normalized phrase → media_control action. */
    private val MEDIA_CONTROL_COMMANDS = mapOf(
        "pause" to "pause",
        "pause music" to "pause",
        "pause the music" to "pause",
        "pause song" to "pause",
        "pause the song" to "pause",
        "pause playback" to "pause",
        "resume" to "play",
        "resume music" to "play",
        "resume playback" to "play",
        "next song" to "next",
        "next track" to "next",
        "skip song" to "next",
        "skip this song" to "next",
        "previous song" to "previous",
        "previous track" to "previous",
        "last song" to "previous"
    )

    private val BRIGHTNESS_UP_COMMANDS = setOf(
        "brightness up", "increase brightness", "raise brightness",
        "brighter", "make it brighter", "turn up brightness"
    )

    private val BRIGHTNESS_DOWN_COMMANDS = setOf(
        "brightness down", "decrease brightness", "lower brightness",
        "dimmer", "make it dimmer", "turn down brightness", "dim the screen"
    )

    private val WIFI_COMMAND_REGEX =
        Regex("^(?:turn\\s+(?:on|off)\\s+|open\\s+|show\\s+)?wi-?fi(?:\\s+settings)?$")

    private val BLUETOOTH_COMMAND_REGEX =
        Regex("^(?:turn\\s+(?:on|off)\\s+|open\\s+|show\\s+)?bluetooth(?:\\s+settings)?$")

    private val AIRPLANE_COMMAND_REGEX =
        Regex("^(?:turn\\s+(?:on|off)\\s+|open\\s+)?airplane\\s+mode(?:\\s+settings)?$")

    /** v1.9.0 (#110): share-sheet screenshot commands. */
    private val SHARE_SCREENSHOT_COMMANDS = setOf(
        "share screenshot", "share this screenshot", "share the screenshot",
        "share this page", "share page", "share screen", "share this screen"
    )

    /** v2.0.0 (#50): unread-reader commands. */
    private val READ_WHATSAPP_COMMANDS = setOf(
        "read my whatsapp messages", "read my whatsapp",
        "read whatsapp messages", "whatsapp messages",
        "read my unread whatsapp messages"
    )

    private val READ_ALL_NOTIF_COMMANDS = setOf(
        "read my notifications", "read notifications",
        "who messaged me", "who texted me", "any new messages",
        "read my messages", "check my notifications",
        "what are my notifications"
    )

    /** v2.0.0 (#114): location queries. */
    private val WHERE_AM_I_COMMANDS = setOf(
        "where am i", "what is my location", "what's my location",
        "my location", "where am i now", "current location",
        "tell me my location"
    )

    /** v2.0.0 (#112): call controls. */
    private val ANSWER_CALL_COMMANDS = setOf(
        "answer the call", "answer call", "pick up the call",
        "pick up", "take the call", "accept the call"
    )

    private val REJECT_CALL_COMMANDS = setOf(
        "reject the call", "reject call", "decline the call",
        "decline call", "end the call", "hang up", "cut the call"
    )

    private val SILENT_ON_COMMANDS = setOf(
        "silent mode on", "turn on silent mode", "enable silent mode",
        "silent mode", "make it silent", "phone silent", "go silent",
        "put phone on silent", "silence the phone"
    )

    private val SILENT_OFF_COMMANDS = setOf(
        "silent mode off", "turn off silent mode", "disable silent mode",
        "ring mode", "turn off silent", "unsilent"
    )

    private val LOCK_COMMANDS = setOf(
        "lock screen", "lock the screen", "lock my phone",
        "lock the phone", "lock it"
    )

    private val GO_HOME_COMMANDS = setOf(
        "go home", "take me home", "home", "go to home"
    )

    private val GO_BACK_COMMANDS = setOf(
        "go back", "press back", "back button"
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

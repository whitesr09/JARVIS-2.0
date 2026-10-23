package com.nshd.jarvis2.brain

import com.nshd.jarvis2.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** v1.8.0 (#54): the panel history keeps at most this many finished tasks. */
private const val HISTORY_MAX = 5

/** Lifecycle of one queued sub-task (F3 multitask queue). */
enum class TaskStatus { QUEUED, RUNNING, DONE, FAILED, SKIPPED }

/** F7: lifecycle of one step inside a [TaskItem]'s plan. */
enum class PlanStepState { PENDING, RUNNING, DONE, FAILED }

/** F7: one row of a task's plan checklist (floating task panel). */
data class PlanStep(
    val label: String,
    val state: PlanStepState = PlanStepState.PENDING
)

data class TaskItem(
    val id: Long,
    val title: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** F6: live step line for the RUNNING item ("Searching 'One Name'…"). */
    val step: String = "",
    /**
     * F7: the plan checklist the floating TASK PANEL renders
     * (✓ / spinner / · / ✗ rows + "x/y done · z%"). Empty when the
     * producer never declared a plan — the panel then falls back to
     * the F6 step-line behaviour.
     */
    val plan: List<PlanStep> = emptyList(),
    /** F7: planner tag ("media" / "send" / "editor" / "quick" / "generic" / "chat"). */
    val planKind: String = "",
    /** F7: one-line outcome shown under the steps when the task ends. */
    val resultLine: String = "",
    /** F7: whether any tool executed under this item (chat-only detection). */
    val toolsExecuted: Boolean = false
)

/**
 * Ordered multitask queue (F3) — the ONE process-wide task registry.
 *
 * [AgentPipeline] submits the sub-tasks of one compound request here
 * and drives them one by one; since F6 it also registers EVERY other
 * turn (single tasks, quick commands, the code-into-editor route) via
 * [beginTask], and the Gemini Live tool path in VoiceSessionManager
 * registers its tool turns too — before F6 only compound splits ever
 * landed here, which is why the overlay HUD sat at "0 of 0 done — idle"
 * while single tasks (and all Live voice tasks) ran. The floating
 * overlay's multitask HUD (FloatingOverlayService) only READS
 * [tasks] / [currentIndex].
 *
 * Plain singleton object — no nested classes.
 */
object TaskQueue {

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    /** Index into [tasks] of the RUNNING item, or -1 when none runs. */
    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /**
     * v1.8.0 (#54): the last [HISTORY_MAX] finished tasks (DONE/FAILED),
     * newest first. [tasks] itself is pruned/replaced whenever a new
     * batch begins, so the panel's "Recent" section reads this bounded
     * list instead. Appended ONLY in the finish paths ([update] on a
     * terminal transition, and [abortAll] for the aborted RUNNING item).
     */
    private val _history = MutableStateFlow<List<TaskItem>>(emptyList())
    val history: StateFlow<List<TaskItem>> = _history.asStateFlow()

    /**
     * v1.8.0 (#60): queue pause flag. While set, [markRunning] refuses
     * to promote a QUEUED item to RUNNING (the already-RUNNING item is
     * unaffected). The panel's Pause/Resume button toggles this.
     */
    @Volatile
    var paused: Boolean = false
        private set

    /** v1.8.0 (#60): hold the queue — QUEUED items stop being promoted. */
    fun pauseQueue() {
        paused = true
        AppLog.i("Queue", "queue PAUSED (queued items will not be promoted)")
    }

    /** v1.8.0 (#60): release the queue — promotion resumes. */
    fun resumeQueue() {
        paused = false
        AppLog.i("Queue", "queue RESUMED")
    }

    /** Prepend a finished item to the bounded history (callers hold the lock). */
    private fun prependHistory(item: TaskItem) {
        if (item.status != TaskStatus.DONE && item.status != TaskStatus.FAILED) return
        _history.value = (listOf(item) + _history.value.filter { it.id != item.id })
            .take(HISTORY_MAX)
    }

    private val nextId = AtomicLong(1)

    /** Replace the queue with fresh QUEUED items; returns them in order. */
    @Synchronized
    fun submit(titles: List<String>): List<TaskItem> {
        val items = titles.map { title ->
            TaskItem(id = nextId.getAndIncrement(), title = title)
        }
        _tasks.value = items
        _currentIndex.value = -1
        AppLog.i(
            "Queue",
            "submit ${items.size} task(s): " +
                items.joinToString(" | ") { "'${it.title.take(40)}'" }
        )
        return items
    }

    /**
     * F6: register ONE command as a fresh single-item queue and mark
     * it RUNNING immediately. Used for every turn that does not go
     * through the compound splitter (single agent turns, quick
     * commands, the code-into-editor route, Live voice tool turns), so
     * the HUD always has a row while work happens. Replaces any
     * previous batch — a new command batch clearing finished tasks is
     * the established HUD contract.
     */
    @Synchronized
    fun beginTask(title: String): TaskItem {
        val item = TaskItem(
            id = nextId.getAndIncrement(),
            title = title,
            status = TaskStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        _tasks.value = listOf(item)
        _currentIndex.value = 0
        AppLog.i("Queue", "task #${item.id} created+running: '${title.take(50)}'")
        return item
    }

    /** F6: live step text for one item (no-op unless it is RUNNING). */
    @Synchronized
    fun setStep(id: Long, text: String) {
        val current = _tasks.value
        val item = current.firstOrNull { it.id == id } ?: return
        if (item.status != TaskStatus.RUNNING || item.step == text) return
        _tasks.value = current.map {
            if (it.id == id) it.copy(step = text) else it
        }
        AppLog.d("Queue", "task #$id step: $text")
    }

    /** F6: step text for whichever item is RUNNING right now. */
    fun setStepForRunning(text: String) {
        runningItem()?.let { setStep(it.id, text) }
    }

    // ── F7 plan API ──────────────────────────────────────────────────
    //
    // Producers (pipeline routes, ToolExecutor, DeepIntents) declare
    // a plan for the RUNNING item and advance it as phases complete.
    // The floating task panel renders exactly this list; progress is
    // derived (DONE count / total), never stored separately.

    /** Declare (or replace) the plan of item [id]. All steps start PENDING. */
    @Synchronized
    fun setPlan(id: Long, labels: List<String>, kind: String) {
        _tasks.value = _tasks.value.map { item ->
            if (item.id == id) {
                item.copy(
                    plan = labels.map { PlanStep(it) },
                    planKind = kind
                )
            } else {
                item
            }
        }
        AppLog.d("Queue", "task #$id plan ($kind): ${labels.joinToString(" → ")}")
    }

    /** Declare a plan on whichever item is RUNNING right now. */
    fun setPlanForRunning(labels: List<String>, kind: String) {
        runningItem()?.let { setPlan(it.id, labels, kind) }
    }

    /** Set one plan step's state on item [id] (no-op out of range). */
    @Synchronized
    fun setPlanStep(id: Long, index: Int, state: PlanStepState) {
        _tasks.value = _tasks.value.map { item ->
            if (item.id != id || index !in item.plan.indices) {
                item
            } else if (item.plan[index].state == state) {
                item
            } else {
                item.copy(
                    plan = item.plan.mapIndexed { i, step ->
                        if (i == index) step.copy(state = state) else step
                    }
                )
            }
        }
    }

    /** Set one plan step's state on the RUNNING item. */
    fun setPlanStepForRunning(index: Int, state: PlanStepState) {
        runningItem()?.let { setPlanStep(it.id, index, state) }
    }

    /**
     * Advance item [id]'s plan to [toIndex]: every step before it is
     * DONE (a FAILED step stays FAILED), step [toIndex] is RUNNING.
     */
    @Synchronized
    fun advancePlan(id: Long, toIndex: Int) {
        _tasks.value = _tasks.value.map { item ->
            if (item.id != id || item.plan.isEmpty()) {
                item
            } else {
                item.copy(
                    plan = item.plan.mapIndexed { i, step ->
                        when {
                            i < toIndex && step.state != PlanStepState.FAILED ->
                                step.copy(state = PlanStepState.DONE)
                            i == toIndex && step.state != PlanStepState.FAILED ->
                                step.copy(state = PlanStepState.RUNNING)
                            else -> step
                        }
                    }
                )
            }
        }
    }

    /** Advance the RUNNING item's plan to [toIndex]. */
    fun advancePlanForRunning(toIndex: Int) {
        runningItem()?.let { advancePlan(it.id, toIndex) }
    }

    /** Record that a tool executed under item [id] (chat-only detection). */
    @Synchronized
    fun noteToolExecuted(id: Long) {
        _tasks.value = _tasks.value.map {
            if (it.id == id && !it.toolsExecuted) it.copy(toolsExecuted = true) else it
        }
    }

    /** Record a tool execution under whichever item is RUNNING. */
    fun noteToolExecutedForRunning() {
        runningItem()?.let { noteToolExecuted(it.id) }
    }

    /**
     * Generic 4-step plan events (Part B mapping): the first
     * open_app-style tool completes step 2 ("Open or confirm the
     * target app") and starts step 3; the first mutating tool just
     * makes sure step 3 is RUNNING (it completes at turn end).
     * No-ops for any non-"generic" plan — deep intents drive their
     * own plans phase by phase.
     */
    @Synchronized
    fun onGenericToolEvent(openAppLike: Boolean) {
        val running = runningItem() ?: return
        if (running.planKind != "generic" || running.plan.size < 4) return
        if (openAppLike) {
            advancePlan(running.id, 2)
        } else if (running.plan[2].state == PlanStepState.PENDING) {
            setPlanStep(running.id, 2, PlanStepState.RUNNING)
        }
    }

    /** Store the one-line outcome the panel shows under the steps. */
    @Synchronized
    fun setResultLine(id: Long, text: String) {
        val clean = text.trim().take(120)
        if (clean.isBlank()) return
        _tasks.value = _tasks.value.map {
            if (it.id == id) it.copy(resultLine = clean) else it
        }
    }

    /** Store the outcome line on whichever item is RUNNING. */
    fun setResultLineForRunning(text: String) {
        runningItem()?.let { setResultLine(it.id, text) }
    }

    /** DONE plan steps of [item] (FAILED doesn't count as done). */
    fun planDoneCount(item: TaskItem): Int =
        item.plan.count { it.state == PlanStepState.DONE }

    /** 0–100 plan completion of [item]; 0 when it has no plan. */
    fun planPercent(item: TaskItem): Int =
        if (item.plan.isEmpty()) 0
        else planDoneCount(item) * 100 / item.plan.size

    /** The currently RUNNING item, if any. */
    @Synchronized
    fun runningItem(): TaskItem? =
        _tasks.value.firstOrNull { it.status == TaskStatus.RUNNING }

    /**
     * F6: close out any still-RUNNING item as DONE/FAILED. The
     * compound-queue path marks its own items individually, so this
     * only ever fires for [beginTask] registrations — it never
     * double-marks a queue item.
     */
    @Synchronized
    fun finishRunning(success: Boolean) {
        val running = runningItem() ?: return
        // F7: finalize the plan with the task — success completes
        // every step; failure marks the still-running step FAILED so
        // the panel's ✗ lands on the step that actually died.
        if (running.plan.isNotEmpty()) {
            val finalized = running.plan.map { step ->
                when {
                    success && step.state != PlanStepState.FAILED ->
                        step.copy(state = PlanStepState.DONE)
                    !success && step.state == PlanStepState.RUNNING ->
                        step.copy(state = PlanStepState.FAILED)
                    else -> step
                }
            }
            _tasks.value = _tasks.value.map {
                if (it.id == running.id) it.copy(plan = finalized) else it
            }
        }
        update(running.id, if (success) TaskStatus.DONE else TaskStatus.FAILED)
        val elapsed = running.startedAt?.let { System.currentTimeMillis() - it } ?: -1L
        AppLog.i(
            "Queue",
            "task #${running.id} '${running.title.take(40)}' finished " +
                (if (success) "DONE" else "FAILED") +
                if (elapsed >= 0) " in ${elapsed}ms" else ""
        )
    }

    @Synchronized
    fun markRunning(id: Long) {
        // v1.8.0 (#60): promotion choke point — while paused, a QUEUED
        // item is NOT promoted to RUNNING. (The pipeline's own loop is
        // outside this file; the flag is the minimal correct hook.)
        val item = _tasks.value.firstOrNull { it.id == id }
        if (paused && item?.status == TaskStatus.QUEUED) {
            AppLog.d("Queue", "task #$id promotion held — queue paused")
            return
        }
        AppLog.d("Queue", "task #$id running")
        update(id, TaskStatus.RUNNING)
    }

    @Synchronized
    fun markRunning(index: Int) {
        val item = _tasks.value.getOrNull(index) ?: return
        if (paused && item.status == TaskStatus.QUEUED) {
            AppLog.d("Queue", "task #${item.id} promotion held — queue paused")
            return
        }
        update(item.id, TaskStatus.RUNNING)
    }

    @Synchronized
    fun markDone(id: Long) {
        AppLog.d("Queue", "task #$id done")
        update(id, TaskStatus.DONE)
    }

    @Synchronized
    fun markDone(index: Int) {
        _tasks.value.getOrNull(index)?.let { update(it.id, TaskStatus.DONE) }
    }

    @Synchronized
    fun markFailed(id: Long) {
        AppLog.d("Queue", "task #$id FAILED")
        update(id, TaskStatus.FAILED)
    }

    @Synchronized
    fun markFailed(index: Int) {
        _tasks.value.getOrNull(index)?.let { update(it.id, TaskStatus.FAILED) }
    }

    /**
     * v1.9.0 (#62): skip ONE upcoming sub-task from the panel. Only
     * QUEUED items can be skipped — a RUNNING item is stopped via
     * the panel's Stop (TaskState abort), never from here. The
     * pipeline's queue loop checks the live status before promoting
     * each item, so a skipped item never executes.
     */
    @Synchronized
    fun skipTask(id: Long) {
        val item = _tasks.value.firstOrNull { it.id == id } ?: return
        if (item.status != TaskStatus.QUEUED) {
            AppLog.d("Queue", "skip ignored for task #$id (status=${item.status})")
            return
        }
        AppLog.i("Queue", "task #$id SKIPPED from panel: '${item.title.take(40)}'")
        update(id, TaskStatus.SKIPPED)
    }

    /** v1.9.0 (#62): cancel = skip for a QUEUED item (same contract). */
    @Synchronized
    fun cancelTask(id: Long) = skipTask(id)

    /** Empty the queue entirely (finished queues stay visible until then). */
    @Synchronized
    fun clear() {
        _tasks.value = emptyList()
        _currentIndex.value = -1
    }

    /**
     * Abort semantics: the RUNNING item becomes FAILED, every QUEUED
     * item becomes SKIPPED, and nothing remains runnable — the items
     * stay in [tasks] so the HUD can show the final outcome.
     */
    @Synchronized
    fun abortAll() {
        val now = System.currentTimeMillis()
        val abortedRunning = _tasks.value.firstOrNull { it.status == TaskStatus.RUNNING }
            ?.copy(status = TaskStatus.FAILED, endedAt = now)
        _tasks.value = _tasks.value.map { item ->
            when (item.status) {
                TaskStatus.RUNNING -> item.copy(status = TaskStatus.FAILED, endedAt = now)
                TaskStatus.QUEUED -> item.copy(status = TaskStatus.SKIPPED, endedAt = now)
                else -> item
            }
        }
        _currentIndex.value = -1
        // #54: the aborted RUNNING task counts as finished (FAILED).
        abortedRunning?.let { prependHistory(it) }
    }

    private fun update(id: Long, status: TaskStatus) {
        val now = System.currentTimeMillis()
        var indexOfItem = -1
        val updated = _tasks.value.mapIndexed { index, item ->
            if (item.id != id) {
                item
            } else {
                indexOfItem = index
                when (status) {
                    TaskStatus.RUNNING -> item.copy(
                        status = status,
                        startedAt = item.startedAt ?: now,
                        endedAt = null
                    )
                    TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.SKIPPED -> item.copy(
                        status = status,
                        endedAt = now
                    )
                    TaskStatus.QUEUED -> item.copy(status = status)
                }
            }
        }
        if (indexOfItem < 0) return
        _tasks.value = updated
        // #54: terminal transitions land in the bounded history.
        if (status == TaskStatus.DONE || status == TaskStatus.FAILED) {
            updated.getOrNull(indexOfItem)?.let { prependHistory(it) }
        }
        _currentIndex.value = when (status) {
            TaskStatus.RUNNING -> indexOfItem
            else -> if (_currentIndex.value == indexOfItem) {
                // Advance to the next still-queued item, if any.
                val next = updated.indexOfFirst { it.status == TaskStatus.QUEUED }
                next
            } else {
                _currentIndex.value
            }
        }
    }

}

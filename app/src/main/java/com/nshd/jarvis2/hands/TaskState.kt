package com.nshd.jarvis2.hands

import com.nshd.jarvis2.brain.TaskQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live state of the agent task currently running (if any), observed by
 * the floating overlay's 4-step Task HUD: goal, recent steps, running
 * indicator, progress.
 *
 * [AgentPipeline] is the producer: it calls [begin] when a user request
 * starts, [step] for every tool event, and [finish] when the request
 * resolves. Overlay single-tap during a task calls [abort], which
 * cancels the pipeline's coroutine [Job].
 *
 * Plain singleton object — no nested classes needed, so the L3 rule
 * (no `inner` / nested `companion` inside objects) is trivially met.
 */
object TaskState {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _goal = MutableStateFlow("")
    val goal: StateFlow<String> = _goal.asStateFlow()

    /** Last 4 step lines, oldest first, prefixed with ✅/🔧/❌ by the pipeline. */
    private val _steps = MutableStateFlow<List<String>>(emptyList())
    val steps: StateFlow<List<String>> = _steps.asStateFlow()

    /** 0..1 when the denominator is known, -1f = indeterminate. */
    private val _progress = MutableStateFlow(-1f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _startedAt = MutableStateFlow(0L)
    val startedAt: StateFlow<Long> = _startedAt.asStateFlow()

    /** Outcome line of the most recently finished task (HUD status line). */
    private val _lastOutcome = MutableStateFlow<String?>(null)
    val lastOutcome: StateFlow<String?> = _lastOutcome.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var maxSteps: Int = 0

    @Volatile
    private var stepsDone: Int = 0

    /** Total step events seen in the current task (HUD "running" counter). */
    @Volatile
    var totalSteps: Int = 0
        private set

    fun begin(goal: String, job: Job, maxSteps: Int = 0) {
        this.job = job
        this.maxSteps = maxSteps
        this.stepsDone = 0
        this.totalSteps = 0
        _goal.value = goal
        _steps.value = emptyList()
        _progress.value = if (maxSteps > 0) 0f else -1f
        _startedAt.value = System.currentTimeMillis()
        _lastOutcome.value = null
        _active.value = true
    }

    fun attachJob(job: Job) {
        this.job = job
    }

    // F6 audit: producers now sit on two IO threads (the pipeline's
    // dispatcher and the Live tool coroutine) — the read-modify-write
    // of the steps list + counters is synchronized so a step line can
    // never be lost to a race.
    @Synchronized
    fun step(text: String) {
        if (!_active.value) return
        totalSteps++
        // Only completed/failed events count toward progress; the "🔧"
        // working marker is display-only.
        if (text.startsWith("✅") || text.startsWith("❌")) {
            stepsDone++
            if (maxSteps > 0) {
                _progress.value = (stepsDone.toFloat() / maxSteps).coerceIn(0f, 1f)
            }
        }
        _steps.value = (_steps.value + text).takeLast(4)
    }

    fun finish(success: Boolean, summary: String) {
        _lastOutcome.value = (if (success) "✅ " else "❌ ") + summary
        if (success && maxSteps > 0) _progress.value = 1f
        _active.value = false
        job = null
    }

    /** Overlay single-tap: cancel the running pipeline coroutine. */
    fun abort() {
        // F3: aborting also aborts the whole multitask queue (running
        // sub-task → FAILED, queued ones → SKIPPED, nothing runnable).
        TaskQueue.abortAll()
        val running = job
        if (running != null && running.isActive) {
            running.cancel()
        }
        finish(false, "Aborted")
    }

    fun isAbortRequested(): Boolean = job?.isCancelled == true && !_active.value

    fun clear() {
        job = null
        _active.value = false
        _goal.value = ""
        _steps.value = emptyList()
        _progress.value = -1f
        _startedAt.value = 0L
        _lastOutcome.value = null
        totalSteps = 0
        stepsDone = 0
        maxSteps = 0
    }
}

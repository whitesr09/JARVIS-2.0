package com.nshd.jarvis2.brain

import com.nshd.jarvis2.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle of one queued sub-task (F3 multitask queue). */
enum class TaskStatus { QUEUED, RUNNING, DONE, FAILED, SKIPPED }

data class TaskItem(
    val id: Long,
    val title: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** F6: live step line for the RUNNING item ("Searching 'One Name'…"). */
    val step: String = ""
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
        AppLog.d("Queue", "task #$id running")
        update(id, TaskStatus.RUNNING)
    }

    @Synchronized
    fun markRunning(index: Int) {
        _tasks.value.getOrNull(index)?.let { update(it.id, TaskStatus.RUNNING) }
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
        _tasks.value = _tasks.value.map { item ->
            when (item.status) {
                TaskStatus.RUNNING -> item.copy(status = TaskStatus.FAILED, endedAt = now)
                TaskStatus.QUEUED -> item.copy(status = TaskStatus.SKIPPED, endedAt = now)
                else -> item
            }
        }
        _currentIndex.value = -1
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

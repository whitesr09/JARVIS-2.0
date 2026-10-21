package com.nshd.jarvis2.brain

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
    val endedAt: Long? = null
)

/**
 * Ordered multitask queue (F3).
 *
 * [AgentPipeline] submits the sub-tasks of one compound request here
 * and drives them one by one; the floating overlay's multitask HUD
 * (FloatingOverlayService) only READS [tasks] / [currentIndex].
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
        return items
    }

    @Synchronized
    fun markRunning(id: Long) = update(id, TaskStatus.RUNNING)

    @Synchronized
    fun markRunning(index: Int) {
        _tasks.value.getOrNull(index)?.let { update(it.id, TaskStatus.RUNNING) }
    }

    @Synchronized
    fun markDone(id: Long) = update(id, TaskStatus.DONE)

    @Synchronized
    fun markDone(index: Int) {
        _tasks.value.getOrNull(index)?.let { update(it.id, TaskStatus.DONE) }
    }

    @Synchronized
    fun markFailed(id: Long) = update(id, TaskStatus.FAILED)

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

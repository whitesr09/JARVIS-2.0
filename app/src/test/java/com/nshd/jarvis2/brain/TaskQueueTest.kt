package com.nshd.jarvis2.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskQueueTest {

    @Test
    fun freshQueuedItemIsNotExpired() {
        val item = TaskItem(id = 1, title = "Test")
        assertFalse(TaskQueue.isExpired(item))
    }

    @Test
    fun queuedItemOlderThanTenMinutesIsExpired() {
        // S3: a QUEUED item older than ~10 min is stale and must
        // never be promoted.
        val stale = TaskItem(
            id = 2,
            title = "Stale",
            queuedAt = System.currentTimeMillis() - 11 * 60 * 1000L
        )
        assertTrue(TaskQueue.isExpired(stale))
    }

    @Test
    fun runningItemNeverExpires() {
        // Expiry only applies to items still waiting for promotion.
        val oldRunning = TaskItem(
            id = 3,
            title = "Running",
            status = TaskStatus.RUNNING,
            queuedAt = System.currentTimeMillis() - 60 * 60 * 1000L
        )
        assertFalse(TaskQueue.isExpired(oldRunning))
    }

    @Test
    fun expireTaskIgnoresFreshQueuedItems() {
        // Defense in depth: expireTask enforces staleness itself, so
        // a miscall can never drop a fresh item.
        val items = TaskQueue.submit(listOf("A", "B"))
        try {
            val first = items.first()
            TaskQueue.expireTask(first.id)
            val live = TaskQueue.tasks.value.first { it.id == first.id }
            assertEquals(TaskStatus.QUEUED, live.status)
        } finally {
            TaskQueue.clear()
        }
    }

    @Test
    fun expireTaskIgnoresUnknownId() {
        TaskQueue.submit(listOf("A"))
        try {
            // Must not throw; the queue is untouched.
            TaskQueue.expireTask(999_999L)
            assertEquals(1, TaskQueue.tasks.value.size)
        } finally {
            TaskQueue.clear()
        }
    }
}

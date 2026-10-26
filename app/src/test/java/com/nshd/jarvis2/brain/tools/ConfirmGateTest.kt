package com.nshd.jarvis2.brain.tools

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmGateTest {

    @Test
    fun cancellingConfirmationClearsPendingGate() = runBlocking {
        ConfirmGate.pending.value?.let { ConfirmGate.dismiss(it.id) }

        val waiting = launch {
            ConfirmGate.confirm("private action details must not be logged")
        }
        withTimeout(2_000) {
            while (ConfirmGate.pending.value == null) yield()
        }

        waiting.cancelAndJoin()

        assertNull("cancelled confirmation must not remain visible", ConfirmGate.pending.value)
    }

    @Test
    fun staleGateIdCannotApproveLaterConfirmation() = runBlocking {
        var firstResult = true
        val first = launch {
            firstResult = ConfirmGate.confirm("first action")
        }
        val firstGate = withTimeout(2_000) {
            while (true) {
                ConfirmGate.pending.value?.let { return@withTimeout it }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        ConfirmGate.complete(firstGate.id, approved = false)
        first.join()
        assertFalse(firstResult)

        var secondResult = true
        val second = launch {
            secondResult = ConfirmGate.confirm("second action")
        }
        val secondGate = withTimeout(2_000) {
            while (true) {
                ConfirmGate.pending.value?.takeIf { it.id != firstGate.id }
                    ?.let { return@withTimeout it }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        ConfirmGate.complete(firstGate.id, approved = true)
        assertEquals(secondGate.id, ConfirmGate.pending.value?.id)

        ConfirmGate.complete(secondGate.id, approved = false)
        second.join()
        assertFalse(secondResult)
        assertNull(ConfirmGate.pending.value)
    }

    @Test
    fun secondConfirmationCannotReplaceFirstPendingGate() = runBlocking {
        ConfirmGate.pending.value?.let { ConfirmGate.dismiss(it.id) }
        var firstResult = false
        val first = launch {
            firstResult = ConfirmGate.confirm("first action")
        }
        val firstGate = withTimeout(2_000) {
            while (true) {
                ConfirmGate.pending.value?.let { return@withTimeout it }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        val secondResult = ConfirmGate.confirm("second action")

        assertFalse("concurrent confirmation must fail closed", secondResult)
        assertEquals("the first prompt must remain the active prompt", firstGate.id, ConfirmGate.pending.value?.id)

        ConfirmGate.complete(firstGate.id, approved = true)
        first.join()

        assertTrue(firstResult)
        assertNull(ConfirmGate.pending.value)
    }
}

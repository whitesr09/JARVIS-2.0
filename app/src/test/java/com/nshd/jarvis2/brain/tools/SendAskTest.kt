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

class SendAskTest {

    @Test
    fun cancelledAskClearsPendingStateAndAllowsNextAsk() = runBlocking {
        var firstResult = false
        val first = launch {
            firstResult = SendAsk.ask("Example contact", "Example message")
        }
        withTimeout(2_000) {
            while (SendAsk.pending.value == null) yield()
        }

        first.cancelAndJoin()

        assertNull("cancelled ask must not remain visible", SendAsk.pending.value)
        assertFalse("cancelled send confirmation must not approve", firstResult)

        val second = launch {
            firstResult = SendAsk.ask("Example contact", "Example message")
        }
        val secondAsk = withTimeout(2_000) {
            while (true) {
                SendAsk.pending.value?.let { return@withTimeout it }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        assertEquals(1, secondAsk.attempt)
        assertEquals(SendAnswer.YES, SendAsk.answer("yes"))
        second.join()

        assertTrue(firstResult)
        assertNull(SendAsk.pending.value)
    }

    @Test
    fun concurrentAskCannotReplaceCurrentPrompt() = runBlocking {
        var firstResult = false
        val first = launch {
            firstResult = SendAsk.ask("First contact", "First message")
        }
        val activeAsk = withTimeout(2_000) {
            while (true) {
                SendAsk.pending.value?.let { return@withTimeout it }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        assertFalse(SendAsk.ask("Second contact", "Second message"))
        assertEquals(activeAsk.id, SendAsk.pending.value?.id)
        assertEquals(SendAnswer.YES, SendAsk.answer("yes"))
        first.join()

        assertTrue(firstResult)
        assertNull(SendAsk.pending.value)
    }

    @Test
    fun longerSentenceIsNotAcceptedAsApproval() {
        assertEquals(SendAnswer.NON_ANSWER, SendAsk.parseAnswer("yes send the other one too"))
        assertEquals(SendAnswer.YES, SendAsk.parseAnswer("yes!"))
        assertEquals(SendAnswer.NO, SendAsk.parseAnswer("cancel."))
    }
}

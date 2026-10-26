package com.nshd.jarvis2.brain

import com.nshd.jarvis2.brain.tools.ToolResult
import com.nshd.jarvis2.hands.ActionOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionLedgerTest {

    private fun args() = JSONObject()
        .put("contact", "1234567890")
        .put("message", "Hello")

    @Test
    fun failedActionMayRetryWithinAttemptBudget() {
        val ledger = ExecutionLedger()
        val args = args()

        assertNull(ledger.gate("whatsapp_message", args))
        ledger.record(
            "whatsapp_message", args,
            ToolResult(false, "Draft could not be verified.")
        )
        assertNull(ledger.gate("whatsapp_message", args))
    }

    @Test
    fun stagedActionIsTerminalForThisTurnAndPreservesOutcome() {
        val ledger = ExecutionLedger()
        val args = args()

        assertNull(ledger.gate("whatsapp_message", args))
        ledger.record(
            "whatsapp_message", args,
            ToolResult(
                success = false,
                message = "Draft is open; user must send it.",
                outcome = ActionOutcome.STAGED_FOR_USER
            )
        )

        val cached = ledger.gate("whatsapp_message", args)
        assertNotNull(cached)
        assertFalse(cached!!.success)
        assertEquals(ActionOutcome.STAGED_FOR_USER, cached.outcome)
        assertTrue(cached.message.contains("Do not retry"))
        assertNotNull(ledger.gate("whatsapp_message", args))
    }

    @Test
    fun submittedUnverifiedAndUnknownOutcomesCannotBeRetried() {
        listOf(
            ActionOutcome.SUBMITTED_UNVERIFIED,
            ActionOutcome.OUTCOME_UNKNOWN
        ).forEach { outcome ->
            val ledger = ExecutionLedger()
            val args = args()
            assertNull(ledger.gate("sms_send", args))
            ledger.record(
                "sms_send", args,
                ToolResult(false, "Check the conversation before retrying.", outcome)
            )

            val cached = ledger.gate("sms_send", args)
            assertNotNull(cached)
            assertFalse(cached!!.success)
            assertEquals(outcome, cached.outcome)
            assertTrue(cached.message.contains("Do not retry"))
        }
    }

    @Test
    fun successfulCachedResultPreservesOutcomeAndMessage() {
        val ledger = ExecutionLedger()
        val args = JSONObject().put("action", "open")
        assertNull(ledger.gate("open_app", args))
        ledger.record(
            "open_app", args,
            ToolResult(true, "Opened settings.", outcome = ActionOutcome.COMPLETED)
        )

        val cached = ledger.gate("open_app", args)
        assertTrue(cached!!.success)
        assertEquals(ActionOutcome.COMPLETED, cached.outcome)
        assertTrue(cached.message.startsWith(ExecutionLedger.ALREADY_DONE_PREFIX))
    }
}

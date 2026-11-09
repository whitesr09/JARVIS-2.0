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
                ToolResult(
                    success = false,
                    message = "Check the conversation before retrying.",
                    outcome = outcome
                )
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

    @Test
    fun concurrentIdenticalCallIsRefusedWhileFirstIsInFlight() {
        // F16: the Live path launches one coroutine per tool call, so
        // two identical calls can race past the gate before either
        // records. The second must be refused, not executed.
        val ledger = ExecutionLedger()
        val args = args()

        assertNull(ledger.gate("whatsapp_message", args))
        val racing = ledger.gate("whatsapp_message", args)
        assertNotNull(racing)
        assertFalse(racing!!.success)
        assertTrue(racing.message.contains("already running"))

        // Once the first execution records, the normal cached-success
        // path takes over (not the in-flight refusal).
        ledger.record(
            "whatsapp_message", args,
            ToolResult(true, "Sent.", outcome = ActionOutcome.COMPLETED)
        )
        val cached = ledger.gate("whatsapp_message", args)
        assertTrue(cached!!.success)
        assertTrue(cached.message.startsWith(ExecutionLedger.ALREADY_DONE_PREFIX))
    }

    @Test
    fun cancelledInFlightCallReleasesItsKeyForRetry() {
        // F16: a cancelled execution never reaches record() — without
        // release(), its key would stay blocked for the whole turn.
        val ledger = ExecutionLedger()
        val args = args()

        assertNull(ledger.gate("whatsapp_message", args))
        ledger.release("whatsapp_message", args)
        assertNull(ledger.gate("whatsapp_message", args))
    }

    @Test
    fun integralDoublesShareKeysWithLongs() {
        // F18: normalize() renders integral doubles as longs, so 500
        // and 500.0 share one ledger key (and one loop-guard budget)
        // instead of giving a model that varies its number format a
        // fresh attempt budget.
        val ledger = ExecutionLedger()
        val asLong = JSONObject().put("x", 500).put("y", 250)
        val asDouble = JSONObject().put("x", 500.0).put("y", 250.0)
        assertEquals(
            ledger.keyFor("tap_percent", asLong),
            ledger.keyFor("tap_percent", asDouble)
        )

        // Behaviour: the second gate on the same logical call is an
        // in-flight refusal, not a fresh attempt.
        assertNull(ledger.gate("tap_percent", asDouble))
        val refused = ledger.gate("tap_percent", asLong)
        assertNotNull(refused)
        assertFalse(refused!!.success)
        assertTrue(refused.message.contains("already running"))

        // A genuinely different value (500.5) is a different key.
        val other = JSONObject().put("x", 500.5).put("y", 250.0)
        assertNull(ledger.gate("tap_percent", other))
    }

    @Test
    fun keysAreCanonicalRegardlessOfArgumentOrder() {
        // normalize() sorts object keys recursively, so the same
        // logical call with reordered keys (or reordered nested keys)
        // produces one key.
        val ledger = ExecutionLedger()
        val a = JSONObject().put("b", 1)
            .put("a", JSONObject().put("y", 2).put("x", 1))
        val b = JSONObject().put("a", JSONObject().put("x", 1).put("y", 2))
            .put("b", 1)
        assertEquals(ledger.keyFor("some_tool", a), ledger.keyFor("some_tool", b))
    }

    @Test
    fun gateRepeatableCapsIdenticalTypingAtThree() {
        // F18: type_text with identical args appends identical text —
        // beyond REPEATABLE_MAX_EXECUTIONS executions in one turn it
        // is a loop, not intent.
        val ledger = ExecutionLedger()
        val args = JSONObject().put("text", "hello")
        repeat(ExecutionLedger.REPEATABLE_MAX_EXECUTIONS) {
            assertNull(ledger.gateRepeatable("type_text", args))
            ledger.record(
                "type_text", args,
                ToolResult(true, "Typed.", outcome = ActionOutcome.COMPLETED)
            )
        }
        val refused = ledger.gateRepeatable("type_text", args)
        assertNotNull(refused)
        assertFalse(refused!!.success)
        assertTrue(
            refused.message.contains(
                "${ExecutionLedger.REPEATABLE_MAX_EXECUTIONS} times"
            )
        )
    }

    @Test
    fun gateRepeatableRefusesConcurrentIdenticalCall() {
        // Same in-flight rule as the normal gate: two identical
        // type_text calls racing in separate coroutines must not
        // both execute.
        val ledger = ExecutionLedger()
        val args = JSONObject().put("text", "hello")
        assertNull(ledger.gateRepeatable("type_text", args))
        val racing = ledger.gateRepeatable("type_text", args)
        assertNotNull(racing)
        assertFalse(racing!!.success)
        assertTrue(racing.message.contains("already running"))
    }

    @Test
    fun gateRepeatableCountsDistinctArgsSeparately() {
        // The cap is per exact key — different text gets its own
        // budget, so legitimate repeated typing of different
        // strings is untouched.
        val ledger = ExecutionLedger()
        val first = JSONObject().put("text", "hello")
        val second = JSONObject().put("text", "world")
        repeat(ExecutionLedger.REPEATABLE_MAX_EXECUTIONS) {
            assertNull(ledger.gateRepeatable("type_text", first))
            ledger.record(
                "type_text", first,
                ToolResult(true, "Typed.", outcome = ActionOutcome.COMPLETED)
            )
        }
        assertNull(ledger.gateRepeatable("type_text", second))
    }
}

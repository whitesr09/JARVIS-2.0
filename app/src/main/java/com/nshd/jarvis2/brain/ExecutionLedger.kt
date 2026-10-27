package com.nshd.jarvis2.brain

import com.nshd.jarvis2.brain.tools.ToolResult
import com.nshd.jarvis2.hands.ActionOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-turn execution ledger (F3 loop/replay fix).
 *
 * One ledger instance lives for exactly one user turn (AgentPipeline
 * creates a fresh one per turn — and per sub-task in a multitask queue;
 * VoiceSessionManager keeps one per Gemini Live turn). Every tool call
 * is keyed as `toolName + "|" + normalizedArgsJson` where the args JSON
 * is canonicalised (object keys sorted recursively, string values
 * trimmed) so the same logical call always produces the same key.
 *
 * Rules:
 *  - A key that already SUCCEEDED is never executed again this turn:
 *    [gate] returns the cached result prefixed with
 *    "Already done this turn — do not repeat."
 *  - A normal FAILED key may retry, up to [MAX_ATTEMPTS] total attempts
 *    per turn; past that, [gate] returns the last failure.
 *  - STAGED_FOR_USER, SUBMITTED_UNVERIFIED, and OUTCOME_UNKNOWN are
 *    terminal for this turn: a repeat could duplicate an external action.
 *
 * This supersedes the old "no replay on provider failover" concern for
 * same-turn duplicates: even if a model re-issues a call, the phone
 * action itself cannot run twice.
 */
class ExecutionLedger {

    private class Entry {
        var attempts: Int = 0
        var success: ToolResult? = null
        var lastFailure: ToolResult? = null
        // These outcomes are not ordinary failures: an external side effect
        // may already have happened, or a draft is intentionally left staged.
        // Retrying could duplicate a message or reopen/re-submit the action.
        var terminalOutcome: ToolResult? = null
    }

    private val entries = mutableMapOf<String, Entry>()

    /**
     * F16: keys whose execution is currently IN FLIGHT. [gate] only
     * dedupes COMPLETED entries — two identical tool calls racing in
     * separate coroutines (the Gemini Live path launches one coroutine
     * per tool call) both passed the gate before either recorded, so
     * the same phone action ran twice at once. A key in this set is
     * refused until [record] (or [release] on cancellation) clears it.
     */
    private val inFlight = mutableSetOf<String>()

    /** F18: per-key execution counts for [gateRepeatable]. */
    private val repeatExecutions = mutableMapOf<String, Int>()

    fun keyFor(toolName: String, args: JSONObject): String =
        toolName + "|" + normalize(args)

    /**
     * Call BEFORE executing. Returns a cached result when the call must
     * NOT execute (already succeeded, already running, or
     * failure-attempt budget spent); returns null and records one
     * attempt when execution may proceed.
     */
    @Synchronized
    fun gate(toolName: String, args: JSONObject): ToolResult? {
        val key = keyFor(toolName, args)
        val entry = entries.getOrPut(key) { Entry() }
        if (key in inFlight) {
            return ToolResult(
                false,
                IN_FLIGHT_PREFIX +
                    "This exact action is already running — wait for its result instead of repeating it."
            )
        }
        entry.terminalOutcome?.let { terminal ->
            return terminal.copy(
                success = false,
                message = NO_REPEAT_PREFIX + terminal.message
            )
        }
        entry.success?.let { cached ->
            return cached.copy(message = ALREADY_DONE_PREFIX + cached.message)
        }
        if (entry.attempts >= MAX_ATTEMPTS) {
            val failure = entry.lastFailure
            return if (failure != null) {
                failure.copy(message = ALREADY_DONE_PREFIX + failure.message)
            } else {
                ToolResult(false, ALREADY_DONE_PREFIX + "Attempt limit reached for $toolName.")
            }
        }
        entry.attempts++
        inFlight += key
        return null
    }

    /**
     * F18: capped gate for repeatable typing (type_text only).
     * edit_text stays exempt at the ToolExecutor level (its actions
     * are state-relative — delete_last_line twice must delete two
     * lines). type_text with identical args appends identical text,
     * so beyond [REPEATABLE_MAX_EXECUTIONS] executions in one turn
     * it is a loop, not intent — and the Live path has no loop guard
     * of its own, so the ledger must enforce it. In-flight identical
     * calls are refused like the normal gate; [record] clears the
     * in-flight mark (its success/terminal bookkeeping is unused for
     * repeatables, which never consult [entries]).
     */
    @Synchronized
    fun gateRepeatable(toolName: String, args: JSONObject): ToolResult? {
        val key = keyFor(toolName, args)
        if (key in inFlight) {
            return ToolResult(
                false,
                IN_FLIGHT_PREFIX +
                    "This exact action is already running — wait for its result instead of repeating it."
            )
        }
        val count = (repeatExecutions[key] ?: 0) + 1
        if (count > REPEATABLE_MAX_EXECUTIONS) {
            return ToolResult(
                false,
                "This exact typing was already performed $REPEATABLE_MAX_EXECUTIONS times this turn — refusing a further repeat to avoid duplicated text. If the user really wants it again, rephrase the request."
            )
        }
        repeatExecutions[key] = count
        inFlight += key
        return null
    }

    /** Call AFTER executing with the raw (pre-screen-summary) result. */
    @Synchronized
    fun record(toolName: String, args: JSONObject, result: ToolResult) {
        val key = keyFor(toolName, args)
        inFlight -= key
        val entry = entries.getOrPut(key) { Entry() }
        if (result.outcome in TERMINAL_OUTCOMES) {
            entry.terminalOutcome = result
            entry.lastFailure = null
        } else if (result.success) {
            entry.success = result
        } else {
            entry.lastFailure = result
        }
    }

    /**
     * F16: release an in-flight key WITHOUT recording a result — for
     * cancellation paths (coroutine cancelled / user stop) where
     * [record] never runs. Without this, a cancelled action would
     * block its identical retry for the rest of the turn.
     */
    @Synchronized
    fun release(toolName: String, args: JSONObject) {
        inFlight -= keyFor(toolName, args)
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        /** F18: identical type_text executions allowed per turn. */
        const val REPEATABLE_MAX_EXECUTIONS = 3
        const val ALREADY_DONE_PREFIX = "Already done this turn — do not repeat. "
        private const val NO_REPEAT_PREFIX =
            "This action's outcome is staged or uncertain. Do not retry until the user checks it. "
        private const val IN_FLIGHT_PREFIX =
            "Already running this turn — do not repeat. "
        private val TERMINAL_OUTCOMES = setOf(
            ActionOutcome.STAGED_FOR_USER,
            ActionOutcome.SUBMITTED_UNVERIFIED,
            ActionOutcome.OUTCOME_UNKNOWN
        )

        /** Canonical string form of a JSON value: sorted object keys. */
        private fun normalize(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys += iterator.next()
                keys.sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                    "\"" + key + "\":" + normalize(value.opt(key))
                }
            }
            is JSONArray -> (0 until value.length()).joinToString(
                separator = ",", prefix = "[", postfix = "]"
            ) { index -> normalize(value.opt(index)) }
            is String -> "\"" + value.trim() + "\""
            // F18: integral numbers render identically regardless of
            // JSON formatting — 500 and 500.0 must share one ledger
            // key (and one loop-guard signature), or a model varying
            // its number format gets a fresh attempt budget.
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is Float -> if (value % 1.0f == 0.0f) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
    }
}

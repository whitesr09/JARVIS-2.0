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
 *  - A FAILED key may retry, up to [MAX_ATTEMPTS] total attempts per
 *    turn; past that, [gate] returns the last failure (same prefix).
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

    fun keyFor(toolName: String, args: JSONObject): String =
        toolName + "|" + normalize(args)

    /**
     * Call BEFORE executing. Returns a cached result when the call must
     * NOT execute (already succeeded, or failure-attempt budget spent);
     * returns null and records one attempt when execution may proceed.
     */
    @Synchronized
    fun gate(toolName: String, args: JSONObject): ToolResult? {
        val entry = entries.getOrPut(keyFor(toolName, args)) { Entry() }
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
        return null
    }

    /** Call AFTER executing with the raw (pre-screen-summary) result. */
    @Synchronized
    fun record(toolName: String, args: JSONObject, result: ToolResult) {
        val entry = entries.getOrPut(keyFor(toolName, args)) { Entry() }
        if (result.outcome in TERMINAL_OUTCOMES) {
            entry.terminalOutcome = result
            entry.lastFailure = null
        } else if (result.success) {
            entry.success = result
        } else {
            entry.lastFailure = result
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val ALREADY_DONE_PREFIX = "Already done this turn — do not repeat. "
        private const val NO_REPEAT_PREFIX =
            "This action's outcome is staged or uncertain. Do not retry until the user checks it. "
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
            else -> value.toString()
        }
    }
}

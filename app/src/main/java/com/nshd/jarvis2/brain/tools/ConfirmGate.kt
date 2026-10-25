package com.nshd.jarvis2.brain.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

data class PendingConfirm(
    val id: Long,
    val description: String,
    /** F7: when the prompt appeared — the renderers' 30 s timer bars read it. */
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Native hard-guardrail confirmation gate.
 *
 * Unlike the reference implementation's keyword flag (which trusted the
 * model to ask), tool execution itself suspends here. No response in
 * 30 seconds means DENY.
 *
 * F7 GateHost: [pending] IS the host bus — every renderer observes the
 * same StateFlow and completes the same request: (a) the in-app
 * dialog (ConfirmGateDialog in Chat/Voice), (b) the floating overlay's
 * gate card (FloatingOverlayService) for commands given while another
 * app is foreground, (c) spoken yes/no replies intercepted by
 * VoiceSessionManager's Live transcript intercept during Live voice
 * turns. Whichever surface the user reaches first wins; [complete]
 * is idempotent per id. v2.0.3: sends NEVER enter this gate — they
 * suspend in SendAsk instead (see HandsActions.SensitiveTapGuard).
 */
object ConfirmGate {
    private val nextId = AtomicLong(1)
    private val _pending = MutableStateFlow<PendingConfirm?>(null)
    val pending: StateFlow<PendingConfirm?> = _pending.asStateFlow()

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    suspend fun confirm(description: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val item: PendingConfirm

        // Check and publish atomically. Keeping this in one critical section
        // prevents two near-simultaneous callers from replacing each other's
        // pending confirmation.
        synchronized(this) {
            if (deferred?.isActive == true) {
                com.nshd.jarvis2.core.AppLog.w(
                    "Gate", "confirmation suppressed because another gate is pending"
                )
                return false
            }
            item = PendingConfirm(nextId.getAndIncrement(), description)
            deferred = answer
            _pending.value = item
        }

        // Do not log the description: it can contain contact names, message
        // text, or other private details from the proposed phone action.
        com.nshd.jarvis2.core.AppLog.d("Gate", "prompt opened: id=${item.id}")
        try {
            val result = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { answer.await() }
            val approved = result == true
            com.nshd.jarvis2.core.AppLog.i(
                "Gate",
                "decision: ${when {
                    result == null -> "timeout (deny)"
                    approved -> "allow"
                    else -> "deny"
                }} — gate #${item.id}"
            )
            return approved
        } finally {
            // Also runs when the owning tool/task is cancelled. Without this,
            // a cancelled caller could leave a stale prompt that blocks all
            // later confirmations until timeout (or indefinitely after a bug).
            synchronized(this) {
                if (deferred === answer) {
                    deferred = null
                    if (_pending.value?.id == item.id) _pending.value = null
                }
            }
            if (answer.isActive) answer.cancel()
        }
    }

    fun complete(id: Long, approved: Boolean) {
        synchronized(this) {
            val current = _pending.value ?: return
            if (current.id != id) return
            deferred?.complete(approved)
            deferred = null
            _pending.value = null
        }
    }

    fun dismiss(id: Long) = complete(id, approved = false)

    private const val CONFIRM_TIMEOUT_MS = 30_000L
}

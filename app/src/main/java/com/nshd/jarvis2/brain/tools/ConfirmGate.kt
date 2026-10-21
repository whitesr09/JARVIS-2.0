package com.nshd.jarvis2.brain.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

data class PendingConfirm(
    val id: Long,
    val description: String
)

/**
 * Native hard-guardrail confirmation gate.
 *
 * Unlike the reference implementation's keyword flag (which trusted the
 * model to ask), tool execution itself suspends here. A dialog bound to
 * [pending] in Chat or Voice completes the request. No response in 30
 * seconds means DENY.
 */
object ConfirmGate {
    private val nextId = AtomicLong(1)
    private val _pending = MutableStateFlow<PendingConfirm?>(null)
    val pending: StateFlow<PendingConfirm?> = _pending.asStateFlow()

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    suspend fun confirm(description: String): Boolean {
        // Only one native prompt can be meaningfully answered at a time.
        synchronized(this) {
            if (deferred?.isActive == true) return false
        }
        val answer = CompletableDeferred<Boolean>()
        val item = PendingConfirm(nextId.getAndIncrement(), description)
        synchronized(this) {
            deferred = answer
            _pending.value = item
        }
        val approved = withTimeoutOrNull(30_000) { answer.await() } ?: false
        synchronized(this) {
            if (deferred === answer) {
                deferred = null
                _pending.value = null
            }
        }
        return approved
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
}

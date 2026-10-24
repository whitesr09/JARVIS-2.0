package com.nshd.jarvis2.brain.tools

import com.nshd.jarvis2.brain.TaskQueue
import com.nshd.jarvis2.core.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * v2.0.2 — just-in-time send confirmation (SUPERSEDES the old card
 * rule for sends; the safety substance is unchanged: recipient + FULL
 * text are confirmed before any send tap/dispatch).
 *
 * A send tool's flow is now: resolve recipient → open chat/composer
 * → type/fill text → VERIFY the entry → ASK (here) → send tap.
 * The ask is NEVER a card or dialog:
 *  - a plain line in the floating panel's result area (TaskQueue's
 *    result-line/step channel), and
 *  - a plain chat/caption message via [askSink] — AgentPipeline sets
 *    this to the running turn's onAssistantDelta, so the ask streams
 *    into the chat bubble (typed chat) or the Voice caption (voice)
 *    as ordinary text. It is never spoken.
 *
 * Answers: a voice session in progress answers with one spoken
 * yes/no (VoiceSessionManager's SendAsk host intercepts it); a
 * typed-chat session answers with its next message (AgentPipeline
 * checks [pending] BEFORE normal routing — no mic auto-start).
 * Yes → the caller grants the existing one-shot SendPermit and
 * performs the send immediately (permit coverage is what prevents
 * the F7 second-gate deadlock). No / timeout → abort, "Not sent.",
 * composed text left untouched where possible. A non-yes/no answer
 * re-asks ONCE ("Say yes to send, or no to cancel."), then a second
 * non-answer aborts. A second send tool invoked while an ask is
 * pending is refused ([isPending] → caller reports busy) rather
 * than stacking asks.
 *
 * ConfirmGate STAYS for non-send classes (payments, destructive
 * actions, unknown-number calls, bulk) — this gate covers sends only.
 */
data class PendingSendAsk(
    val id: Long,
    val recipient: String,
    val text: String,
    val attempt: Int,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    /** The exact ask line shown to the user (recipient + FULL text). */
    val askLine: String
        get() = "Ready to send to $recipient? $text"
}

/** Outcome of one user answer to a send ask. */
enum class SendAnswer { YES, NO, NON_ANSWER }

object SendAsk {
    private const val ASK_TIMEOUT_MS = 30_000L
    private const val REASK_LINE = "Say yes to send, or no to cancel."

    private val nextId = AtomicLong(1)
    private val _pending = MutableStateFlow<PendingSendAsk?>(null)
    val pending: StateFlow<PendingSendAsk?> = _pending.asStateFlow()

    @Volatile
    private var deferred: CompletableDeferred<SendAnswer>? = null

    /** Live-text sink for the ask line (pipeline's onAssistantDelta). */
    @Volatile
    var askSink: ((String) -> Unit)? = null

    /** True while a send ask is awaiting an answer. */
    val isPending: Boolean
        get() = deferred?.isActive == true

    /**
     * Classify a raw yes/no reply. Shared by the voice intercept and
     * the typed-chat intercept so both accept exactly the same words:
     * yes/yeah/send/send it/ok/okay/confirm = yes; no/cancel/stop/
     * don't = no; anything else = non-answer. Matching is on the
     * whole trimmed message (trailing punctuation stripped), so a
     * longer message is a non-answer, never an accidental yes.
     */
    fun parseAnswer(raw: String): SendAnswer {
        val normalized = raw.trim().lowercase()
            .trimEnd('.', '!', '?', ' ').trim()
        return when (normalized) {
            "yes", "yeah", "send", "send it", "ok", "okay", "confirm" ->
                SendAnswer.YES
            "no", "cancel", "stop", "don't", "dont" -> SendAnswer.NO
            else -> SendAnswer.NON_ANSWER
        }
    }

    /**
     * Deliver an answer from either intercept. YES/NO complete the
     * waiting ask; NON_ANSWER also completes it with NON_ANSWER so
     * [ask] can publish the single re-ask (the answerer re-listens /
     * waits for the next message). Returns false when no ask is
     * pending (the text is then routed normally).
     */
    fun answer(raw: String): SendAnswer {
        // No ask pending: nothing is consumed (callers check
        // [pending] first, so this is only a safety net).
        val current = deferred ?: return SendAnswer.NON_ANSWER
        val parsed = parseAnswer(raw)
        current.complete(parsed)
        return parsed
    }

    /**
     * Ask, suspending until answered. Returns true only on YES.
     * Callers check [isPending] first and report busy instead of
     * stacking a second ask.
     */
    suspend fun ask(recipient: String, text: String): Boolean {
        synchronized(this) {
            if (deferred?.isActive == true) {
                AppLog.w("SendAsk", "ask suppressed (another ask pending) for $recipient")
                return false
            }
        }
        val id = nextId.getAndIncrement()
        var attempt = 1
        while (attempt <= 2) {
            val item = PendingSendAsk(id, recipient, text, attempt)
            val answer = CompletableDeferred<SendAnswer>()
            synchronized(this) {
                deferred = answer
                _pending.value = item
            }
            AppLog.i("SendAsk", "ask #$id attempt $attempt to $recipient")
            publish(item)
            val result = withTimeoutOrNull(ASK_TIMEOUT_MS) { answer.await() }
            synchronized(this) {
                if (deferred === answer) {
                    deferred = null
                    _pending.value = null
                }
            }
            when (result) {
                SendAnswer.YES -> {
                    AppLog.i("SendAsk", "ask #$id approved")
                    return true
                }
                SendAnswer.NO -> {
                    AppLog.i("SendAsk", "ask #$id denied")
                    return false
                }
                SendAnswer.NON_ANSWER -> {
                    if (attempt == 1) {
                        AppLog.i("SendAsk", "ask #$id non-answer — re-asking once")
                        attempt = 2
                        continue
                    }
                    AppLog.i("SendAsk", "ask #$id second non-answer — aborting")
                    return false
                }
                null -> {
                    AppLog.i("SendAsk", "ask #$id timeout (deny)")
                    return false
                }
            }
        }
        return false
    }

    /** Publish one ask (or re-ask) to every live surface. */
    private fun publish(item: PendingSendAsk) {
        val line = if (item.attempt == 1) {
            item.askLine
        } else {
            "$REASK_LINE\n${item.askLine}"
        }
        // Floating panel: result line (truncated to 120 by TaskQueue)
        // + step line, both plain text, no card.
        runCatching { TaskQueue.setResultLineForRunning(line) }
        runCatching { TaskQueue.setStepForRunning(line) }
        // Chat / Voice caption as a plain message (never spoken).
        runCatching { askSink?.invoke(line) }
    }
}

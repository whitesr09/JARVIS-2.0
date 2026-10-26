package com.nshd.jarvis2.brain

import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb

/**
 * v1.9.0 (#82): auto-compact history. The chat table is pruned at
 * 1000 rows (F6) — without this, the prune silently threw away the
 * oldest context. When the table passes [TRIGGER] rows, the oldest
 * turns (everything except the newest [KEEP_RECENT]) are folded by
 * the chat model into ONE Memory Core note, the folded rows are
 * deleted, and a single assistant-side history row records that the
 * compaction happened. Any failure (no provider, model error) just
 * skips compaction — the F6 prune remains the backstop and no turn
 * is ever blocked on this.
 */
object HistoryCompactor {

    private const val TRIGGER = 600
    private const val KEEP_RECENT = 200
    private const val MIN_FOLD = 50

    @Volatile
    private var running = false

    suspend fun maybeCompact(memoryDb: MemoryDb, providerStore: ProviderStore) {
        if (running) return
        val count = try {
            memoryDb.messageCount()
        } catch (e: Exception) {
            return
        }
        if (count <= TRIGGER) return
        running = true
        try {
            val foldCount = count - KEEP_RECENT
            val old = memoryDb.oldestMessages(foldCount)
            if (old.size < MIN_FOLD) return
            val transcript = old.joinToString("\n") { msg ->
                "${msg.role}: ${msg.content.take(300)}"
            }
            val outcome = FailoverRouter(
                configsInOrder = providerStore.orderedConfigs(),
                toolExecutor = null,
                store = providerStore
            ).chat(
                messages = listOf(
                    ChatMsg(
                        "system",
                        "You compress an old chat history into a memory note. " +
                            "Keep every durable fact, decision, name, plan and " +
                            "unfinished thread. Drop small talk. Maximum 180 words, " +
                            "plain sentences, no markdown."
                    ),
                    ChatMsg("user", transcript.take(24_000))
                ),
                preferReasoningModel = false
            )
            val summary = (outcome as? ChatOutcome.Success)?.reply?.trim()
            if (summary.isNullOrBlank()) {
                AppLog.w("App", "history compact skipped — no summary produced")
                return
            }
            memoryDb.addFact(
                MemoryCategories.GENERAL,
                "Earlier conversation summary: $summary"
            )
            memoryDb.deleteMessages(old.map { it.id })
            memoryDb.addMessage(
                "assistant",
                "[Earlier conversation was compacted into a memory note " +
                    "to save space — ${old.size} old messages folded.]"
            )
            AppLog.i(
                "App",
                "history compacted: ${old.size} messages folded into a memory note"
            )
        } catch (e: Exception) {
            AppLog.w("App", "history compact failed safely (${e.javaClass.simpleName})")
        } finally {
            running = false
        }
    }
}

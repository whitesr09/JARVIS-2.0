package com.nshd.jarvis2.brain.tools

import android.content.Context
import java.security.MessageDigest

/**
 * F18: cross-turn duplicate-send guard.
 *
 * The per-turn [ExecutionLedger] cannot see retries in a NEW turn, and
 * the pipeline's terminal-outcome breaks only stop multi-step flows —
 * so a model re-issuing an identical send in a later turn (or a user
 * repeating the request) produced duplicate messages. This guard closes
 * that hole: a send that succeeded, or completed with an uncertain
 * outcome (SUBMITTED_UNVERIFIED / OUTCOME_UNKNOWN — the message may
 * already be in the chat), is recorded, and an identical send to the
 * same recipient within [WINDOW_MS] is refused without executing.
 *
 * Privacy: only SHA-256 hashes of normalized text are stored — never
 * message text, never contact details beyond the guard key itself
 * (recipient + channel + text hash). Records live in a dedicated
 * prefs file, capped at [MAX_ENTRIES], and expire on their own.
 */
object RecentSends {

    private const val PREFS = "jarvis_recent_sends"
    private const val WINDOW_MS = 30 * 60 * 1000L
    private const val MAX_ENTRIES = 50

    fun wasRecentlySent(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(key, 0L)
        if (ts == 0L) return false
        if (System.currentTimeMillis() - ts > WINDOW_MS) {
            prefs.edit().remove(key).apply()
            return false
        }
        return true
    }

    fun record(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putLong(key, System.currentTimeMillis())
        val all = prefs.all
        if (all.size >= MAX_ENTRIES) {
            all.entries
                .sortedBy { (it.value as? Long) ?: 0L }
                .dropLast(MAX_ENTRIES - 1)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

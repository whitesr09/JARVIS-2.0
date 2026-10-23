package com.nshd.jarvis2.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.9.0 (#9): the recent-wake-events mini-log. False wakes were
 * invisible before — now every wake-phrase decision the wake engine
 * makes (fired, or heard-but-below the sensitivity threshold) lands
 * in this small SharedPreferences ring (last [MAX_EVENTS]) so the
 * user can SEE what the mic thought it heard, with word confidence.
 */
object WakeEvents {

    private const val PREFS = "jarvis_wake_events"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 10

    /** One line: "<time> · <what happened>". */
    fun record(context: Context, event: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("d MMM, h:mm a", Locale.US).format(Date())
        val line = "$stamp · $event"
        val existing = prefs.getString(KEY_EVENTS, "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val updated = (listOf(line) + existing).take(MAX_EVENTS)
        prefs.edit().putString(KEY_EVENTS, updated.joinToString("\n")).apply()
        AppLog.d("Wake", "event logged: $event")
    }

    /** Newest first. */
    fun recent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
}

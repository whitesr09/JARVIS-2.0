package com.nshd.jarvis2.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.0.0 "Brain Upgrade" storage — one SharedPreferences file
 * ("jarvis_brain") holding the Phase-4 learned structures as JSON:
 *
 *  - Macros (#16/#17): a successful turn's executed tool sequence,
 *    saved under a trigger phrase and replayed with NO model call.
 *    Replay runs every step through ToolExecutor, so a macro that
 *    contains a send STILL hits the always-confirm gate with the
 *    recipient + full text — recording never bypasses a gate.
 *  - Custom quick commands: "when I say X, do Y" — phrase → command
 *    text, re-dispatched through the normal pipeline when heard.
 *  - Routines (#115): named step lists with a trigger
 *    (MANUAL / ON_WAKE / ON_APP_OPEN), run by RoutineRunner.
 *  - Scheduled messages (#51): drafts gated at schedule time; an
 *    AlarmManager alarm posts a due notification, and the actual
 *    send re-enters the normal gated send flow at fire time.
 *
 * Also tracks per-goal success counts (#16's "succeeded 2×" offer)
 * and the notification-reader opt-in (#86, off by default).
 */
object BrainStore {

    private const val PREFS = "jarvis_brain"
    private const val KEY_MACROS = "macros"
    private const val KEY_CUSTOM = "custom_commands"
    private const val KEY_ROUTINES = "routines"
    private const val KEY_SCHEDULED = "scheduled"
    private const val KEY_GOAL_COUNTS = "goal_counts"
    private const val KEY_LAST_CANDIDATE = "last_candidate"
    private const val KEY_NOTIF_READER = "notif_reader_enabled"
    private const val KEY_PENDING_SUMMARY = "pending_summary"
    private const val KEY_SCHEDULED_SEQ = "scheduled_seq"

    // ── Data shapes ──────────────────────────────────────────────────

    data class RecordedStep(val tool: String, val argsJson: String)

    data class Macro(
        val trigger: String,
        val steps: List<RecordedStep>,
        val sourceGoal: String
    )

    data class CustomCommand(val phrase: String, val target: String)

    data class Routine(
        val name: String,
        /** MANUAL | ON_WAKE | ON_APP_OPEN */
        val trigger: String,
        val steps: List<String>,
        val lastRunDay: String = ""
    )

    data class ScheduledMessage(
        val id: Long,
        val contact: String,
        val message: String,
        val fireAtMs: Long,
        val due: Boolean = false
    )

    /** A just-finished successful turn that could become a macro. */
    data class TurnCandidate(
        val goal: String,
        val goalKey: String,
        val steps: List<RecordedStep>
    )

    // ── Prefs plumbing ───────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readArray(context: Context, key: String): JSONArray {
        val raw = prefs(context).getString(key, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeArray(context: Context, key: String, array: JSONArray) {
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    /** Normalized matching key for triggers / phrases / goals. */
    fun normalizeKey(text: String): String = text.trim()
        .replace(Regex("\\s+"), " ")
        .trimEnd('.', '!', '?', '…', ' ')
        .lowercase()

    // ── Macros (#16/#17) ─────────────────────────────────────────────

    fun macros(context: Context): List<Macro> {
        val array = readArray(context, KEY_MACROS)
        val out = mutableListOf<Macro>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<RecordedStep>()
            for (j in 0 until stepsArray.length()) {
                val step = stepsArray.optJSONObject(j) ?: continue
                steps += RecordedStep(
                    tool = step.optString("tool"),
                    argsJson = step.optString("args").ifBlank { "{}" }
                )
            }
            out += Macro(
                trigger = obj.optString("trigger"),
                steps = steps,
                sourceGoal = obj.optString("goal")
            )
        }
        return out
    }

    fun findMacro(context: Context, normalizedText: String): Macro? =
        macros(context).firstOrNull { normalizeKey(it.trigger) == normalizedText }

    fun saveMacro(context: Context, macro: Macro) {
        val kept = macros(context).filter {
            normalizeKey(it.trigger) != normalizeKey(macro.trigger)
        }
        val array = JSONArray()
        (kept + macro).forEach { m ->
            val steps = JSONArray()
            m.steps.forEach { step ->
                steps.put(
                    JSONObject()
                        .put("tool", step.tool)
                        .put("args", step.argsJson)
                )
            }
            array.put(
                JSONObject()
                    .put("trigger", m.trigger)
                    .put("goal", m.sourceGoal)
                    .put("steps", steps)
            )
        }
        writeArray(context, KEY_MACROS, array)
        AppLog.i(
            "Brain",
            "macro saved: '${macro.trigger}' (${macro.steps.size} steps)"
        )
    }

    fun deleteMacro(context: Context, trigger: String) {
        val kept = macros(context).filter {
            normalizeKey(it.trigger) != normalizeKey(trigger)
        }
        val array = JSONArray()
        kept.forEach { m ->
            val steps = JSONArray()
            m.steps.forEach { step ->
                steps.put(
                    JSONObject().put("tool", step.tool).put("args", step.argsJson)
                )
            }
            array.put(
                JSONObject()
                    .put("trigger", m.trigger)
                    .put("goal", m.sourceGoal)
                    .put("steps", steps)
            )
        }
        writeArray(context, KEY_MACROS, array)
    }

    // ── #16: success counting + last-turn candidate ─────────────────

    /**
     * Record one successful agent-loop turn. Returns the total number
     * of times this exact goal has now succeeded (the pipeline offers
     * "save as a command" when this reaches 2).
     */
    fun noteSuccessfulTurn(
        context: Context,
        goal: String,
        steps: List<RecordedStep>
    ): Int {
        val key = normalizeKey(goal)
        if (key.isBlank() || steps.isEmpty()) return 0
        val counts = try {
            JSONObject(prefs(context).getString(KEY_GOAL_COUNTS, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val count = counts.optInt(key, 0) + 1
        counts.put(key, count)
        prefs(context).edit().putString(KEY_GOAL_COUNTS, counts.toString()).apply()
        val candidate = JSONObject()
            .put("goal", goal)
            .put("key", key)
            .put(
                "steps",
                JSONArray().apply {
                    steps.forEach { step ->
                        put(
                            JSONObject()
                                .put("tool", step.tool)
                                .put("args", step.argsJson)
                        )
                    }
                }
            )
        prefs(context).edit()
            .putString(KEY_LAST_CANDIDATE, candidate.toString()).apply()
        return count
    }

    fun lastCandidate(context: Context): TurnCandidate? {
        val raw = prefs(context).getString(KEY_LAST_CANDIDATE, null) ?: return null
        val obj = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
        val steps = mutableListOf<RecordedStep>()
        for (j in 0 until stepsArray.length()) {
            val step = stepsArray.optJSONObject(j) ?: continue
            steps += RecordedStep(
                tool = step.optString("tool"),
                argsJson = step.optString("args").ifBlank { "{}" }
            )
        }
        if (steps.isEmpty()) return null
        return TurnCandidate(
            goal = obj.optString("goal"),
            goalKey = obj.optString("key"),
            steps = steps
        )
    }

    fun clearCandidate(context: Context) {
        prefs(context).edit().remove(KEY_LAST_CANDIDATE).apply()
    }

    // ── Custom quick commands ────────────────────────────────────────

    fun customCommands(context: Context): List<CustomCommand> {
        val array = readArray(context, KEY_CUSTOM)
        val out = mutableListOf<CustomCommand>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out += CustomCommand(
                phrase = obj.optString("phrase"),
                target = obj.optString("target")
            )
        }
        return out
    }

    fun findCustomCommand(context: Context, normalizedText: String): CustomCommand? =
        customCommands(context).firstOrNull {
            normalizeKey(it.phrase) == normalizedText
        }

    fun saveCustomCommand(context: Context, command: CustomCommand) {
        val kept = customCommands(context).filter {
            normalizeKey(it.phrase) != normalizeKey(command.phrase)
        }
        val array = JSONArray()
        (kept + command).forEach { c ->
            array.put(
                JSONObject().put("phrase", c.phrase).put("target", c.target)
            )
        }
        writeArray(context, KEY_CUSTOM, array)
        AppLog.i("Brain", "custom command saved: '${command.phrase}'")
    }

    fun deleteCustomCommand(context: Context, phrase: String) {
        val kept = customCommands(context).filter {
            normalizeKey(it.phrase) != normalizeKey(phrase)
        }
        val array = JSONArray()
        kept.forEach { c ->
            array.put(
                JSONObject().put("phrase", c.phrase).put("target", c.target)
            )
        }
        writeArray(context, KEY_CUSTOM, array)
    }

    // ── Routines (#115) ──────────────────────────────────────────────

    fun routines(context: Context): List<Routine> {
        val array = readArray(context, KEY_ROUTINES)
        val out = mutableListOf<Routine>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<String>()
            for (j in 0 until stepsArray.length()) {
                stepsArray.optString(j).takeIf { it.isNotBlank() }?.let { steps += it }
            }
            out += Routine(
                name = obj.optString("name"),
                trigger = obj.optString("trigger").ifBlank { "MANUAL" },
                steps = steps,
                lastRunDay = obj.optString("lastRunDay")
            )
        }
        return out
    }

    fun findRoutine(context: Context, name: String): Routine? =
        routines(context).firstOrNull {
            normalizeKey(it.name) == normalizeKey(name)
        }

    fun saveRoutine(context: Context, routine: Routine) {
        val kept = routines(context).filter {
            normalizeKey(it.name) != normalizeKey(routine.name)
        }
        writeRoutines(context, kept + routine)
        AppLog.i(
            "Brain",
            "routine saved: '${routine.name}' trigger=${routine.trigger} " +
                "steps=${routine.steps.size}"
        )
    }

    fun deleteRoutine(context: Context, name: String) {
        writeRoutines(
            context,
            routines(context).filter {
                normalizeKey(it.name) != normalizeKey(name)
            }
        )
    }

    fun markRoutineRan(context: Context, name: String, day: String) {
        writeRoutines(
            context,
            routines(context).map {
                if (normalizeKey(it.name) == normalizeKey(name)) {
                    it.copy(lastRunDay = day)
                } else {
                    it
                }
            }
        )
    }

    private fun writeRoutines(context: Context, list: List<Routine>) {
        val array = JSONArray()
        list.forEach { r ->
            array.put(
                JSONObject()
                    .put("name", r.name)
                    .put("trigger", r.trigger)
                    .put("steps", JSONArray(r.steps))
                    .put("lastRunDay", r.lastRunDay)
            )
        }
        writeArray(context, KEY_ROUTINES, array)
    }

    // ── Scheduled messages (#51) ─────────────────────────────────────

    fun scheduled(context: Context): List<ScheduledMessage> {
        val array = readArray(context, KEY_SCHEDULED)
        val out = mutableListOf<ScheduledMessage>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out += ScheduledMessage(
                id = obj.optLong("id"),
                contact = obj.optString("contact"),
                message = obj.optString("message"),
                fireAtMs = obj.optLong("fireAt"),
                due = obj.optBoolean("due")
            )
        }
        return out.sortedBy { it.fireAtMs }
    }

    fun getScheduled(context: Context, id: Long): ScheduledMessage? =
        scheduled(context).firstOrNull { it.id == id }

    fun addScheduled(
        context: Context,
        contact: String,
        message: String,
        fireAtMs: Long
    ): ScheduledMessage {
        val prefs = prefs(context)
        val id = prefs.getLong(KEY_SCHEDULED_SEQ, 0L) + 1L
        prefs.edit().putLong(KEY_SCHEDULED_SEQ, id).apply()
        val item = ScheduledMessage(id, contact, message, fireAtMs)
        writeScheduled(context, scheduled(context) + item)
        AppLog.i(
            "Brain",
            "scheduled message #$id to '$contact' at $fireAtMs"
        )
        return item
    }

    fun removeScheduled(context: Context, id: Long) {
        writeScheduled(context, scheduled(context).filter { it.id != id })
    }

    fun markScheduledDue(context: Context, id: Long) {
        writeScheduled(
            context,
            scheduled(context).map {
                if (it.id == id) it.copy(due = true) else it
            }
        )
    }

    private fun writeScheduled(context: Context, list: List<ScheduledMessage>) {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("contact", s.contact)
                    .put("message", s.message)
                    .put("fireAt", s.fireAtMs)
                    .put("due", s.due)
            )
        }
        writeArray(context, KEY_SCHEDULED, array)
    }

    // ── Notification reader opt-in (#86) ─────────────────────────────

    fun notifReaderEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_READER, false)

    fun setNotifReaderEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_READER, enabled).apply()
    }

    // ── Headless-run summary hand-off (routines) ─────────────────────
    // A wake/app-open routine finishes outside any chat surface; its
    // summary is parked here and the Chat screen surfaces it once as
    // an assistant message.

    fun setPendingSummary(context: Context, summary: String) {
        prefs(context).edit().putString(KEY_PENDING_SUMMARY, summary).apply()
    }

    fun takePendingSummary(context: Context): String? {
        val prefs = prefs(context)
        val value = prefs.getString(KEY_PENDING_SUMMARY, null)
        if (value != null) prefs.edit().remove(KEY_PENDING_SUMMARY).apply()
        return value
    }

    /** "2026-10-23" style day key for once-a-day routine triggers. */
    fun todayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}

/**
 * v2.0.0 (#16): records the tool calls of the CURRENT pipeline turn
 * (name + arguments of every successful execution) so a finished turn
 * can be saved as a macro. AgentPipeline arms it at dispatch start
 * and reads the snapshot when the turn ends; ToolExecutor feeds it.
 * Only successful executions are recorded — a macro must replay a
 * sequence that actually worked.
 */
object MacroRecorder {

    @Volatile
    private var armed = false

    private val steps = mutableListOf<BrainStore.RecordedStep>()

    fun begin() {
        synchronized(steps) {
            steps.clear()
            armed = true
        }
    }

    fun record(tool: String, argsJson: String) {
        if (!armed) return
        synchronized(steps) {
            steps += BrainStore.RecordedStep(tool, argsJson)
        }
    }

    fun snapshot(): List<BrainStore.RecordedStep> {
        synchronized(steps) {
            armed = false
            return steps.toList()
        }
    }

    fun disarm() {
        synchronized(steps) {
            armed = false
            steps.clear()
        }
    }
}

/**
 * v2.0.0 (#51): AlarmManager glue for scheduled messages. Uses
 * setAndAllowWhileIdle (inexact, no exact-alarm permission needed):
 * a scheduled message is a DRAFT reminder, not a life-safety alarm —
 * when it fires, the receiver posts a notification and the send
 * itself re-enters the gated flow in the app.
 */
object ScheduledAlarms {

    const val ACTION_FIRE = "com.nshd.jarvis2.SCHEDULED_MESSAGE"
    const val EXTRA_ID = "scheduled_id"

    fun schedule(context: Context, item: BrainStore.ScheduledMessage) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                item.fireAtMs,
                pendingIntent(context, item.id)
            )
        }
        AppLog.i("Brain", "alarm set for scheduled message #${item.id}")
    }

    fun cancel(context: Context, id: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: return
        runCatching { alarmManager.cancel(pendingIntent(context, id)) }
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, com.nshd.jarvis2.service.ScheduledMessageReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

package com.nshd.jarvis2.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * F5 — the full in-app log system the user asked for: every subsystem
 * (voice, Live socket, provider HTTP, agent, gates, hands, wake,
 * overlay, crashes) writes concise lines here, they persist to
 * `filesDir/logs/app.log` (rotated at ~1 MB, one backup kept), and the
 * Settings → "App Logs & Diagnostics" viewer can filter / search /
 * copy / share / save / clear them.
 *
 * Design notes:
 *  - In-memory ring buffer (capacity [CAPACITY]) mirrored into a
 *    [StateFlow] snapshot on every add. Log volume is human-scale
 *    (stage markers, HTTP calls, tool runs — never per audio frame),
 *    so a full snapshot copy per entry is cheap.
 *  - File appends run on one dedicated daemon thread, in call order.
 *  - EVERY message passes through [sanitize] first: Authorization
 *    headers, Bearer tokens, `key=` query values and x-goog-api-key
 *    values are replaced with `***`. Call sites must still never pass
 *    a raw API key — sanitize is the safety net, not the licence.
 *  - On-disk line format is tab-separated and escaped
 *    (`<epochMs>\t<level>\t<tag>\t<message>\t<stack?>`) so the tail of
 *    a previous session can be reloaded into memory at [init] and
 *    past crashes stay visible after a restart.
 */
object AppLog {

    enum class Level(val label: String) { D("D"), I("I"), W("W"), E("E") }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val stack: String? = null
    )

    private const val CAPACITY = 1500
    private const val MAX_FILE_BYTES = 1_048_576L // ~1 MB before rotation
    private const val LOAD_TAIL_LINES = 500
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "app.log"
    private const val BACKUP_FILE_NAME = "app.log.1"

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(CAPACITY)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var logDir: File? = null

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "applog-io").apply { isDaemon = true }
    }

    /**
     * Point AppLog at `filesDir/logs` and reload the tail of the
     * previous session's file into memory. Idempotent — safe from
     * MainActivity AND WakeWordService. Entries logged before init are
     * kept in memory (and persisted from init onward).
     */
    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val dir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
            runCatching { dir.mkdirs() }
            logDir = dir
            val loaded = runCatching { loadTail(dir) }.getOrDefault(emptyList())
            if (loaded.isNotEmpty()) {
                // Older in-memory entries (pre-init breadcrumbs) stay
                // after the reloaded tail — they happened later.
                val preInit = buffer.toList()
                buffer.clear()
                loaded.takeLast(CAPACITY).forEach { buffer.addLast(it) }
                preInit.forEach { addToBufferLocked(it) }
                _entries.value = buffer.toList()
            }
        }
        i("App", "AppLog initialised")
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun d(tag: String, msg: String) = add(Level.D, tag, msg, null)

    fun i(tag: String, msg: String) = add(Level.I, tag, msg, null)

    fun w(tag: String, msg: String) = add(Level.W, tag, msg, null)

    fun e(tag: String, msg: String) = add(Level.E, tag, msg, null)

    fun e(tag: String, msg: String, throwable: Throwable?) {
        // Exception messages may contain URLs, submitted text, or provider
        // payload fragments. Keep class names and stack frames, not messages.
        add(Level.E, tag, msg, throwable?.let(::formatThrowableForLog))
    }

    internal fun formatThrowableForLog(throwable: Throwable): String {
        val out = StringBuilder()
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && seen.add(current) && depth < 5) {
            if (depth == 0) {
                out.append(current.javaClass.name)
            } else {
                out.append("\nCaused by: ").append(current.javaClass.name)
            }
            current.stackTrace.take(20).forEach { frame ->
                out.append("\n    at ").append(frame.toString())
            }
            current = current.cause
            depth++
        }
        return out.toString()
    }

    /** Wipe memory + both log files. The separate crash report is NOT touched. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
        val dir = logDir ?: return
        io.execute {
            runCatching { File(dir, LOG_FILE_NAME).delete() }
            runCatching { File(dir, BACKUP_FILE_NAME).delete() }
        }
    }

    /** Combined size of app.log + app.log.1 (0 before init). File IO — call off Main. */
    fun totalFileSizeBytes(): Long {
        val dir = logDir ?: return 0L
        val main = runCatching { File(dir, LOG_FILE_NAME).length() }.getOrDefault(0L)
        val backup = runCatching { File(dir, BACKUP_FILE_NAME).length() }.getOrDefault(0L)
        return main + backup
    }

    /** One display line: `HH:mm:ss.SSS L/tag message` (+ stack lines when present). */
    fun formatLine(entry: Entry): String {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timeMs))
        val base = "$stamp ${entry.level.label}/${entry.tag} ${entry.message}"
        return if (entry.stack.isNullOrBlank()) {
            base
        } else {
            base + "\n" + entry.stack.trim()
        }
    }

    fun formatAll(list: List<Entry>): String = list.joinToString("\n") { formatLine(it) }

    // ── Redaction ──────────────────────────────────────────────────────

    /**
     * Strip credentials from a log message. Applied to every message
     * (and stack) on the way in — no exceptions.
     */
    fun sanitize(text: String): String {
        var out = text
        out = AUTH_HEADER_REGEX.replace(out) { m -> "${m.groupValues[1]}***" }
        out = BEARER_REGEX.replace(out) { m -> "${m.groupValues[1]}***" }
        out = KEY_PARAM_REGEX.replace(out) { m -> "${m.groupValues[1]}***" }
        out = GOOG_KEY_REGEX.replace(out) { m -> "${m.groupValues[1]}***" }
        out = API_KEY_FIELD_REGEX.replace(out) { m -> "${m.groupValues[1]}***" }
        return out
    }

    private val AUTH_HEADER_REGEX = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?\\S+")
    private val BEARER_REGEX = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val KEY_PARAM_REGEX = Regex("([?&]key=)[^&\\s\"']+")
    private val GOOG_KEY_REGEX = Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)\\S+")
    private val API_KEY_FIELD_REGEX = Regex("(?i)(api[-_ ]?key\\s*[:=]\\s*)\\S+")

    // ── Internals ──────────────────────────────────────────────────────

    private fun add(level: Level, tag: String, msg: String, stack: String?) {
        val entry = Entry(
            timeMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = sanitize(msg),
            stack = stack?.let { sanitize(it) }
        )
        synchronized(lock) { addToBufferLocked(entry) }
        val dir = logDir
        if (dir != null) {
            io.execute { runCatching { appendLine(dir, entry) } }
        }
    }

    private fun addToBufferLocked(entry: Entry) {
        buffer.addLast(entry)
        while (buffer.size > CAPACITY) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    private fun appendLine(dir: File, entry: Entry) {
        val file = File(dir, LOG_FILE_NAME)
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            val backup = File(dir, BACKUP_FILE_NAME)
            runCatching { backup.delete() }
            runCatching { file.renameTo(backup) }
        }
        file.appendText(serialize(entry) + "\n")
    }

    private fun serialize(entry: Entry): String = buildString {
        append(entry.timeMs)
        append('\t')
        append(entry.level.label)
        append('\t')
        append(escape(entry.tag))
        append('\t')
        append(escape(entry.message))
        append('\t')
        append(escape(entry.stack.orEmpty()))
    }

    private fun parse(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val timeMs = parts[0].toLongOrNull() ?: return null
        val level = when (parts[1]) {
            "D" -> Level.D
            "I" -> Level.I
            "W" -> Level.W
            "E" -> Level.E
            else -> return null
        }
        return Entry(
            timeMs = timeMs,
            level = level,
            tag = unescape(parts[2]),
            message = unescape(parts[3]),
            stack = unescape(parts.getOrElse(4) { "" }).ifBlank { null }
        )
    }

    private fun loadTail(dir: File): List<Entry> {
        val lines = mutableListOf<String>()
        val backup = File(dir, BACKUP_FILE_NAME)
        if (backup.exists()) lines += backup.readLines()
        val main = File(dir, LOG_FILE_NAME)
        if (main.exists()) lines += main.readLines()
        return lines.takeLast(LOAD_TAIL_LINES).mapNotNull { parse(it) }
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", " ")

    /** Single-pass inverse of [escape] (chained replaces would misread `\\n`). */
    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

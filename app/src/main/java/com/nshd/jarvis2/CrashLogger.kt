package com.nshd.jarvis2

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * F4.1 crash capture + session diagnostics.
 *
 * Two jobs:
 *  1. **Crash capture**: [install] registers a default uncaught-exception
 *     handler that synchronously writes the full stack trace to
 *     `filesDir/last_crash.txt`, raises the `crash_pending` flag, and
 *     then delegates to the previous handler so the process dies exactly
 *     as it would have anyway. On the next launch the app shows the
 *     trace on a dedicated screen (see JarvisApp) — the user can
 *     screenshot / copy / share it instead of the crash vanishing.
 *  2. **Session ring log**: [logNonFatal] and [stage] append timestamped
 *     lines to `filesDir/session_log.txt` (last [MAX_LOG_LINES] lines
 *     kept). Stage markers trace the voice connect sequence step by
 *     step, so even a *silent* death (native abort, OEM kill) leaves a
 *     "last thing that happened" breadcrumb, and a soft failure's
 *     Details expander can show exactly how far connect got.
 *
 * Everything here is best-effort and internally `runCatching`-guarded:
 * the diagnostics layer must never become a crash source itself. All
 * file IO is tiny (one small file) and synchronised on [logLock].
 */
object CrashLogger {

    private const val PREFS_NAME = "jarvis_diagnostics"
    private const val KEY_CRASH_PENDING = "crash_pending"
    // Plan #102 (safe mode): consecutive crashes within BOOT_WINDOW_MS of
    // a boot; at SAFE_MODE_THRESHOLD the next boot starts in safe mode.
    private const val KEY_BOOT_STARTED_AT = "boot_started_at"
    private const val KEY_BOOT_CRASH_STREAK = "boot_crash_streak"
    private const val KEY_SAFE_MODE = "safe_mode"
    private const val BOOT_WINDOW_MS = 20_000L
    private const val SAFE_MODE_THRESHOLD = 3
    // Plan #98: last visible screen ("TAB:SUB") for crash relaunch restore.
    private const val KEY_LAST_SCREEN = "last_screen"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val LOG_FILE_NAME = "session_log.txt"
    private const val MAX_LOG_LINES = 200

    private val installed = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val logLock = Any()

    /**
     * Install the uncaught-exception handler. Idempotent — safe to call
     * from MainActivity AND WakeWordService (whichever starts first in
     * the process wins; later calls are no-ops).
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Synchronous, tiny, and guarded: this runs while the
            // process is already dying, so nothing here may throw.
            runCatching { writeCrashReport(thread, throwable) }
            val delegate = previousHandler
            if (delegate != null) {
                delegate.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        stage("app", "crash logger installed")
    }

    // ── Crash report ───────────────────────────────────────────────────

    private fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val ctx = appContext ?: return
        val trace = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()
        val report = buildString {
            appendLine("Jarvis crash report")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("App version: ${appVersion(ctx)}")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message ?: "(no message)"}")
            appendLine("---")
            append(trace)
        }
        File(ctx.filesDir, CRASH_FILE_NAME).writeText(report)
        // F5: mirror the crash into AppLog too (best-effort — the
        // process is dying; last_crash.txt above stays the reliable
        // record, AppLog's async writer may not flush in time).
        runCatching {
            com.nshd.jarvis2.core.AppLog.e(
                "Crash",
                "UNCAUGHT on thread ${thread.name}: ${throwable.javaClass.name}"
            )
        }
        // commit(), not apply(): the process is dying right now.
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit().putBoolean(KEY_CRASH_PENDING, true)
        // Plan #102: a crash inside the boot window counts toward the
        // safe-mode streak; three in a row arm safe mode for next boot.
        val bootAt = prefs.getLong(KEY_BOOT_STARTED_AT, 0L)
        if (bootAt > 0L && System.currentTimeMillis() - bootAt < BOOT_WINDOW_MS) {
            val streak = prefs.getInt(KEY_BOOT_CRASH_STREAK, 0) + 1
            edit.putInt(KEY_BOOT_CRASH_STREAK, streak)
            if (streak >= SAFE_MODE_THRESHOLD) {
                edit.putBoolean(KEY_SAFE_MODE, true)
            }
        }
        edit.commit()
    }

    // ── Safe mode + boot streak (Plan #102) ────────────────────────────

    /** MainActivity calls this first thing in onCreate. */
    fun markBootStart(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_BOOT_STARTED_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /** The app survived the boot window: reset the crash streak. */
    fun markBootSettled(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BOOT_CRASH_STREAK, 0)
                .apply()
        }
    }

    fun isSafeMode(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_MODE, false)
    }.getOrDefault(false)

    /** Leaving safe mode also clears the streak that armed it. */
    fun setSafeMode(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SAFE_MODE, enabled)
                .putInt(KEY_BOOT_CRASH_STREAK, 0)
                .apply()
        }
    }

    // ── Last-screen restore (Plan #98) ─────────────────────────────────

    fun saveLastScreen(context: Context, screen: String) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SCREEN, screen)
                .apply()
        }
    }

    fun lastScreen(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SCREEN, null)
    }.getOrNull()

    private fun appVersion(ctx: Context): String = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("unknown")

    /** True when an unviewed crash report is waiting. Cheap prefs read. */
    fun hasPendingCrash(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASH_PENDING, false)
    }.getOrDefault(false)

    /** Full crash report text for the report screen; never throws. */
    fun readCrashReport(context: Context): String = runCatching {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (file.exists()) {
            file.readText().takeIf { it.isNotBlank() }
                ?: "The crash report file was empty."
        } else {
            "No crash report file was found."
        }
    }.getOrDefault("The crash report could not be read.")

    /** User dismissed the report: delete the file + clear the flag. */
    fun clearCrash(context: Context) {
        runCatching { File(context.filesDir, CRASH_FILE_NAME).delete() }
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CRASH_PENDING, false)
                .apply()
        }
    }

    // ── Session ring log ───────────────────────────────────────────────

    /** Non-fatal error breadcrumb (one summary line + a few trace lines). */
    fun logNonFatal(tag: String, throwable: Throwable) {
        val summary = throwable.javaClass.simpleName
        val traceLines = throwable.stackTrace
            .take(10)
            .map { "    at $it" }
        appendLines(listOf("E/$tag $summary") + traceLines)
        // Exception messages and chained-cause payloads are omitted from logs.
        runCatching {
            com.nshd.jarvis2.core.AppLog.e("Crash", "[$tag] $summary")
        }
    }

    /** Non-fatal message breadcrumb. */
    fun logNonFatal(tag: String, message: String) {
        appendLines(listOf("E/$tag $message"))
        runCatching {
            com.nshd.jarvis2.core.AppLog.e("Crash", "[$tag] $message")
        }
    }

    /** Connect-sequence stage marker — the "how far did it get" trail. */
    fun stage(tag: String, message: String) {
        appendLines(listOf("S/$tag $message"))
        // F5: F4.1 stage markers are ALSO AppLog lines, under the
        // viewer's tag names — the whole connect trail is visible in
        // Settings → App Logs without opening this legacy ring log.
        runCatching {
            com.nshd.jarvis2.core.AppLog.d(appLogTag(tag), message)
        }
    }

    /** Legacy stage tag ("voice"/"live"/"wake"/…) → AppLog viewer tag. */
    private fun appLogTag(tag: String): String = when (tag.lowercase()) {
        "voice", "universal" -> "Voice"
        "live" -> "Live"
        "wake" -> "Wake"
        "app" -> "App"
        "overlay" -> "Overlay"
        else -> tag.replaceFirstChar { it.uppercase() }
    }

    private fun appendLines(newLines: List<String>) {
        val ctx = appContext ?: return
        synchronized(logLock) {
            runCatching {
                val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val file = File(ctx.filesDir, LOG_FILE_NAME)
                val existing = if (file.exists()) {
                    file.readLines().takeLast(MAX_LOG_LINES)
                } else {
                    emptyList()
                }
                val stamped = newLines.map { "$stamp $it" }
                file.writeText((existing + stamped).takeLast(MAX_LOG_LINES).joinToString("\n"))
            }
        }
    }

    /** Last [count] session-log lines, oldest first. Never throws. */
    fun sessionLogTail(context: Context, count: Int): List<String> = runCatching {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (file.exists()) file.readLines().takeLast(count) else emptyList()
    }.getOrDefault(emptyList())

    /** Whole session log as one string (for copy / diagnostics UI). */
    fun sessionLogText(context: Context): String = runCatching {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (file.exists()) file.readText() else ""
    }.getOrDefault("")
}

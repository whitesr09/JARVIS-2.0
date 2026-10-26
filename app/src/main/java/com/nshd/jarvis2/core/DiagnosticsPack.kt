package com.nshd.jarvis2.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.nshd.jarvis2.CrashLogger
import com.nshd.jarvis2.brain.ProbeStatus
import com.nshd.jarvis2.brain.ProviderStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F8 (#97): one-tap, privacy-reduced diagnostics pack. It contains
 * app/device metadata, a limited settings/provider summary, crash metadata
 * (not exception messages or stacks), and selected low-risk log events.
 *
 * API keys are never read here. High-risk action/provider/clipboard/memory
 * logs and stack traces are excluded by default; common email/phone patterns
 * are masked and [AppLog.sanitize] is applied to the final text. This is
 * risk reduction, not a guarantee that every possible personal value is
 * detected, so the UI asks users to review the pack before sharing.
 */
object DiagnosticsPack {

    private val SAFE_LOG_TAGS = setOf("App", "Voice", "Live", "Wake", "Overlay")
    private val EMAIL_REGEX = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val PHONE_REGEX = Regex("(?<!\\w)(?:\\+\\d{1,3}[ .-]?)?\\d{10,13}(?!\\w)")

    /** Normalize only known low-risk event messages; discard raw errors and arbitrary text. */
    internal fun safeDiagnosticEvent(tag: String, message: String): String? = when (tag) {
        "App" -> when {
            message.startsWith("Cold start setup took ") -> "Cold-start setup completed"
            else -> null
        }
        "Voice" -> when {
            message.startsWith("session start engine=") -> "Voice session started"
            message.startsWith("engine decision:") -> "Voice engine selection started"
            message.startsWith("stop reading requested") -> "Stop-reading requested"
            message.startsWith("call auto-resume unavailable") -> "Phone-call monitoring unavailable"
            message == "call auto-resume watch registered" -> "Phone-call monitoring registered"
            message.startsWith("phone call active") -> "Voice paused for phone call"
            message.startsWith("call ended — resuming session") -> "Voice resumed after phone call"
            else -> null
        }
        "Live" -> when {
            message.startsWith("setup timeout after ") -> "Live setup timed out"
            message.startsWith("WS open — setup sent") -> "Live WebSocket opened"
            message.startsWith("first audio frame") -> "Live audio frame received"
            message.startsWith("tool call: ") -> {
                val tool = message.substringAfter("tool call: ").trim()
                if (tool.matches(Regex("[A-Za-z0-9_]{1,64}"))) "Live tool call: $tool"
                else "Live tool call observed"
            }
            message.startsWith("model fallback chain exhausted") -> "Live model fallback exhausted"
            message.startsWith("setup took ") -> "Live setup completed"
            else -> null
        }
        "Wake" -> when {
            message == "wake phrase detected" -> "Wake phrase detected"
            message.startsWith("wake phrase below sensitivity threshold") -> "Wake phrase below sensitivity threshold"
            message.startsWith("paused — battery at ") -> "Wake-word paused for low battery"
            message.startsWith("battery pause lifted ") -> "Wake-word resumed after battery pause"
            else -> null
        }
        "Overlay" -> when {
            message.startsWith("task panel shown ") -> "Task panel shown"
            message == "tap — toggle voice session" -> "Voice toggle tapped"
            else -> null
        }
        else -> null
    }

    internal fun redactCommonContactPatterns(text: String): String =
        PHONE_REGEX.replace(
            EMAIL_REGEX.replace(text, "[email redacted]"),
            "[phone redacted]"
        )

    /** Build only allowlisted and normalized diagnostic events. */
    internal fun safeLogText(entries: List<AppLog.Entry>): String {
        val selected = entries.mapNotNull { entry ->
            if (entry.tag !in SAFE_LOG_TAGS || !entry.stack.isNullOrBlank()) return@mapNotNull null
            val normalized = safeDiagnosticEvent(entry.tag, entry.message) ?: return@mapNotNull null
            entry.copy(message = redactCommonContactPatterns(normalized), stack = null)
        }
        return AppLog.formatAll(selected)
    }

    /** Keep crash time/version/type, but never export the exception message or stack by default. */
    internal fun summarizeCrashReport(report: String): String {
        if (report.isBlank() || report.contains("No crash report file was found")) return "(none)"
        val lines = report.lineSequence().takeWhile { it != "---" }.toList()
        val time = lines.firstOrNull { it.startsWith("Time: ") }?.removePrefix("Time: ")
        val version = lines.firstOrNull { it.startsWith("App version: ") }?.removePrefix("App version: ")
        val exception = lines.firstOrNull { it.startsWith("Exception: ") }
            ?.removePrefix("Exception: ")
            ?.substringBefore(":")
            ?.trim()
        return buildString {
            appendLine("Crash metadata only; exception message and stack trace omitted.")
            if (time != null) appendLine("Time: $time")
            if (version != null) appendLine("App version: $version")
            if (!exception.isNullOrBlank()) appendLine("Exception type: $exception")
        }.trim()
    }

    /** Build the pack text. File/metadata reads only — call off Main. */
    fun buildText(context: Context): String {
        val appContext = context.applicationContext
        val version = runCatching {
            val info = appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("unknown")
        val stamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.US
        ).format(Date())
        return buildString {
            appendLine("JARVIS diagnostics pack (redacted)")
            appendLine("Generated: $stamp")
            appendLine("Privacy: raw action, clipboard, memory, API-response, and crash-stack logs are omitted; common phone/email patterns are masked. Review before sharing.")
            appendLine("App version: $version")
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                    "(Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
            )
            appendLine()
            appendLine("== Settings summary ==")
            appendLine(settingsSummary(appContext))
            appendLine()
            appendLine("== Providers (no keys) ==")
            appendLine(providersSummary(appContext))
            appendLine()
            appendLine("== Last crash metadata ==")
            val crashFile = File(appContext.filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                appendLine(summarizeCrashReport(CrashLogger.readCrashReport(appContext)))
            } else {
                appendLine("(none)")
            }
            appendLine()
            appendLine("== Selected redacted diagnostic events (last 500 entries scanned) ==")
            append(safeLogText(AppLog.entries.value.takeLast(500)))
        }.let { AppLog.sanitize(it) }
    }

    /** Engine / persona / language / theme / live model — no secrets exist among these. */
    private fun settingsSummary(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val engine = prefs.getString("voice_engine", "AUTO") ?: "AUTO"
        val persona = prefs.getString("persona", "Jarvis Pro") ?: "Jarvis Pro"
        val language = prefs.getString("language", "Auto") ?: "Auto"
        val theme = prefs.getString("theme", "DARK") ?: "DARK"
        val liveModel = prefs.getString(
            "live_model", "gemini-2.5-flash-native-audio-preview-12-2025"
        ) ?: "gemini-2.5-flash-native-audio-preview-12-2025"
        val voice = prefs.getString("voice_name", "Kore") ?: "Kore"
        val wakeOn = prefs.getBoolean("wake_always_on", false)
        val silent = prefs.getBoolean("silent_execution", false)
        return "Engine: $engine • Persona: $persona • Language: $language • " +
            "Theme: $theme\nLive model: $liveModel • Live voice: $voice • " +
            "Wake word: ${if (wakeOn) "on" else "off"} • " +
            "Silent execution: ${if (silent) "on" else "off"}"
    }

    /** Provider ids + configured model names + cached probe outcomes. */
    private fun providersSummary(context: Context): String {
        val store = ProviderStore(context)
        val configs = store.orderedConfigs()
        if (configs.isEmpty()) return "(no providers)"
        return configs.joinToString("\n") { config ->
            val probes = store.cachedProbeResults(config.id)
            val probeText = if (probes.isEmpty()) {
                "never probed"
            } else {
                probes.joinToString("; ") { probe ->
                    val latency = if (probe.status == ProbeStatus.WORKING) {
                        " ${probe.latencyMs}ms"
                    } else {
                        ""
                    }
                    "${probe.modelId}=${probe.status}$latency"
                }
            }
            "- ${config.id} (${config.label}): " +
                "configured=${config.isConfigured} " +
                "chat=${config.chatModel.ifBlank { "(none)" }} " +
                "reasoning=${config.reasoningModel.ifBlank { "(none)" }}\n" +
                "  probes: $probeText"
        }
    }

    /**
     * Write the pack to the share cache and open the share chooser
     * (the same FileProvider flow the log viewer uses). Returns false
     * when the share could not be started. Logs the export (tag "App").
     */
    fun share(context: Context): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            val text = buildText(appContext)
            val file = File(appContext.cacheDir, "jarvis-diagnostics.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Jarvis diagnostics pack")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(
                Intent.createChooser(intent, "Share diagnostics pack")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            AppLog.i(
                "App",
                "redacted diagnostics pack created (${text.lines().size} lines)"
            )
            true
        }.getOrElse { error ->
            AppLog.e("App", "diagnostics pack share failed (${error.javaClass.simpleName})")
            false
        }
    }
}

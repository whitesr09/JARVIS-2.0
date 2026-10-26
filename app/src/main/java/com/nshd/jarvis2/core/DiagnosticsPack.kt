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
 * F8 (#97): one-tap diagnostics pack — a single .txt a user can share
 * when something breaks: app + device identity, the last crash report
 * (when one exists), the last 500 AppLog lines, provider ids + model
 * names + probe latencies, and a settings summary.
 *
 * NO SECRETS, by construction: API keys are never read here (only
 * provider ids, model names and probe metadata), and the whole text
 * still passes through [AppLog.sanitize] before it leaves.
 */
object DiagnosticsPack {

    private val SAFE_LOG_TAGS = setOf("App", "Voice", "Live", "Wake", "Overlay")
    private val EMAIL_REGEX = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val PHONE_REGEX = Regex("(?<!\\w)(?:\\+\\d{1,3}[ .-]?)?\\d{10,13}(?!\\w)")

    /** Build only selected low-risk diagnostic lines, redacting common contact patterns. */
    internal fun safeLogText(entries: List<AppLog.Entry>): String {
        val selected = entries.filter {
            it.tag in SAFE_LOG_TAGS && it.stack.isNullOrBlank()
        }
        return AppLog.formatAll(selected.map { entry ->
            val masked = PHONE_REGEX.replace(
                EMAIL_REGEX.replace(entry.message, "[email redacted]"),
                "[phone redacted]"
            )
            entry.copy(message = masked, stack = null)
        })
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

package com.nshd.jarvis2.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPackTest {

    @Test
    fun safeLogTextOmitsActionLogsStacksAndMasksContactPatterns() {
        val entries = listOf(
            AppLog.Entry(1L, AppLog.Level.I, "Voice", "session connected to +919876543210"),
            AppLog.Entry(2L, AppLog.Level.I, "Voice", "provider contact is user@example.com"),
            AppLog.Entry(3L, AppLog.Level.I, "Hands", "screen content from a private chat"),
            AppLog.Entry(4L, AppLog.Level.I, "Agent", "contact alias A -> B"),
            AppLog.Entry(5L, AppLog.Level.E, "Voice", "failed", "stack with private details"),
            AppLog.Entry(6L, AppLog.Level.I, "Live", "setup complete")
        )

        val safe = DiagnosticsPack.safeLogText(entries)

        assertTrue(safe.contains("[phone redacted]"))
        assertTrue(safe.contains("[email redacted]"))
        assertTrue(safe.contains("setup complete"))
        assertFalse(safe.contains("+919876543210"))
        assertFalse(safe.contains("user@example.com"))
        assertFalse(safe.contains("private chat"))
        assertFalse(safe.contains("alias A"))
        assertFalse(safe.contains("stack with private details"))
    }

    @Test
    fun crashSummaryExcludesExceptionMessageAndStack() {
        val report = """
            Jarvis crash report
            Time: 2026-10-26 10:30:00
            App version: 2.0.3 (26)
            Thread: main
            Exception: java.lang.IllegalStateException: private message content
            ---
            java.lang.IllegalStateException: private message content
                at com.example.App.run(App.kt:12)
        """.trimIndent()

        val summary = DiagnosticsPack.summarizeCrashReport(report)

        assertTrue(summary.contains("java.lang.IllegalStateException"))
        assertTrue(summary.contains("2026-10-26 10:30:00"))
        assertFalse(summary.contains("private message content"))
        assertFalse(summary.contains("App.kt:12"))
    }
}

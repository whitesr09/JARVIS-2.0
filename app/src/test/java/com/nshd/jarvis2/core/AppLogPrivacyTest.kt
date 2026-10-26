package com.nshd.jarvis2.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogPrivacyTest {
    @Test
    fun persistentThrowableFormatOmitsExceptionAndCauseMessages() {
        val cause = IllegalArgumentException("private endpoint https://api.example/key=secret")
        cause.stackTrace = arrayOf(StackTraceElement("com.example.Provider", "chat", "Provider.kt", 44))
        val error = IllegalStateException("private query +919876543210", cause)
        error.stackTrace = arrayOf(StackTraceElement("com.example.Voice", "start", "Voice.kt", 21))

        val formatted = AppLog.formatThrowableForLog(error)

        assertTrue(formatted.contains("java.lang.IllegalStateException"))
        assertTrue(formatted.contains("com.example.Voice.start"))
        assertTrue(formatted.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(formatted.contains("com.example.Provider.chat"))
        assertFalse(formatted.contains("private query"))
        assertFalse(formatted.contains("+919876543210"))
        assertFalse(formatted.contains("private endpoint"))
        assertFalse(formatted.contains("secret"))
    }
}

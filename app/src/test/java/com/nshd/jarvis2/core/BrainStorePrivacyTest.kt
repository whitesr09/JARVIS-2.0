package com.nshd.jarvis2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainStorePrivacyTest {

    @Test
    fun goalCountStorageKeyIsStableForNormalizedCommands() {
        assertEquals(
            BrainStore.goalCountStorageKey("Send Email"),
            BrainStore.goalCountStorageKey("  send   email!! ")
        )
    }

    @Test
    fun goalCountStorageKeyDoesNotPersistTheRawCommand() {
        val privatePrompt = "send my password hunter2 to my email"
        val key = BrainStore.goalCountStorageKey(privatePrompt)

        assertEquals(64, key.length)
        assertTrue(key.matches(Regex("^[0-9a-f]{64}$")))
        assertFalse(key.contains("password"))
        assertFalse(key.contains("hunter2"))
        assertNotEquals(BrainStore.normalizeKey(privatePrompt), key)
    }
}

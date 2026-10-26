package com.nshd.jarvis2.hands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppRecipientGuardTest {

    @Test
    fun acceptsExactApprovedContactTitleWithFormattingDifferences() {
        assertTrue(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "Ahammed Nishad",
                approvedName = "Ahammed Nishad",
                approvedDigits = "919876543210"
            )
        )
        assertTrue(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "Ahammed-Nishad",
                approvedName = "Ahammed Nishad",
                approvedDigits = "919876543210"
            )
        )
    }

    @Test
    fun acceptsMatchingVisiblePhoneForUnsavedContact() {
        assertTrue(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "+91 98765 43210",
                approvedName = "Saved contact name",
                approvedDigits = "919876543210"
            )
        )
    }

    @Test
    fun rejectsDifferentContactAndDifferentPhone() {
        assertFalse(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "Another Contact",
                approvedName = "Ahammed Nishad",
                approvedDigits = "919876543210"
            )
        )
        assertFalse(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "+91 98765 43211",
                approvedName = "Ahammed Nishad",
                approvedDigits = "919876543210"
            )
        )
    }

    @Test
    fun rejectsSameTrailingTenDigitsWithDifferentInternationalPrefix() {
        assertFalse(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "+44 1234 9876543210",
                approvedName = "Ahammed Nishad",
                approvedDigits = "919876543210"
            )
        )
        assertFalse(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "76543210",
                approvedName = "Ahammed Nishad",
                approvedDigits = "919876543210"
            )
        )
    }

    @Test
    fun acceptsLocalTenDigitTitleForInternationalApprovedNumber() {
        assertTrue(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "9876543210",
                approvedName = "Saved contact name",
                approvedDigits = "919876543210"
            )
        )
    }

    @Test
    fun rejectsAmbiguousShortNumber() {
        assertFalse(
            WhatsAppRecipientGuard.matchesApprovedChat(
                visibleTitle = "43210",
                approvedName = "Saved contact name",
                approvedDigits = "919876543210"
            )
        )
    }
}

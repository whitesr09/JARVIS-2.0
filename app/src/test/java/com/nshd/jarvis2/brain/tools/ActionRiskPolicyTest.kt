package com.nshd.jarvis2.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRiskPolicyTest {
    @Test
    fun classifiesCommunicationAndHighImpactActions() {
        assertEquals(ActionRisk.COMMUNICATION_SEND, ActionRiskPolicy.classify("whatsapp_message"))
        assertEquals(ActionRisk.COMMUNICATION_SEND, ActionRiskPolicy.classify("gmail_send"))
        assertEquals(ActionRisk.HIGH_IMPACT, ActionRiskPolicy.classify("call_contact"))
        assertEquals(ActionRisk.HIGH_IMPACT, ActionRiskPolicy.classify("calendar_event"))
    }

    @Test
    fun knownReadOnlyToolsRemainRetryable() {
        assertEquals(ActionRisk.READ_ONLY, ActionRiskPolicy.classify("get_screen"))
        assertFalse(ActionRiskPolicy.shouldTreatFailureAsOutcomeUnknown("get_screen"))
    }

    @Test
    fun unknownToolsFailClosedForOutcomeHandling() {
        assertEquals(ActionRisk.UNKNOWN, ActionRiskPolicy.classify("future_unclassified_tool"))
        assertTrue(ActionRiskPolicy.shouldTreatFailureAsOutcomeUnknown("future_unclassified_tool"))
    }

    @Test
    fun mutationAndExternalSideEffectsAreNotTreatedAsReadOnly() {
        assertTrue(ActionRiskPolicy.mayHaveSideEffect("type_text"))
        assertTrue(ActionRiskPolicy.mayHaveSideEffect("set_alarm"))
        assertTrue(ActionRiskPolicy.shouldTreatFailureAsOutcomeUnknown("whatsapp_group_message"))
    }
}

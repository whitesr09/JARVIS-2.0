package com.nshd.jarvis2.brain.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRegistryValidationTest {

    @Test
    fun acceptsValidRequiredArguments() {
        assertNull(
            ToolRegistry.validateArguments(
                "click_text",
                JSONObject().put("text", "Settings")
            )
        )
    }

    @Test
    fun rejectsMissingRequiredArguments() {
        assertEquals(
            "Missing required argument: text",
            ToolRegistry.validateArguments("click_text", JSONObject())
        )
    }

    @Test
    fun rejectsUnknownToolsAndArguments() {
        assertEquals(
            "Unknown tool: invented_action",
            ToolRegistry.validateArguments("invented_action", JSONObject())
        )
        assertEquals(
            "Unexpected argument: extra",
            ToolRegistry.validateArguments(
                "click_text",
                JSONObject().put("text", "Settings").put("extra", true)
            )
        )
    }

    @Test
    fun rejectsInvalidEnumValues() {
        assertEquals(
            "Argument 'direction' has an unsupported value",
            ToolRegistry.validateArguments(
                "scroll",
                JSONObject().put("direction", "diagonal")
            )
        )
    }

    @Test
    fun rejectsWrongTypesAndOversizedPayloads() {
        assertEquals(
            "Argument 'element_id' must be integer",
            ToolRegistry.validateArguments(
                "click_element",
                JSONObject().put("element_id", "not-an-index")
            )
        )
        assertEquals(
            "Tool arguments are too large",
            ToolRegistry.validateArguments(
                "type_text",
                JSONObject().put("text", "x".repeat(33_000))
            )
        )
    }
}

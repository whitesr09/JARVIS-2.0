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
    fun rejectsOutOfRangeDeviceActionValues() {
        assertEquals(
            "Argument 'x' must be between 0 and 100",
            ToolRegistry.validateArguments(
                "tap_percent",
                JSONObject().put("x", 101).put("y", 40)
            )
        )
        assertEquals(
            "Argument 'percent' must be between 0 and 100",
            ToolRegistry.validateArguments(
                "set_volume",
                JSONObject().put("percent", -1)
            )
        )
        assertEquals(
            "Argument 'hour' must be between 0 and 23",
            ToolRegistry.validateArguments(
                "set_alarm",
                JSONObject().put("hour", 24).put("minute", 30)
            )
        )
    }

    @Test
    fun requiresCountForDeleteLastChars() {
        assertEquals(
            "Argument 'count' is required for delete_last_chars",
            ToolRegistry.validateArguments(
                "edit_text",
                JSONObject().put("action", "delete_last_chars")
            )
        )
        assertEquals(
            "Argument 'count' must be between 1 and 16384",
            ToolRegistry.validateArguments(
                "edit_text",
                JSONObject().put("action", "delete_last_chars").put("count", 0)
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

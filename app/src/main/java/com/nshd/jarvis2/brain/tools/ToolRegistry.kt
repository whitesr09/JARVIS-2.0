package com.nshd.jarvis2.brain.tools

import org.json.JSONArray
import org.json.JSONObject

data class ToolSpec(
    val name: String,
    val description: String,
    val paramsJsonSchema: JSONObject
)

/** Canonical Level 4 tool definitions shared by REST and Gemini Live. */
object ToolRegistry {

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "inspect_screen",
            "Read the current screen as a numbered accessibility element list. Call this before tapping when the screen state is not already known.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "open_app",
            "Open an installed app by common name (for example WhatsApp, YouTube, Instagram, Chrome, Spotify, Telegram, Gmail, Camera, Settings, Maps or Play Store).",
            schema(
                properties = JSONObject().put("app", stringProperty("App name to open")),
                required = listOf("app")
            )
        ),
        ToolSpec(
            "click_element",
            "Click an element by its integer index from the most recent inspect_screen result.",
            schema(
                properties = JSONObject().put("element_id", integerProperty("Element index from inspect_screen")),
                required = listOf("element_id")
            )
        ),
        ToolSpec(
            "click_text",
            "Click a visible element matching the supplied text or content description.",
            schema(
                properties = JSONObject().put("text", stringProperty("Visible text or description to click")),
                required = listOf("text")
            )
        ),
        ToolSpec(
            "type_text",
            "Type text into the currently focused editable field, or the first editable field on screen. Entry is verified by reading the field back.",
            schema(
                properties = JSONObject().put("text", stringProperty("Exact text to enter")),
                required = listOf("text")
            )
        ),
        ToolSpec(
            "scroll",
            "Scroll the current screen in one direction and report whether the screen changed.",
            schema(
                properties = JSONObject().put(
                    "direction",
                    stringProperty("Scroll direction", listOf("up", "down", "left", "right"))
                ),
                required = listOf("direction")
            )
        ),
        ToolSpec(
            "press_key",
            "Press an Android system key: back, home, recents, notifications or quickSettings.",
            schema(
                properties = JSONObject().put(
                    "key",
                    stringProperty(
                        "System key",
                        listOf("back", "home", "recents", "notifications", "quickSettings")
                    )
                ),
                required = listOf("key")
            )
        ),
        ToolSpec(
            "tap_percent",
            "Tap explicit screen coordinates as percentages (0-100). Use only when accessibility targeting cannot identify the target.",
            schema(
                properties = JSONObject()
                    .put("x", numberProperty("Horizontal position as a percentage"))
                    .put("y", numberProperty("Vertical position as a percentage")),
                required = listOf("x", "y")
            )
        ),
        ToolSpec(
            "whatsapp_message",
            "Send a WhatsApp message to a contact name or phone number. A uniquely resolved contact sends directly; multiple matches return candidates and do not send.",
            schema(
                properties = JSONObject()
                    .put("contact", stringProperty("Contact name, saved alias or phone number"))
                    .put("message", stringProperty("Message text")),
                required = listOf("contact", "message")
            )
        ),
        ToolSpec(
            "youtube_play",
            "Search YouTube and play the first plausible video result for the query.",
            schema(
                properties = JSONObject().put("query", stringProperty("YouTube search query")),
                required = listOf("query")
            )
        ),
        ToolSpec(
            "set_volume",
            "Set media volume as a percentage from 0 to 100.",
            schema(
                properties = JSONObject().put("percent", integerProperty("Volume percentage, 0-100")),
                required = listOf("percent")
            )
        ),
        ToolSpec(
            "toggle_torch",
            "Turn the camera torch on or off.",
            schema(
                properties = JSONObject().put("on", booleanProperty("True for on, false for off")),
                required = listOf("on")
            )
        ),
        ToolSpec(
            "set_brightness",
            "Set system brightness as a percentage from 0 to 100. Requires Modify System Settings permission.",
            schema(
                properties = JSONObject().put("percent", integerProperty("Brightness percentage, 0-100")),
                required = listOf("percent")
            )
        ),
        ToolSpec(
            "call_contact",
            "Call a contact by name, saved alias or phone number. Calls to numbers not present in contacts require native user confirmation.",
            schema(
                properties = JSONObject().put("contact", stringProperty("Contact name, saved alias or phone number")),
                required = listOf("contact")
            )
        )
    )

    fun spec(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    /** OpenAI-compatible `tools` array. */
    fun openAiTools(): JSONArray {
        val array = JSONArray()
        specs.forEach { spec ->
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", JSONObject(spec.paramsJsonSchema.toString()))
                    )
            )
        }
        return array
    }

    /** Gemini Live `functionDeclarations` array (same schemas as REST). */
    fun liveFunctionDeclarations(): JSONArray {
        val array = JSONArray()
        specs.forEach { spec ->
            array.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", JSONObject(spec.paramsJsonSchema.toString()))
            )
        }
        return array
    }

    private fun schema(properties: JSONObject, required: List<String> = emptyList()): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        }
        return schema
    }

    private fun stringProperty(description: String, enumValues: List<String>? = null): JSONObject {
        val property = JSONObject()
            .put("type", "string")
            .put("description", description)
        if (!enumValues.isNullOrEmpty()) property.put("enum", JSONArray(enumValues))
        return property
    }

    private fun integerProperty(description: String): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)

    private fun numberProperty(description: String): JSONObject = JSONObject()
        .put("type", "number")
        .put("description", description)

    private fun booleanProperty(description: String): JSONObject = JSONObject()
        .put("type", "boolean")
        .put("description", description)
}

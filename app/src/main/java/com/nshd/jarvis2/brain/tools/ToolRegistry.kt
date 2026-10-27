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
            "take_screenshot",
            "Take a screenshot of the current screen and save it to the app's screenshots folder. Use when the user asks for a screenshot to keep (see_screen is for looking at the screen yourself).",
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
            "Type text into the currently focused editable field, or the first editable field on screen. Dictation semantics: the text is INSERTED at the cursor (appended at the end when no cursor is known) — it does not erase what is already there; search boxes are the exception and are replaced. Entry is verified by reading the field back. To delete text instead, use edit_text.",
            schema(
                properties = JSONObject().put("text", stringProperty("Exact text to enter")),
                required = listOf("text")
            )
        ),
        ToolSpec(
            "scroll",
            "Scroll the current screen in one direction. ONE call scrolls ONCE by default — pass count only when the user explicitly asks for multiple scrolls (e.g. 'scroll down 2 times'). Never scroll twice for a single 'scroll' request.",
            schema(
                properties = JSONObject().put(
                    "direction",
                    stringProperty("Scroll direction", listOf("up", "down", "left", "right"))
                ).put(
                    "count",
                    integerProperty("Number of swipes, default 1. Only more than 1 when the user explicitly says a number.")
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
            "spotify_play",
            "Open Spotify, search and play the first song result for the query — one fast programmatic step (open → search → tap the song). Prefer this over manually tapping through Spotify.",
            schema(
                properties = JSONObject().put("query", stringProperty("Spotify search query — a song/track name, optionally with the artist")),
                required = listOf("query")
            )
        ),
        ToolSpec(
            "edit_text",
            "Edit the text in the CURRENTLY FOCUSED text field (e.g. the QuickEdit editor): delete text on command. Actions: clear_all (delete everything), delete_last_line, delete_last_word, delete_last_chars (needs count), delete_selection (only the selected range). Use this when he dictates into an editor and says delete / remove / erase / clear.",
            schema(
                properties = JSONObject()
                    .put(
                        "action",
                        stringProperty(
                            "Edit action",
                            listOf(
                                "clear_all", "delete_last_line", "delete_last_word",
                                "delete_last_chars", "delete_selection"
                            )
                        )
                    )
                    .put("count", integerProperty("Character count — only for delete_last_chars")),
                required = listOf("action")
            )
        ),
        ToolSpec(
            "insert_code",
            "Insert COMPLETE code into the focused text editor (QuickEdit). Pass the raw code only — no markdown fences, no prose — in ONE call; it is inserted at the cursor (appended at the end if no cursor is known), never splitting it across calls. If no editor is focused, QuickEdit is opened automatically. Afterwards confirm with the line count from the result.",
            schema(
                properties = JSONObject().put("code", stringProperty("The complete code text to insert, raw — no markdown fences")),
                required = listOf("code")
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
        ),
        ToolSpec(
            "see_screen",
            "Look at the actual screen pixels (a screenshot is attached as the next message). Use when the accessibility element list is unclear, incomplete, or the app draws its own UI (games, canvas, images).",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "vision_tap",
            "Tap a visual target described in words (an icon, image or unlabelled control) by finding it in a screenshot with the vision model. Use when the target has no text and does not appear in inspect_screen.",
            schema(
                properties = JSONObject().put("description", stringProperty("What to tap, described visually, e.g. \"the blue send arrow icon at the bottom right\"")),
                required = listOf("description")
            )
        ),
        // ── v1.9.0 "More Powers" tools ─────────────────────────────────
        // Risk classes: reads (read_page, clipboard_get) are SAFE and
        // ledger-exempt; gestures/clipboard writes are plain mutations;
        // every messaging tool stays behind the always-confirm gate in
        // ToolExecutor, exactly like whatsapp_message.
        ToolSpec(
            "long_press",
            "Long-press at screen coordinates given as percentages (0-100). Use for context menus and press-and-hold controls.",
            schema(
                properties = JSONObject()
                    .put("x", numberProperty("Horizontal position as a percentage"))
                    .put("y", numberProperty("Vertical position as a percentage")),
                required = listOf("x", "y")
            )
        ),
        ToolSpec(
            "double_tap",
            "Double-tap at screen coordinates given as percentages (0-100), e.g. to like a photo or zoom.",
            schema(
                properties = JSONObject()
                    .put("x", numberProperty("Horizontal position as a percentage"))
                    .put("y", numberProperty("Vertical position as a percentage")),
                required = listOf("x", "y")
            )
        ),
        ToolSpec(
            "drag",
            "Drag from one screen point to another, coordinates as percentages (0-100). Use for sliders, reordering and swipe-to-move interactions.",
            schema(
                properties = JSONObject()
                    .put("from_x", numberProperty("Start X as a percentage"))
                    .put("from_y", numberProperty("Start Y as a percentage"))
                    .put("to_x", numberProperty("End X as a percentage"))
                    .put("to_y", numberProperty("End Y as a percentage")),
                required = listOf("from_x", "from_y", "to_x", "to_y")
            )
        ),
        ToolSpec(
            "pinch_zoom",
            "Pinch at the screen centre: direction \"in\" zooms in (fingers spread), \"out\" zooms out. For maps and photos.",
            schema(
                properties = JSONObject().put(
                    "direction",
                    stringProperty("Pinch direction", listOf("in", "out"))
                ),
                required = listOf("direction")
            )
        ),
        ToolSpec(
            "set_slider",
            "Set the first visible slider/seek bar (volume-style, brightness, seek) to a percentage of its range.",
            schema(
                properties = JSONObject().put("percent", integerProperty("Slider position, 0-100")),
                required = listOf("percent")
            )
        ),
        ToolSpec(
            "read_page",
            "Read the current screen's readable text (article/page content as plain text). Then summarise it or read it aloud for the user, as asked. Read-only.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "undo_action",
            "Undo the last reversible action (a text edit, a dictation insert, or an app open). Sends and playback cannot be undone.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "clipboard_get",
            "Read the current clipboard text. Read-only.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "clipboard_set",
            "Put text on the clipboard so the user (or a paste) can use it.",
            schema(
                properties = JSONObject().put("text", stringProperty("Text to copy to the clipboard")),
                required = listOf("text")
            )
        ),
        ToolSpec(
            "copy_selection",
            "Copy the currently selected text in the focused field to the clipboard.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "paste_clipboard",
            "Paste the clipboard contents into the currently focused text field. Entry is verified by reading the field back.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "whatsapp_photo",
            "Send the most recent photo from the gallery to a WhatsApp contact (name, alias or phone number). Always confirmed with the user first.",
            schema(
                properties = JSONObject().put("contact", stringProperty("Contact name, saved alias or phone number")),
                required = listOf("contact")
            )
        ),
        ToolSpec(
            "whatsapp_reply_current",
            "Reply to the WhatsApp chat that is currently open on screen. Always confirmed (chat name + full text) first.",
            schema(
                properties = JSONObject().put("message", stringProperty("Reply text")),
                required = listOf("message")
            )
        ),
        ToolSpec(
            "instagram_message",
            "Send an Instagram DM to a contact (name or username). Always confirmed with the user first.",
            schema(
                properties = JSONObject()
                    .put("contact", stringProperty("Instagram contact name or username"))
                    .put("message", stringProperty("Message text")),
                required = listOf("contact", "message")
            )
        ),
        ToolSpec(
            "telegram_message",
            "Send a Telegram message to a contact (name or username). Always confirmed with the user first.",
            schema(
                properties = JSONObject()
                    .put("contact", stringProperty("Telegram contact name or username"))
                    .put("message", stringProperty("Message text")),
                required = listOf("contact", "message")
            )
        ),
        ToolSpec(
            "sms_message",
            "Send an SMS to a contact name or phone number via the default SMS app. Always confirmed with the user first.",
            schema(
                properties = JSONObject()
                    .put("contact", stringProperty("Contact name, saved alias or phone number"))
                    .put("message", stringProperty("SMS text")),
                required = listOf("contact", "message")
            )
        ),
        ToolSpec(
            "gmail_send",
            "Send an email via Gmail. Always confirmed (recipient + subject + body) first.",
            schema(
                properties = JSONObject()
                    .put("to", stringProperty("Recipient email address"))
                    .put("subject", stringProperty("Email subject"))
                    .put("body", stringProperty("Email body")),
                required = listOf("to", "subject", "body")
            )
        ),
        ToolSpec(
            "set_alarm",
            "Set an alarm in the Clock app (24-hour time).",
            schema(
                properties = JSONObject()
                    .put("hour", integerProperty("Hour, 0-23"))
                    .put("minute", integerProperty("Minute, 0-59"))
                    .put("label", stringProperty("Alarm label (optional)")),
                required = listOf("hour", "minute")
            )
        ),
        ToolSpec(
            "set_timer",
            "Start a countdown timer in the Clock app.",
            schema(
                properties = JSONObject()
                    .put("seconds", integerProperty("Timer length in seconds"))
                    .put("label", stringProperty("Timer label (optional)")),
                required = listOf("seconds")
            )
        ),
        ToolSpec(
            "calendar_event",
            "Create a calendar event starting in N minutes from now. The calendar app opens with the draft filled in for the user to save.",
            schema(
                properties = JSONObject()
                    .put("title", stringProperty("Event title"))
                    .put("start_in_minutes", integerProperty("Minutes from now until the event starts"))
                    .put("duration_minutes", integerProperty("Event length in minutes")),
                required = listOf("title", "start_in_minutes")
            )
        ),
        ToolSpec(
            "share_screenshot",
            "Share the current screen (or the latest Jarvis screenshot) through the Android share sheet — the user picks the target app.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "save_note",
            "Save a quick note to Memory Core (Notes category) — for \"note that …\" requests.",
            schema(
                properties = JSONObject().put("text", stringProperty("The note text")),
                required = listOf("text")
            )
        ),
        // ── v2.0.0 "Brain Upgrade" tools ───────────────────────────────
        ToolSpec(
            "whatsapp_group_message",
            "Send a WhatsApp message to a named GROUP (broadcast to that one group). Always confirmed with the user first, showing the group name and the full text.",
            schema(
                properties = JSONObject()
                    .put("group", stringProperty("WhatsApp group name"))
                    .put("message", stringProperty("Message text")),
                required = listOf("group", "message")
            )
        ),
        ToolSpec(
            "fill_form",
            "Fill the form on the current screen: detects the editable fields, proposes values for them from the user's Memory Core, asks for ONE confirmation listing every field and value, then types the approved values in. Fields that already have text are never overwritten.",
            schema(properties = JSONObject())
        ),
        ToolSpec(
            "wait_for_text",
            "Wait until the given text appears on the current screen (polls the accessibility snapshot). Use after an action when the next screen loads slowly, instead of guessing. Read-only.",
            schema(
                properties = JSONObject()
                    .put("text", stringProperty("Text to wait for"))
                    .put(
                        "timeout_seconds",
                        integerProperty("Max seconds to wait (1-30, default 15)")
                    ),
                required = listOf("text")
            )
        )
    )


    /**
     * Validate model-supplied tool arguments locally before dispatch. Provider
     * JSON schemas are guidance, not a security boundary: some endpoints emit
     * malformed types, missing required fields, or invented tool names.
     * Returns a short validation error, or null when the call is acceptable.
     */
    fun validateArguments(name: String, args: JSONObject): String? {
        if (args.toString().length > MAX_ARGUMENT_JSON_CHARS) {
            return "Tool arguments are too large"
        }
        val tool = spec(name) ?: return "Unknown tool: $name"
        val schema = tool.paramsJsonSchema
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()

        for (index in 0 until required.length()) {
            val key = required.optString(index)
            if (!args.has(key) || args.isNull(key)) {
                return "Missing required argument: $key"
            }
        }

        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val property = properties.optJSONObject(key)
                ?: return "Unexpected argument: $key"
            val value = args.opt(key)
            if (value == null || value === JSONObject.NULL) {
                return "Argument '$key' cannot be null"
            }
            val expectedType = property.optString("type")
            val validType = when (expectedType) {
                "string" -> value is String
                "integer" -> value is Int || value is Long ||
                    (value is Number && value.toDouble().isFinite() &&
                        value.toDouble() % 1.0 == 0.0)
                "number" -> value is Number && value.toDouble().isFinite()
                "boolean" -> value is Boolean
                "object" -> value is JSONObject
                "array" -> value is JSONArray
                else -> true
            }
            if (!validType) return "Argument '$key' must be $expectedType"

            val enumValues = property.optJSONArray("enum")
            if (enumValues != null) {
                var found = false
                for (i in 0 until enumValues.length()) {
                    if (enumValues.opt(i) == value) {
                        found = true
                        break
                    }
                }
                if (!found) return "Argument '$key' has an unsupported value"
            }

            if (value is String && value.length > MAX_ARGUMENT_STRING_CHARS) {
                return "Argument '$key' is too long"
            }
            validateNumericBounds(name, key, value)?.let { return it }
        }

        // Conditional arguments are validated here as well as by the schema:
        // some compatible providers ignore schema-required details.
        if (name == "edit_text" && args.optString("action") == "delete_last_chars" &&
            (!args.has("count") || args.isNull("count"))
        ) {
            return "Argument 'count' is required for delete_last_chars"
        }
        return null
    }

    /** Domain limits for tool values that can drive device actions. */
    private fun validateNumericBounds(toolName: String, key: String, value: Any): String? {
        if (value !is Number) return null
        val number = value.toDouble()
        val range = when {
            key in setOf("x", "y", "from_x", "from_y", "to_x", "to_y") &&
                toolName in setOf("tap_percent", "long_press", "double_tap", "drag") ->
                0.0..100.0
            key == "percent" && toolName in setOf("set_volume", "set_brightness", "set_slider") ->
                0.0..100.0
            toolName == "set_alarm" && key == "hour" -> 0.0..23.0
            toolName == "set_alarm" && key == "minute" -> 0.0..59.0
            toolName == "set_timer" && key == "seconds" -> 1.0..86_400.0
            toolName == "wait_for_text" && key == "timeout_seconds" -> 1.0..30.0
            toolName == "calendar_event" && key == "start_in_minutes" -> 0.0..525_600.0
            toolName == "calendar_event" && key == "duration_minutes" -> 1.0..10_080.0
            toolName == "edit_text" && key == "count" -> 1.0..MAX_ARGUMENT_STRING_CHARS.toDouble()
            else -> null
        } ?: return null
        if (number !in range) {
            return "Argument '$key' must be between ${range.start.toInt()} and ${range.endInclusive.toInt()}"
        }
        return null
    }

    private const val MAX_ARGUMENT_JSON_CHARS = 32_768
    private const val MAX_ARGUMENT_STRING_CHARS = 16_384

    fun spec(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    /**
     * Terminal tools (F3): on success the agent loop ends the turn
     * immediately with the tool result as the final summary — no extra
     * model round trips (which previously replayed media/open requests
     * in a loop). These are the registry's actual media/open tools;
     * there are no play_media/play_song tools in this build.
     */
    val TERMINAL_TOOLS: Set<String> = setOf("youtube_play", "spotify_play", "open_app")

    /** Media-play tools (F3): a success blocks voice auto-listen resume. */
    val MEDIA_PLAY_TOOLS: Set<String> = setOf("youtube_play", "spotify_play")

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

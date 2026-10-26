package com.nshd.jarvis2.brain.tools

/**
 * Central classification of tool effects. This is a safety aid, not a
 * substitute for per-action authorization or post-action verification.
 *
 * Unknown tools default to UNKNOWN and must not be treated as read-only.
 */
enum class ActionRisk {
    READ_ONLY,
    REVERSIBLE_MUTATION,
    EXTERNAL_SIDE_EFFECT,
    COMMUNICATION_SEND,
    HIGH_IMPACT,
    UNKNOWN
}

object ActionRiskPolicy {
    private val communicationSends = setOf(
        "whatsapp_message", "whatsapp_photo", "whatsapp_reply_current",
        "whatsapp_group_message", "instagram_message", "telegram_message",
        "sms_message", "gmail_send", "share_screenshot"
    )

    private val highImpact = setOf(
        "call_contact", "answer_call", "reject_call",
        "delete_last_chars", "undo_action", "clipboard_set", "paste_clipboard",
        "fill_form", "calendar_event", "download_url"
    )

    private val externalSideEffects = setOf(
        "set_alarm", "set_timer", "set_volume", "set_brightness",
        "toggle_torch", "set_silent", "media_control"
    )

    private val reversibleMutations = setOf(
        "open_app", "click_element", "click_text", "type_text", "scroll",
        "press_key", "tap_percent", "edit_text", "insert_code",
        "long_press", "double_tap", "drag", "pinch_zoom", "set_slider",
        "copy_selection", "youtube_play", "spotify_play"
    )

    private val knownReadOnly = setOf(
        "get_screen", "read_screen", "get_clipboard", "get_current_app",
        "get_time", "get_date", "search_contacts", "get_memory"
    )

    fun classify(toolName: String): ActionRisk = when (toolName) {
        in communicationSends -> ActionRisk.COMMUNICATION_SEND
        in highImpact -> ActionRisk.HIGH_IMPACT
        in externalSideEffects -> ActionRisk.EXTERNAL_SIDE_EFFECT
        in reversibleMutations -> ActionRisk.REVERSIBLE_MUTATION
        in knownReadOnly -> ActionRisk.READ_ONLY
        else -> ActionRisk.UNKNOWN
    }

    /** True for known actions whose exceptions may hide a partial side effect. */
    fun mayHaveSideEffect(toolName: String): Boolean =
        classify(toolName) !in setOf(ActionRisk.READ_ONLY, ActionRisk.UNKNOWN)

    /** Fail closed when a tool is not explicitly classified as read-only. */
    fun shouldTreatFailureAsOutcomeUnknown(toolName: String): Boolean =
        classify(toolName) != ActionRisk.READ_ONLY
}

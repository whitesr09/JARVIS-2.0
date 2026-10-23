package com.nshd.jarvis2.brain.tools

import android.content.Context
import com.nshd.jarvis2.brain.ExecutionLedger
import com.nshd.jarvis2.hands.ActionResult
import com.nshd.jarvis2.hands.ContactResolution
import com.nshd.jarvis2.hands.ContactsResolver
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.hands.GroundingResult
import com.nshd.jarvis2.hands.HandsActions
import com.nshd.jarvis2.hands.VisionGrounder
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.service.JarvisAccessibilityService
import org.json.JSONObject

data class ToolResult(
    val success: Boolean,
    val message: String,
    /** Optional Base64 JPEG the agent loop forwards to the model as vision input. */
    val imageBase64: String? = null
)

/**
 * Executes registry tools and enforces the Level 4 hard guardrails locally.
 * The model is never trusted to police itself: payment-like clicks/typing,
 * bulk calls and calls to numbers outside Contacts suspend in [ConfirmGate]
 * before any action runs.
 */
class ToolExecutor(
    context: Context,
    private val memoryDb: MemoryDb,
    /** Per-turn dedupe ledger (F3); null disables dedupe for ad-hoc calls. */
    private val ledger: ExecutionLedger? = null
) {
    private val appContext = context.applicationContext
    private val intents = DeepIntents(appContext)
    private val contactsResolver = ContactsResolver(appContext, memoryDb)
    private val vision = VisionGrounder(appContext)

    // F8 (#21): the previous step's compact screen entries (stable
    // keys), so step ≥2 of an agent turn sends only what changed.
    // Per-executor state: one executor serves one agent turn (the
    // Live path builds one per call, so Live always gets full
    // summaries — its first-step behaviour, unchanged).
    private var previousScreenKeys: Set<String>? = null
    private var previousScreenPackage: String? = null

    // F8 (#30): consecutive element-targeting failures this turn and
    // the vision assists already spent (hard cap per turn).
    private var consecutiveTargetFailures = 0
    private var visionAssistsUsed = 0

    suspend fun execute(name: String, args: JSONObject): ToolResult =
        execute(name, args, includeScreenSummary = true)

    suspend fun execute(
        name: String,
        args: JSONObject,
        includeScreenSummary: Boolean
    ): ToolResult {
        // F6: the running queue item's live step line follows the
        // tool (friendly label + argument gist — never typed content).
        com.nshd.jarvis2.brain.TaskQueue.setStepForRunning(stepTextFor(name, args))
        // F3 execution ledger: an identical call that already succeeded
        // this turn returns its cached result instead of re-executing;
        // failures get at most ExecutionLedger.MAX_ATTEMPTS attempts.
        // Pure observation tools are exempt: inspect_screen / see_screen
        // must always read the CURRENT screen (the verify-after-act
        // loop depends on fresh state, and a read replays no action).
        // F6: edit_text is exempt too — it is repeatable BY DESIGN:
        // identical arguments act on the CURRENT field state, so
        // "delete_last_line" twice in one turn must delete two lines,
        // not replay the first result. (insert_code stays ledgered:
        // inserting the same code twice is never intended.)
        val activeLedger = ledger?.takeUnless {
            name in OBSERVATION_TOOLS || name in REPEATABLE_TOOLS
        }
        activeLedger?.gate(name, args)?.let { cached ->
            // F5: ledger cache hits are exactly the "why didn't it run
            // again?" question the log exists to answer.
            com.nshd.jarvis2.core.AppLog.d(
                "Agent", "tool $name — ledger cache hit, not re-executed"
            )
            return cached
        }
        // F7: plan bookkeeping for the floating task panel — a real
        // execution marks the running item as tool-backed (chat-only
        // turns keep toolsExecuted=false) and drives the generic
        // 4-step plan's app/action steps.
        com.nshd.jarvis2.brain.TaskQueue.noteToolExecutedForRunning()
        if (name == "open_app") {
            com.nshd.jarvis2.brain.TaskQueue.onGenericToolEvent(openAppLike = true)
        } else if (name in UI_MUTATION_TOOLS) {
            com.nshd.jarvis2.brain.TaskQueue.onGenericToolEvent(openAppLike = false)
        }
        val startedAt = System.currentTimeMillis()
        // v1.9.0 (#41): capture the focused field's text BEFORE a text
        // mutation so undo can restore it exactly.
        val preEditFieldText = if (name == "edit_text" || name == "insert_code") {
            HandsActions.focusedFieldText()
        } else {
            null
        }
        val raw = try {
            dispatch(name, args)
        } catch (e: Exception) {
            ToolResult(false, "Tool $name failed safely: ${e.message ?: "unexpected error"}")
        }
        // v1.9.0 (#41): record the honest inverses of reversible
        // successes. Sends/plays are never recorded (they cannot be
        // undone and the undo tool must not pretend otherwise).
        if (raw.success) {
            when (name) {
                "type_text" -> HandsActions.pushUndo(
                    HandsActions.UndoEntry(
                        label = "Typed text",
                        kind = "delete_chars",
                        payload = args.optString("text").length.toString()
                    )
                )
                "edit_text" -> if (preEditFieldText != null) {
                    HandsActions.pushUndo(
                        HandsActions.UndoEntry(
                            label = "Text edit",
                            kind = "restore_text",
                            payload = preEditFieldText
                        )
                    )
                }
                "insert_code" -> if (preEditFieldText != null) {
                    HandsActions.pushUndo(
                        HandsActions.UndoEntry(
                            label = "Code insert",
                            kind = "restore_text",
                            payload = preEditFieldText
                        )
                    )
                }
                "open_app" -> HandsActions.pushUndo(
                    HandsActions.UndoEntry(
                        label = "Opened app",
                        kind = "press_back",
                        payload = ""
                    )
                )
            }
        }
        // F8 (#30): targeting-failure streak for the vision fallback.
        if (name == "click_element" || name == "click_text") {
            if (raw.success) {
                consecutiveTargetFailures = 0
            } else if (isTargetMiss(raw.message)) {
                consecutiveTargetFailures++
            }
        }
        com.nshd.jarvis2.core.AppLog.d(
            "Agent",
            "tool $name ${if (raw.success) "ok" else "FAILED"} " +
                "in ${System.currentTimeMillis() - startedAt}ms"
        )
        activeLedger?.record(name, args, raw)
        if (!includeScreenSummary || name == "inspect_screen" ||
            name == "see_screen" || name == "vision_tap" ||
            name == "take_screenshot" || name == "read_page" ||
            name == "clipboard_get"
        ) return raw
        val service = JarvisAccessibilityService.instance ?: return raw
        if (name in UI_MUTATION_TOOLS) {
            // Give the target app a brief chance to finish its transition so
            // the summary attached below is the post-action screen, not the
            // frame that existed before the tap had any effect.
            // F6: settle cap 1_200 ms → 900 ms (polling early-exits the
            // moment the tree is stable), and the summary is the
            // COMPACT serialization — the old 15 full-length labels
            // (an editor's whole content could ride along) were pure
            // prompt weight on every step.
            // v1.9.0 (#28): per-app settle profiles — media/chat apps
            // settle FAST (500 ms cap), banking/payment apps get the
            // cautious profile (1 500 ms), everything else the
            // standard 900 ms.
            val pkg = service.screenReader().currentPackage().orEmpty()
            val settleMs = when {
                pkg in FAST_SETTLE_PACKAGES -> 500L
                pkg.contains("bank", ignoreCase = true) ||
                    pkg.contains("pay", ignoreCase = true) ||
                    pkg.contains("upi", ignoreCase = true) -> 1_500L
                else -> 900L
            }
            com.nshd.jarvis2.core.AppLog.d(
                "Agent", "settle profile ${settleMs}ms for ${pkg.ifBlank { "unknown app" }}"
            )
            HandsActions.waitForSettle(settleMs)
        }
        return try {
            val summary = screenSummaryForStep(service)
            ToolResult(raw.success, raw.message + "\nScreen now:\n" + summary)
        } catch (e: Exception) {
            raw
        }
    }

    /**
     * F8 (#21): step-aware screen summary. The FIRST summary of a turn
     * (and any summary after the foreground app changes) is the full
     * compact serialization; later steps send ONLY the entries that
     * changed vs the previous step — keyed by their serialized line
     * minus the volatile [#index] prefix, so a label/state change IS
     * a diff entry. Focused/editable elements are ALWAYS included
     * (they're where the action is), and brand-new elements (e.g. a
     * dialog's) arrive as new keys. A diff over [DIFF_ENTRY_CAP]
     * falls back to the full compact list; inspect_screen always
     * returns the full serialization, untouched.
     */
    private fun screenSummaryForStep(
        service: JarvisAccessibilityService
    ): String {
        val reader = service.screenReader()
        val (pkg, lines) = reader.compactEntries()
        val keys = lines.map { screenEntryKey(it) }.toSet()
        val previous = previousScreenKeys
        val previousPkg = previousScreenPackage
        previousScreenKeys = keys
        previousScreenPackage = pkg
        if (previous == null || previousPkg != pkg) {
            com.nshd.jarvis2.core.AppLog.d(
                "Agent", "screen summary full=${lines.size} diff=full (first/app change)"
            )
            if (lines.isEmpty()) {
                return "App: $pkg\nVisible Elements: (none exposed by accessibility)"
            }
            return buildString {
                append("App: ").append(pkg).append("\nVisible Elements:")
                lines.forEach { line -> append("\n").append(line) }
            }
        }
        val changed = lines.filter { line ->
            screenEntryKey(line) !in previous || line.contains("(InputField)")
        }
        val unchangedCount = (lines.size - changed.size).coerceAtLeast(0)
        com.nshd.jarvis2.core.AppLog.d(
            "Agent", "screen summary full=${lines.size} diff=${changed.size}"
        )
        if (changed.size > DIFF_ENTRY_CAP) {
            return buildString {
                append("App: ").append(pkg).append("\nVisible Elements:")
                lines.forEach { line -> append("\n").append(line) }
            }
        }
        return buildString {
            append("App: ").append(pkg)
            append("\nVisible Elements (changed only; unchanged: ")
            append(unchangedCount).append(" elements hidden):")
            if (changed.isEmpty()) {
                append("\n(no visible changes)")
            } else {
                changed.forEach { line -> append("\n").append(line) }
            }
        }
    }

    /** Stable diff key for one compact entry line: everything after "[#N] ". */
    private fun screenEntryKey(line: String): String =
        line.substringAfter("] ", line)

    /** F8 (#30): does this failure mean "the target wasn't there"? */
    private fun isTargetMiss(message: String): Boolean =
        message.contains("No visible element matched") ||
            message.contains("not in the last screen snapshot") ||
            message.contains("could not be re-found")

    /**
     * F8 (#30): vision fallback assist. After TWO consecutive
     * targeting failures in this turn, the next click attempt is
     * served by vision instead: screenshot → the provider's vision
     * model grounds the described target → coordinate tap. Returns
     * null when the assist doesn't apply (streak too short, cap
     * reached, or no screenshot source — then normal targeting just
     * proceeds and fails honestly). Hard cap: [MAX_VISION_ASSISTS]
     * per turn. This is a loop-level assist inside the existing
     * click tools, NOT a new user-facing tool.
     */
    private suspend fun visionAssist(description: String): ToolResult? {
        if (description.isBlank()) return null
        if (consecutiveTargetFailures < 2) return null
        if (visionAssistsUsed >= MAX_VISION_ASSISTS) return null
        val image = vision.captureImageBase64()
        if (image == null) {
            com.nshd.jarvis2.core.AppLog.w(
                "Hands",
                "vision-fallback wanted for \"$description\" but no " +
                    "screenshot source is available — degrading to normal targeting"
            )
            return null
        }
        visionAssistsUsed++
        consecutiveTargetFailures = 0
        com.nshd.jarvis2.core.AppLog.i(
            "Hands",
            "vision-fallback used (assist $visionAssistsUsed/$MAX_VISION_ASSISTS) " +
                "for \"$description\""
        )
        return when (val grounded = vision.ground(description, image)) {
            is GroundingResult.Failed -> ToolResult(
                false,
                "Vision fallback could not locate \"$description\": ${grounded.message}"
            )
            is GroundingResult.Found -> {
                val tapped = HandsActions.tapPercent(
                    grounded.x / 10.0, grounded.y / 10.0
                )
                ToolResult(
                    tapped.success,
                    "Vision fallback tapped \"$description\" at " +
                        "(${grounded.x}, ${grounded.y}) via ${grounded.providerLabel} " +
                        "vision. ${tapped.message}"
                )
            }
        }
    }

    private suspend fun dispatch(name: String, args: JSONObject): ToolResult {
        // Generic bulk gate applies even if a model invents bulk_count on a
        // tool whose public schema does not advertise it.
        if (args.optInt("bulk_count", 0) > MAX_UNCONFIRMED_BULK) {
            val count = args.optInt("bulk_count")
            val approved = ConfirmGate.confirm(
                "Run $name as a bulk action affecting $count items? Bulk actions above " +
                    "$MAX_UNCONFIRMED_BULK require your approval."
            )
            if (!approved) return ToolResult(false, "Bulk action was not confirmed, so nothing was run.")
        }

        return when (name) {
            "inspect_screen" -> inspectScreen()
            "take_screenshot" -> takeScreenshot()
            // F8 (#15): internal quick-command tools (not in the model
            // registry — AgentPipeline's quick router calls them).
            "media_control" -> args.requiredString("action")?.let {
                intents.mediaKey(it).toToolResult()
            } ?: missing("action")
            "open_panel" -> args.requiredString("panel")?.let {
                intents.openPanel(it).toToolResult()
            } ?: missing("panel")
            "set_silent" -> if (args.has("on")) {
                intents.setSilent(args.optBoolean("on")).toToolResult()
            } else missing("on")
            "lock_screen" -> HandsActions.lockScreen().toToolResult()
            "open_app" -> args.requiredString("app")?.let { app ->
                intents.openApp(app).toToolResult()
            } ?: missing("app")
            "click_element" -> clickElement(args)
            "click_text" -> clickText(args)
            "type_text" -> typeText(args)
            "scroll" -> args.requiredString("direction")?.let {
                HandsActions.scroll(it).toToolResult()
            } ?: missing("direction")
            "press_key" -> args.requiredString("key")?.let {
                HandsActions.pressKey(it).toToolResult()
            } ?: missing("key")
            "tap_percent" -> tapPercent(args)
            "whatsapp_message" -> whatsappMessage(args)
            "youtube_play" -> args.requiredString("query")?.let {
                intents.youtubePlay(it).toToolResult()
            } ?: missing("query")
            "spotify_play" -> args.requiredString("query")?.let {
                intents.spotifyPlay(it).toToolResult()
            } ?: missing("query")
            "edit_text" -> editText(args)
            "insert_code" -> insertCode(args)
            "set_volume" -> if (args.has("percent")) {
                intents.setVolume(args.optInt("percent")).toToolResult()
            } else missing("percent")
            "toggle_torch" -> if (args.has("on")) {
                intents.toggleTorch(args.optBoolean("on")).toToolResult()
            } else missing("on")
            "set_brightness" -> if (args.has("percent")) {
                intents.setBrightness(args.optInt("percent")).toToolResult()
            } else missing("percent")
            "call_contact" -> callContact(args)
            "see_screen" -> seeScreen()
            "vision_tap" -> visionTap(args)
            // ── v1.9.0 "More Powers" ────────────────────────────────
            "long_press" -> if (args.has("x") && args.has("y")) {
                HandsActions.longPressPercent(
                    args.optDouble("x", -1.0), args.optDouble("y", -1.0)
                ).toToolResult()
            } else missing("x/y")
            "double_tap" -> if (args.has("x") && args.has("y")) {
                HandsActions.doubleTapPercent(
                    args.optDouble("x", -1.0), args.optDouble("y", -1.0)
                ).toToolResult()
            } else missing("x/y")
            "drag" -> if (args.has("from_x") && args.has("from_y") &&
                args.has("to_x") && args.has("to_y")
            ) {
                HandsActions.dragPercent(
                    args.optDouble("from_x", -1.0), args.optDouble("from_y", -1.0),
                    args.optDouble("to_x", -1.0), args.optDouble("to_y", -1.0)
                ).toToolResult()
            } else missing("from_x/from_y/to_x/to_y")
            "pinch_zoom" -> args.requiredString("direction")?.let {
                HandsActions.pinch(it.equals("in", ignoreCase = true)).toToolResult()
            } ?: missing("direction")
            "set_slider" -> if (args.has("percent")) {
                HandsActions.setSliderPercent(args.optInt("percent")).toToolResult()
            } else missing("percent")
            "read_page" -> readPage()
            "undo_action" -> HandsActions.undoLast().toToolResult()
            "clipboard_get" -> clipboardGet()
            "clipboard_set" -> args.requiredString("text")?.let {
                if (HandsActions.setClipboardText(it)) {
                    com.nshd.jarvis2.core.AppLog.d(
                        "Hands", "clipboard set (${it.length} chars)"
                    )
                    ToolResult(true, "Copied to the clipboard.")
                } else {
                    ToolResult(false, "The clipboard is unavailable right now.")
                }
            } ?: missing("text")
            "copy_selection" -> HandsActions.copySelection().toToolResult()
            "paste_clipboard" -> HandsActions.pasteIntoFocused().toToolResult()
            "whatsapp_photo" -> whatsappPhoto(args)
            "whatsapp_reply_current" -> whatsappReplyCurrent(args)
            "instagram_message" -> instagramMessage(args)
            "telegram_message" -> telegramMessage(args)
            "sms_message" -> smsMessage(args)
            "gmail_send" -> gmailSend(args)
            "set_alarm" -> if (args.has("hour") && args.has("minute")) {
                intents.setAlarm(
                    args.optInt("hour"), args.optInt("minute"),
                    args.optString("label")
                ).toToolResult()
            } else missing("hour/minute")
            "set_timer" -> if (args.has("seconds")) {
                intents.setTimer(
                    args.optInt("seconds"), args.optString("label")
                ).toToolResult()
            } else missing("seconds")
            "calendar_event" -> calendarEvent(args)
            "share_screenshot" -> intents.shareScreenshot().toToolResult()
            "save_note" -> args.requiredString("text")?.let {
                memoryDb.addFact(
                    com.nshd.jarvis2.memory.MemoryCategories.NOTES, it
                )
                ToolResult(true, "Note saved to Memory Core.")
            } ?: missing("text")
            else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    /**
     * F8 (#15): capture the screen and SAVE it (filesDir/screenshots/)
     * — unlike see_screen, which only shows the image to the model.
     */
    private suspend fun takeScreenshot(): ToolResult {
        val image = vision.captureImageBase64()
            ?: return ToolResult(
                false,
                "No screen image is available. Enable the Accessibility Service " +
                    "(Permissions → Accessibility) or start Screen Share in Settings, then ask again."
            )
        val source = vision.captureSourceName() ?: "screen capture"
        return try {
            val bytes = android.util.Base64.decode(image, android.util.Base64.DEFAULT)
            val dir = java.io.File(appContext.filesDir, "screenshots").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US
            ).format(java.util.Date())
            val file = java.io.File(dir, "jarvis-$stamp.jpg")
            file.writeBytes(bytes)
            com.nshd.jarvis2.core.AppLog.i(
                "Tools", "screenshot saved via $source (${bytes.size} bytes)"
            )
            ToolResult(true, "Screenshot taken via $source and saved as ${file.name}.")
        } catch (e: Exception) {
            ToolResult(false, "The screenshot was captured but could not be saved: ${e.message ?: "storage error"}")
        }
    }

    private suspend fun seeScreen(): ToolResult {
        val image = vision.captureImageBase64()
            ?: return ToolResult(
                false,
                "No screen image is available. Enable the Accessibility Service " +
                    "(Permissions → Accessibility) or start Screen Share in Settings, then ask again."
            )
        val source = vision.captureSourceName() ?: "screen capture"
        return ToolResult(
            true,
            "Screenshot captured via $source and attached as the next message — " +
                "inspect the pixels directly.",
            imageBase64 = image
        )
    }

    private suspend fun visionTap(args: JSONObject): ToolResult {
        val description = args.requiredString("description") ?: return missing("description")
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(
                false,
                "Accessibility is not enabled, so the grounded tap cannot be performed. " +
                    "Enable Jarvis Digital Hands in Permissions → Accessibility Service first."
            )
        val image = vision.captureImageBase64()
            ?: return ToolResult(
                false,
                "No screen image is available for grounding. Start Screen Share in " +
                    "Settings or check the Accessibility Service, then ask again."
            )
        return when (val grounded = vision.ground(description, image)) {
            is GroundingResult.Failed -> ToolResult(false, grounded.message)
            is GroundingResult.Found -> {
                val before = service.screenReader().signature()
                val tapped = HandsActions.tapPercent(grounded.x / 10.0, grounded.y / 10.0)
                if (!tapped.success) {
                    ToolResult(false, "Vision found \"$description\" but the tap failed: ${tapped.message}")
                } else {
                    HandsActions.waitForSettle(900)
                    val changed = service.screenReader().signature() != before
                    val summary = try {
                        service.screenReader().serializeCompact()
                    } catch (e: Exception) {
                        ""
                    }
                    ToolResult(
                        true,
                        "Tapped \"$description\" at (${grounded.x}, ${grounded.y}) via " +
                            "${grounded.providerLabel} vision. " +
                            if (changed) {
                                "The screen changed after the tap.\nScreen now:\n$summary"
                            } else {
                                "The tap landed but the screen signature did not change — " +
                                    "the target may not have responded.\nScreen now:\n$summary"
                            }
                    )
                }
            }
        }
    }

    private fun inspectScreen(): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(
                false,
                "Accessibility is not enabled. Enable Jarvis Digital Hands in Permissions → Accessibility Service first."
            )
        return ToolResult(true, service.screenReader().serialize())
    }

    private suspend fun clickElement(args: JSONObject): ToolResult {
        if (!args.has("element_id")) return missing("element_id")
        val index = args.optInt("element_id", -1)
        if (index < 0) return ToolResult(false, "element_id must be a non-negative snapshot index.")
        val element = JarvisAccessibilityService.lastSnapshot.getOrNull(index)
        // F8 (#30): two targeting failures in a row → vision assist.
        visionAssist(element?.label.orEmpty())?.let { return it }
        val targetText = listOf(element?.label.orEmpty(), element?.text.orEmpty(), element?.desc.orEmpty())
            .joinToString(" ")
        if (isPaymentLike(targetText)) {
            val approved = ConfirmGate.confirm(
                "This tap targets a payment or purchase control: \"${element?.label ?: "element #$index"}\". Allow it?"
            )
            if (!approved) return ToolResult(false, "Payment-related tap was not confirmed, so nothing was tapped.")
        }
        return HandsActions.clickElement(index).toToolResult()
    }

    private suspend fun clickText(args: JSONObject): ToolResult {
        val text = args.requiredString("text") ?: return missing("text")
        // F8 (#30): two targeting failures in a row → vision assist.
        visionAssist(text)?.let { return it }
        if (isPaymentLike(text)) {
            val approved = ConfirmGate.confirm(
                "This tap targets a payment or purchase control: \"$text\". Allow it?"
            )
            if (!approved) return ToolResult(false, "Payment-related tap was not confirmed, so nothing was tapped.")
        }
        return HandsActions.clickText(text).toToolResult()
    }

    private suspend fun typeText(args: JSONObject): ToolResult {
        val text = args.requiredString("text") ?: return missing("text")
        if (isPaymentLike(text) || containsOtpPattern(text) || UPI_ID_REGEX.containsMatchIn(text)) {
            val approved = ConfirmGate.confirm(
                "This text looks payment-related or like a one-time code. Allow Jarvis to type it?"
            )
            if (!approved) return ToolResult(false, "Sensitive text was not confirmed, so nothing was typed.")
        }
        return HandsActions.setTextOnFocused(text).toToolResult()
    }

    private suspend fun tapPercent(args: JSONObject): ToolResult {
        if (!args.has("x") || !args.has("y")) return missing("x/y")
        // A coordinate tap has no text argument, so inspect the snapshot
        // element under the point (plus any caller-supplied description)
        // before allowing a payment-like target through.
        val describedTarget = args.optString("target").ifBlank { args.optString("description") }
        val metrics = appContext.resources.displayMetrics
        val x = (metrics.widthPixels * args.optDouble("x", -1.0) / 100.0).toInt()
        val y = (metrics.heightPixels * args.optDouble("y", -1.0) / 100.0).toInt()
        val elementAtPoint = JarvisAccessibilityService.lastSnapshot
            .lastOrNull { it.bounds.contains(x, y) }
        val targetText = listOf(
            describedTarget,
            elementAtPoint?.label.orEmpty(),
            elementAtPoint?.text.orEmpty(),
            elementAtPoint?.desc.orEmpty()
        ).joinToString(" ")
        if (isPaymentLike(targetText)) {
            val approved = ConfirmGate.confirm(
                "This coordinate tap targets a payment or purchase control: " +
                    "\"${elementAtPoint?.label ?: describedTarget}\". Allow it?"
            )
            if (!approved) return ToolResult(false, "Payment-related tap was not confirmed, so nothing was tapped.")
        }
        return HandsActions.tapPercent(args.optDouble("x", -1.0), args.optDouble("y", -1.0)).toToolResult()
    }

    /**
     * F6 (Part D): delete text in the focused field on the user's
     * explicit command. Deliberately NO ConfirmGate and no tap guard
     * here: the command IS the instruction, and deletions in his own
     * editor are the requested behaviour (all logged, tag "Tools").
     */
    private suspend fun editText(args: JSONObject): ToolResult {
        val action = args.requiredString("action") ?: return missing("action")
        val count = args.optInt("count", 0)
        val result = HandsActions.editTextOnFocused(action, count)
        com.nshd.jarvis2.core.AppLog.d(
            "Tools",
            "edit_text action=$action count=$count → success=${result.success}"
        )
        return result.toToolResult()
    }

    /**
     * F6 (Part D2): insert complete code into the focused editor.
     * The argument is defensively de-fenced first (models wrap code
     * in ``` fences despite instructions). When no editable field is
     * focused, QuickEdit is opened as a convenience (package
     * com.rhmsoft.edit) and its largest editor area tapped once;
     * if that still yields no editor, the failure tells him exactly
     * what to do. NO length cap anywhere on this path — long code is
     * chunked inside HandsActions.
     */
    private suspend fun insertCode(args: JSONObject): ToolResult {
        val raw = args.optString("code")
        if (raw.isBlank()) return missing("code")
        val code = stripCodeFences(raw)
        if (code.isBlank()) {
            return ToolResult(false, "The code to insert was empty after cleanup.")
        }
        // F7 plan: the editor-code route sets this plan itself; when
        // the model reaches insert_code through the generic loop, give
        // the panel the same checklist (insert phase running).
        val runningItem = com.nshd.jarvis2.brain.TaskQueue.runningItem()
        if (runningItem != null && runningItem.plan.isEmpty()) {
            com.nshd.jarvis2.brain.TaskQueue.setPlan(
                runningItem.id,
                listOf("Understand goal", "Write the code", "Insert into QuickEdit", "Verify"),
                kind = "editor"
            )
            com.nshd.jarvis2.brain.TaskQueue.advancePlan(runningItem.id, 2)
        }
        var autoOpened = false
        if (!HandsActions.focusedEditablePresent()) {
            com.nshd.jarvis2.brain.TaskQueue.setStepForRunning("Opening QuickEdit…")
            val opened = intents.openApp("QuickEdit")
            if (!opened.success) {
                return ToolResult(
                    false,
                    "Open QuickEdit and tap into the editing area first, then ask me again."
                )
            }
            autoOpened = true
            HandsActions.waitForPackage(QUICKEDIT_PACKAGE, 2_500)
            if (!HandsActions.waitForEditable(2_500)) {
                return ToolResult(
                    false,
                    "QuickEdit opened, but its editor never appeared — tap into " +
                        "the editing area, then ask me again."
                )
            }
            val snapshot = JarvisAccessibilityService.instance
                ?.screenReader()?.snapshot().orEmpty()
            val largestEditor = snapshot
                .filter { it.editable }
                .maxByOrNull { it.bounds.width() * it.bounds.height() }
            if (largestEditor != null) {
                HandsActions.clickElement(largestEditor.index)
            }
            if (!HandsActions.focusedEditablePresent()) {
                return ToolResult(
                    false,
                    "Open QuickEdit and tap into the editing area first, then ask me again."
                )
            }
        }
        // v1.9.0 (#64): inserting over a SELECTION replaces text the
        // user can see — show a compact before/after diff in the
        // confirm gate first. A plain cursor insert (no selection)
        // stays gate-free, as before.
        val selection = HandsActions.focusedSelectionText()
        if (!selection.isNullOrBlank()) {
            val beforePreview = selection.lines().take(5)
                .joinToString("\n") { "- $it" }
            val afterPreview = code.lines().take(5)
                .joinToString("\n") { "+ $it" }
            val approved = ConfirmGate.confirm(
                "Replace the selected ${selection.lines().size} line(s) " +
                    "in the editor with ${code.lines().size} new line(s)?\n\n" +
                    "Before:\n$beforePreview\n\nAfter:\n$afterPreview"
            )
            if (!approved) {
                return ToolResult(
                    false,
                    "Code insert was not confirmed — the selection is unchanged."
                )
            }
        }
        val result = HandsActions.insertCodeIntoFocused(code)
        val lineCount = code.trimEnd('\n').lines().size
        com.nshd.jarvis2.core.AppLog.i(
            "Tools",
            "insert_code: $lineCount lines / ${code.length} chars, " +
                "autoOpened=$autoOpened, success=${result.success}, " +
                "preview='${code.replace('\n', ' ').take(40)}'"
        )
        if (!result.success) return ToolResult(false, result.message)
        val note = if (autoOpened) " (QuickEdit was opened automatically.)" else ""
        return ToolResult(
            true,
            "Inserted $lineCount lines of code into QuickEdit.$note"
        )
    }

    /**
     * Defensive cleanup for insert_code arguments: if a fenced block
     * is present anywhere in the argument, the code is what's inside
     * the FIRST fence pair (plus anything after it is dropped — prose
     * the model added around the code never reaches the editor).
     */
    private fun stripCodeFences(raw: String): String {
        val text = raw.trim()
        val fenceStart = text.indexOf("```")
        if (fenceStart >= 0) {
            val bodyStart = text.indexOf('\n', fenceStart)
            if (bodyStart >= 0) {
                val fenceEnd = text.indexOf("```", bodyStart)
                if (fenceEnd > bodyStart) {
                    return text.substring(bodyStart + 1, fenceEnd)
                        .trim('\n')
                        .trimEnd()
                }
            }
        }
        return text.trimEnd()
    }

    /** F6: friendly live-step text for a tool call (label + gist). */
    private fun stepTextFor(name: String, args: JSONObject): String {
        val gist = when (name) {
            "open_app" -> args.optString("app")
            "youtube_play", "spotify_play" -> args.optString("query")
            "whatsapp_message", "call_contact" -> args.optString("contact")
            "click_text" -> args.optString("text")
            else -> ""
        }.trim()
        return when (name) {
            "open_app" -> "Opening ${gist.ifBlank { "app" }}…"
            "youtube_play" -> "Playing on YouTube…"
            "spotify_play" -> "Playing on Spotify…"
            "inspect_screen", "see_screen" -> "Looking at the screen…"
            "type_text" -> "Typing…"
            "edit_text" -> "Editing text…"
            "insert_code" -> "Inserting code…"
            "vision_tap" -> "Finding the tap target…"
            else -> com.nshd.jarvis2.brain.ToolEvent.prettyTool(name) + "…"
        }
    }

    private suspend fun whatsappMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        // F7: the send chain is stage-logged end to end (tag "Send")
        // so a "not sending" report can be traced to the exact stage:
        // routed → resolved → gate → opened → typed → tapped → verified.
        com.nshd.jarvis2.core.AppLog.i(
            "Send", "routed: whatsapp_message to '$contact' (${message.length} chars)"
        )
        // F7 plan: the panel walks these steps; "Confirm & send"
        // (step 3) is the RUNNING step while the gate waits, so the
        // user sees WHY the task is paused (see the gate block below).
        com.nshd.jarvis2.brain.TaskQueue.setPlanForRunning(
            listOf(
                "Understand goal",
                "Open chat with $contact",
                "Type the message",
                "Confirm & send"
            ),
            kind = "send"
        )
        com.nshd.jarvis2.brain.TaskQueue.setPlanStepForRunning(
            0, com.nshd.jarvis2.brain.PlanStepState.DONE
        )
        com.nshd.jarvis2.brain.TaskQueue.setPlanStepForRunning(
            1, com.nshd.jarvis2.brain.PlanStepState.RUNNING
        )
        return when (val resolution = contactsResolver.resolve(contact)) {
            is ContactResolution.Found -> {
                val name = resolution.contact.displayName
                com.nshd.jarvis2.core.AppLog.i(
                    "Send", "contact resolved: '$contact' → $name (${resolution.contact.phone})"
                )
                // F3: EVERY message send is confirmed, always — the
                // prompt names the recipient AND shows the full text.
                com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(3)
                com.nshd.jarvis2.core.AppLog.i(
                    "Send", "gate shown: confirm send to $name"
                )
                val approved = ConfirmGate.confirm(
                    "Send WhatsApp message to $name " +
                        "(${resolution.contact.phone})?\n\nMessage: \"$message\""
                )
                com.nshd.jarvis2.core.AppLog.i(
                    "Send", "gate decision: ${if (approved) "approved" else "denied/timeout"}"
                )
                if (!approved) {
                    return ToolResult(
                        false,
                        "WhatsApp message was not confirmed, so nothing was sent."
                    )
                }
                // F7 SendPermit: THIS approved send's tap on WhatsApp's
                // Send button must not hit the tap guard's second gate
                // (the F3 double-gate that stalled sends). One permit
                // per package variant; the guard consumes exactly one.
                HandsActions.grantSendPermit("com.whatsapp")
                HandsActions.grantSendPermit("com.whatsapp.w4b")
                val result = intents.whatsappMessage(resolution.contact.phone, message)
                if (result.success) {
                    com.nshd.jarvis2.core.AppLog.i(
                        "Send", "verified: message sent to $name"
                    )
                    ToolResult(true, "WhatsApp message to $name: ${result.message}")
                } else {
                    com.nshd.jarvis2.core.AppLog.w(
                        "Send", "FAILED after approval: ${result.message}"
                    )
                    ToolResult(false, result.message)
                }
            }
            is ContactResolution.Ambiguous -> ToolResult(
                false,
                "I found multiple contacts matching \"$contact\": " +
                    resolution.candidates.joinToString(", ") { it.displayName } +
                    ". Ask the user to pick one — nothing was sent."
            )
            ContactResolution.NotFound -> ToolResult(
                false,
                "No contact matched \"$contact\", and it does not look like a complete phone number. Nothing was sent."
            )
        }
    }

    // ── v1.9.0 tool implementations ──────────────────────────────────

    /** #39: the current screen's readable text (SAFE read, tag "Hands"). */
    private fun readPage(): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(
                false,
                "Accessibility is not enabled. Enable Jarvis Digital Hands in Permissions → Accessibility Service first."
            )
        val text = service.screenReader().pageText()
        com.nshd.jarvis2.core.AppLog.i(
            "Hands", "read_page: ${text.length} chars from ${service.screenReader().currentPackage()}"
        )
        if (text.isBlank()) {
            return ToolResult(false, "No readable text was found on this screen.")
        }
        return ToolResult(true, "Page text:\n$text")
    }

    /** #42: clipboard read (SAFE read, tag "Hands"). */
    private fun clipboardGet(): ToolResult {
        val text = HandsActions.clipboardText()
        com.nshd.jarvis2.core.AppLog.d(
            "Hands", "clipboard_get: ${text?.length ?: 0} chars"
        )
        return if (text.isNullOrBlank()) {
            ToolResult(false, "The clipboard is empty (or holds no text).")
        } else {
            ToolResult(true, "Clipboard: \"$text\"")
        }
    }

    /** #43: send the latest gallery photo on WhatsApp — gated like every send. */
    private suspend fun whatsappPhoto(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        return when (val resolution = contactsResolver.resolve(contact)) {
            is ContactResolution.Found -> {
                val name = resolution.contact.displayName
                val approved = ConfirmGate.confirm(
                    "Send your most recent gallery photo on WhatsApp to " +
                        "$name (${resolution.contact.phone})?"
                )
                if (!approved) {
                    return ToolResult(false, "Photo send was not confirmed, so nothing was sent.")
                }
                HandsActions.grantSendPermit("com.whatsapp")
                HandsActions.grantSendPermit("com.whatsapp.w4b")
                intents.whatsappShareLatestPhoto(resolution.contact.phone).toToolResult()
            }
            is ContactResolution.Ambiguous -> ToolResult(
                false,
                "I found multiple contacts matching \"$contact\": " +
                    resolution.candidates.joinToString(", ") { it.displayName } +
                    ". Ask the user to pick one — nothing was sent."
            )
            ContactResolution.NotFound -> ToolResult(
                false,
                "No contact matched \"$contact\". Nothing was sent."
            )
        }
    }

    /** #44: reply inside the WhatsApp chat currently open on screen. */
    private suspend fun whatsappReplyCurrent(args: JSONObject): ToolResult {
        val message = args.requiredString("message") ?: return missing("message")
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b")
            .firstOrNull { intents.currentChatTitle(it) != null }
            ?: return ToolResult(
                false,
                "No WhatsApp chat is open on screen right now. Open the chat " +
                    "(e.g. from its notification), then ask again."
            )
        val title = intents.currentChatTitle(pkg) ?: "the open chat"
        val approved = ConfirmGate.confirm(
            "Reply on WhatsApp to $title?\n\nMessage: \"$message\""
        )
        if (!approved) {
            return ToolResult(false, "Reply was not confirmed, so nothing was sent.")
        }
        HandsActions.grantSendPermit(pkg)
        return intents.typeAndSendInOpenChat(pkg, message).toToolResult()
    }

    /** #45: Instagram DM — always-confirm rule, same as WhatsApp. */
    private suspend fun instagramMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        val approved = ConfirmGate.confirm(
            "Send Instagram DM to $contact?\n\nMessage: \"$message\""
        )
        if (!approved) {
            return ToolResult(false, "Instagram DM was not confirmed, so nothing was sent.")
        }
        HandsActions.grantSendPermit("com.instagram.android")
        return intents.instagramDm(contact, message).toToolResult()
    }

    /** #46: Telegram message — always-confirm rule, same as WhatsApp. */
    private suspend fun telegramMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        val approved = ConfirmGate.confirm(
            "Send Telegram message to $contact?\n\nMessage: \"$message\""
        )
        if (!approved) {
            return ToolResult(false, "Telegram message was not confirmed, so nothing was sent.")
        }
        HandsActions.grantSendPermit("org.telegram.messenger")
        return intents.telegramMessage(contact, message).toToolResult()
    }

    /** #47: SMS via the default SMS app — resolved contact + gate first. */
    private suspend fun smsMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        if (ContactsResolver.looksLikePhone(contact)) {
            val number = ContactsResolver.normalizePhone(contact)
            val approved = ConfirmGate.confirm(
                "Send SMS to $number?\n\nMessage: \"$message\""
            )
            if (!approved) {
                return ToolResult(false, "SMS was not confirmed, so nothing was sent.")
            }
            return intents.smsSend(number, message).toToolResult()
        }
        return when (val resolution = contactsResolver.resolve(contact)) {
            is ContactResolution.Found -> {
                val name = resolution.contact.displayName
                val approved = ConfirmGate.confirm(
                    "Send SMS to $name (${resolution.contact.phone})?\n\nMessage: \"$message\""
                )
                if (!approved) {
                    return ToolResult(false, "SMS was not confirmed, so nothing was sent.")
                }
                intents.smsSend(resolution.contact.phone, message).toToolResult()
            }
            is ContactResolution.Ambiguous -> ToolResult(
                false,
                "I found multiple contacts matching \"$contact\": " +
                    resolution.candidates.joinToString(", ") { it.displayName } +
                    ". Ask the user to pick one — nothing was sent."
            )
            ContactResolution.NotFound -> ToolResult(
                false,
                "No contact matched \"$contact\". Nothing was sent."
            )
        }
    }

    /** #48: Gmail send — gate shows recipient + subject + body. */
    private suspend fun gmailSend(args: JSONObject): ToolResult {
        val to = args.requiredString("to") ?: return missing("to")
        val subject = args.requiredString("subject") ?: return missing("subject")
        val body = args.requiredString("body") ?: return missing("body")
        val approved = ConfirmGate.confirm(
            "Send email via Gmail to $to?\n\nSubject: $subject\n\n$body"
        )
        if (!approved) {
            return ToolResult(false, "Email was not confirmed, so nothing was sent.")
        }
        return intents.gmailSend(to, subject, body).toToolResult()
    }

    /** #108: calendar draft via intent (the calendar app confirms). */
    private fun calendarEvent(args: JSONObject): ToolResult {
        val title = args.requiredString("title") ?: return missing("title")
        if (!args.has("start_in_minutes")) return missing("start_in_minutes")
        val startIn = args.optInt("start_in_minutes").coerceAtLeast(0)
        val duration = args.optInt("duration_minutes", 60).coerceAtLeast(5)
        val begin = System.currentTimeMillis() + startIn * 60_000L
        return intents.calendarAddEvent(title, begin, begin + duration * 60_000L)
            .toToolResult()
    }

    private suspend fun callContact(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        // A raw number bypasses name resolution, so check Contacts here:
        // numbers that are not stored there are a hard guardrail.
        if (ContactsResolver.looksLikePhone(contact)) {
            val number = ContactsResolver.normalizePhone(contact)
            if (contactsResolver.isNumberInContacts(number)) {
                return intents.callNumber(number, number).toToolResult()
            }
            val approved = ConfirmGate.confirm(
                "Call $number? This number is not in contacts, so your approval is required."
            )
            return if (approved) {
                intents.callNumber(number, number).toToolResult()
            } else {
                ToolResult(false, "Call to the number outside contacts was not confirmed.")
            }
        }
        return when (val resolution = contactsResolver.resolve(contact)) {
            is ContactResolution.Found -> intents.callNumber(
                resolution.contact.phone,
                resolution.contact.displayName
            ).toToolResult()
            is ContactResolution.Ambiguous -> ToolResult(
                false,
                "I found multiple contacts matching \"$contact\": " +
                    resolution.candidates.joinToString(", ") { it.displayName } +
                    ". Ask the user to pick one — no call was placed."
            )
            ContactResolution.NotFound -> {
                if (!ContactsResolver.looksLikePhone(contact)) {
                    ToolResult(false, "No contact matched \"$contact\". No call was placed.")
                } else {
                    val number = ContactsResolver.normalizePhone(contact)
                    val approved = ConfirmGate.confirm(
                        "Call $number? This number is not in contacts, so your approval is required."
                    )
                    if (!approved) {
                        ToolResult(false, "Call to the number outside contacts was not confirmed.")
                    } else {
                        intents.callNumber(number, number).toToolResult()
                    }
                }
            }
        }
    }

    private fun JSONObject.requiredString(name: String): String? =
        optString(name).trim().takeIf { it.isNotBlank() }

    private fun missing(name: String): ToolResult =
        ToolResult(false, "Missing required argument: $name")

    private fun ActionResult.toToolResult(): ToolResult = ToolResult(success, message)

    private fun isPaymentLike(text: String): Boolean =
        PAYMENT_REGEX.containsMatchIn(text.lowercase())

    private fun containsOtpPattern(text: String): Boolean =
        OTP_REGEX.containsMatchIn(text.lowercase()) ||
            CARD_NUMBER_REGEX.containsMatchIn(text)

    companion object {
        private const val MAX_UNCONFIRMED_BULK = 5
        /** F8 (#21): a bigger diff than this falls back to full compact. */
        private const val DIFF_ENTRY_CAP = 40
        /** F8 (#30): vision assists allowed per agent turn. */
        private const val MAX_VISION_ASSISTS = 2
        private const val QUICKEDIT_PACKAGE = "com.rhmsoft.edit"
        /** Read-only screen observation — exempt from the F3 ledger. */
        private val OBSERVATION_TOOLS = setOf(
            "inspect_screen", "see_screen", "take_screenshot",
            // v1.9.0: SAFE reads (page text / clipboard) — always fresh.
            "read_page", "clipboard_get"
        )

        /** v1.9.0 (#28): apps whose transitions settle fast. */
        private val FAST_SETTLE_PACKAGES = setOf(
            "com.spotify.music", "com.google.android.youtube",
            "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android",
            "org.telegram.messenger"
        )

        /**
         * Stateful-but-repeatable tools exempt from ledger dedupe.
         * F6 exempted edit_text; F7 (Part G assessment) adds type_text
         * on the same rationale: both act on the CURRENT field state,
         * so identical arguments in one turn are legitimate repeated
         * work (dictating the same word twice, appending the same
         * chunk to a second field), not an accidental replay of a
         * one-shot action. One-shot tools (sends, plays, opens) stay
         * ledgered — the anti-replay protection the ledger exists for.
         */
        private val REPEATABLE_TOOLS = setOf("edit_text", "type_text")
        private val UI_MUTATION_TOOLS = setOf(
            "open_app", "click_element", "click_text", "type_text", "scroll",
            "press_key", "tap_percent", "whatsapp_message", "youtube_play",
            "spotify_play", "edit_text", "insert_code",
            // v1.9.0 mutations (gestures, clipboard writes, new sends).
            "long_press", "double_tap", "drag", "pinch_zoom", "set_slider",
            "undo_action", "clipboard_set", "copy_selection", "paste_clipboard",
            "whatsapp_photo", "whatsapp_reply_current", "instagram_message",
            "telegram_message", "sms_message", "gmail_send", "share_screenshot"
        )
        private val PAYMENT_REGEX = Regex(
            "\\b(pay|payment|payments|purchase|buy now|place order|checkout|check out|" +
                "send money|transfer money|confirm payment|upi)\\b"
        )
        private val OTP_REGEX = Regex("\\b(otp|one[- ]time password|one[- ]time code|verification code)\\b")
        private val UPI_ID_REGEX = Regex("[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}")
        private val CARD_NUMBER_REGEX = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    }
}

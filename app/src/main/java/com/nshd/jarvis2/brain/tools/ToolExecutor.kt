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
        val raw = try {
            dispatch(name, args)
        } catch (e: Exception) {
            ToolResult(false, "Tool $name failed safely: ${e.message ?: "unexpected error"}")
        }
        com.nshd.jarvis2.core.AppLog.d(
            "Agent",
            "tool $name ${if (raw.success) "ok" else "FAILED"} " +
                "in ${System.currentTimeMillis() - startedAt}ms"
        )
        activeLedger?.record(name, args, raw)
        if (!includeScreenSummary || name == "inspect_screen" ||
            name == "see_screen" || name == "vision_tap"
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
            HandsActions.waitForSettle(900)
        }
        return try {
            val summary = service.screenReader().serializeCompact()
            ToolResult(raw.success, raw.message + "\nScreen now:\n" + summary)
        } catch (e: Exception) {
            raw
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
            else -> ToolResult(false, "Unknown tool: $name")
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
        private const val QUICKEDIT_PACKAGE = "com.rhmsoft.edit"
        /** Read-only screen observation — exempt from the F3 ledger. */
        private val OBSERVATION_TOOLS = setOf("inspect_screen", "see_screen")

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
            "spotify_play", "edit_text", "insert_code"
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

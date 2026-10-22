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
        // F3 execution ledger: an identical call that already succeeded
        // this turn returns its cached result instead of re-executing;
        // failures get at most ExecutionLedger.MAX_ATTEMPTS attempts.
        // Pure observation tools are exempt: inspect_screen / see_screen
        // must always read the CURRENT screen (the verify-after-act
        // loop depends on fresh state, and a read replays no action).
        val activeLedger = ledger?.takeUnless { name in OBSERVATION_TOOLS }
        activeLedger?.gate(name, args)?.let { cached ->
            // F5: ledger cache hits are exactly the "why didn't it run
            // again?" question the log exists to answer.
            com.nshd.jarvis2.core.AppLog.d(
                "Agent", "tool $name — ledger cache hit, not re-executed"
            )
            return cached
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
            HandsActions.waitForSettle(1_200)
        }
        return try {
            val summary = service.screenReader().serialize(MAX_SUMMARY_ELEMENTS)
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
                    HandsActions.waitForSettle(1_200)
                    val changed = service.screenReader().signature() != before
                    val summary = try {
                        service.screenReader().serialize(MAX_SUMMARY_ELEMENTS)
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

    private suspend fun whatsappMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        return when (val resolution = contactsResolver.resolve(contact)) {
            is ContactResolution.Found -> {
                // F3: EVERY message send is confirmed, always — the
                // prompt names the recipient AND shows the full text.
                val approved = ConfirmGate.confirm(
                    "Send WhatsApp message to ${resolution.contact.displayName} " +
                        "(${resolution.contact.phone})?\n\nMessage: \"$message\""
                )
                if (!approved) {
                    return ToolResult(
                        false,
                        "WhatsApp message was not confirmed, so nothing was sent."
                    )
                }
                val result = intents.whatsappMessage(resolution.contact.phone, message)
                if (result.success) {
                    ToolResult(true, "WhatsApp message to ${resolution.contact.displayName}: ${result.message}")
                } else {
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
        private const val MAX_SUMMARY_ELEMENTS = 15
        /** Read-only screen observation — exempt from the F3 ledger. */
        private val OBSERVATION_TOOLS = setOf("inspect_screen", "see_screen")
        private val UI_MUTATION_TOOLS = setOf(
            "open_app", "click_element", "click_text", "type_text", "scroll",
            "press_key", "tap_percent", "whatsapp_message", "youtube_play"
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

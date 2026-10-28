package com.nshd.jarvis2.brain.tools

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.brain.ExecutionLedger
import com.nshd.jarvis2.core.MacroRecorder
import com.nshd.jarvis2.hands.ActionOutcome
import com.nshd.jarvis2.hands.ActionResult
import com.nshd.jarvis2.hands.ContactResolution
import com.nshd.jarvis2.hands.ContactsResolver
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.hands.GroundingResult
import com.nshd.jarvis2.hands.HandsActions
import com.nshd.jarvis2.hands.TaskStoppedException
import com.nshd.jarvis2.hands.VisionGrounder
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.service.JarvisAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class ToolResult(
    val success: Boolean,
    val message: String,
    /** Optional Base64 JPEG the agent loop forwards to the model as vision input. */
    val imageBase64: String? = null,
    val outcome: ActionOutcome = if (success) ActionOutcome.COMPLETED else ActionOutcome.FAILED
)

/**
 * Executes registry tools and enforces the Level 4 hard guardrails locally.
 * The model is never trusted to police itself: payment-like clicks/typing,
 * bulk calls and calls to numbers outside Contacts suspend in [ConfirmGate]
 * before any action runs. SENDS are different (v2.0.2): they suspend in
 * [SendAsk] just-in-time — after the chat/composer is open and the text
 * is filled + verified, before the send tap — never in a card, and
 * never before the composer exists.
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
        // Model-provided tool calls are untrusted input. Validate the name,
        // required fields, JSON types, enums and payload bounds before task
        // bookkeeping, dedupe-ledger access, or any phone-side effect.
        ToolRegistry.validateArguments(name, args)?.let { validationError ->
            // A rejected step means this turn did not produce a complete,
            // replayable macro candidate, even if earlier steps succeeded.
            MacroRecorder.markFailure()
            com.nshd.jarvis2.core.AppLog.w(
                "Agent", "rejected invalid tool call '$name': $validationError"
            )
            return ToolResult(false, "I didn't run that action: $validationError.")
        }
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
        // F18: type_text is NOT fully exempt anymore — it uses the
        // capped repeatable gate instead (identical text appended
        // unboundedly is a loop, and the Live path has no loop guard
        // of its own). edit_text stays exempt (state-relative).
        if (name == "type_text") {
            ledger?.gateRepeatable(name, args)?.let { cached ->
                if (!cached.success) MacroRecorder.markFailure()
                com.nshd.jarvis2.core.AppLog.d(
                    "Agent", "tool $name — repeatable gate hit, not re-executed"
                )
                return cached
            }
        }
        val activeLedger = ledger?.takeUnless {
            name in OBSERVATION_TOOLS || name in REPEATABLE_TOOLS
        }
        activeLedger?.gate(name, args)?.let { cached ->
            if (!cached.success) MacroRecorder.markFailure()
            // F5: ledger cache hits are exactly the "why didn't it run
            // again?" question the log exists to answer.
            com.nshd.jarvis2.core.AppLog.d(
                "Agent", "tool $name — ledger cache hit, not re-executed"
            )
            return cached
        }
        // F18: cross-turn duplicate-send guard — the per-turn ledger
        // cannot see retries in a NEW turn, so an identical send that
        // already happened (or likely happened) is refused here,
        // before any JIT ask or phone action.
        val sendGuardKey = sendGuardKey(name, args)
        if (sendGuardKey != null && RecentSends.wasRecentlySent(appContext, sendGuardKey)) {
            return ToolResult(
                false,
                "Duplicate-send guard: an identical message to this recipient was already sent (or likely sent) within the last 30 minutes — check the chat before sending again. This is a refusal, not a failure; reword the message or wait to send it again.",
                outcome = ActionOutcome.OUTCOME_UNKNOWN
            )
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
        val dispatched = try {
            // Hang-proofing: a tool that stalls on a slow device or
            // network ends as OUTCOME_UNKNOWN (terminal: same-turn
            // retry blocked, sends cross-turn guarded) instead of
            // hanging the turn until the user kills it.
            withTimeoutOrNull(TOOL_TIMEOUT_MS) { dispatch(name, args) }
                ?: ToolResult(
                    false,
                    "Tool $name timed out after 60 seconds — the action may or may not have reached the app. Check the app before retrying.",
                    outcome = ActionOutcome.OUTCOME_UNKNOWN
                )
        } catch (e: CancellationException) {
            // Coroutine cancellation must stop device actions immediately.
            // F16: release the in-flight ledger key so a later identical
            // call this turn is not blocked by the cancelled execution.
            ledger?.release(name, args)
            throw e
        } catch (e: TaskStoppedException) {
            // The explicit user stop control is not an ordinary tool failure.
            ledger?.release(name, args)
            throw e
        } catch (e: Exception) {
            // An exception can happen after a tap or send has already reached
            // the target app. Conservatively make mutations terminal for this
            // turn, so neither the ledger nor the model retries an uncertain
            // side effect. Read-only failures remain ordinary retryable errors.
            if (ActionRiskPolicy.shouldTreatFailureAsOutcomeUnknown(name)) {
                ToolResult(
                    success = false,
                    message = "OUTCOME_UNKNOWN — Tool $name stopped unexpectedly " +
                        "(${e.javaClass.simpleName}). Check the app/device before retrying.",
                    outcome = ActionOutcome.OUTCOME_UNKNOWN
                )
            } else {
                // Never forward exception messages; they can contain private
                // URLs, contact details, request fragments, or provider data.
                ToolResult(false, "Tool $name failed safely (${e.javaClass.simpleName}).")
            }
        }
        // Keep action status authoritative for every caller, including
        // direct ToolResult paths that don't pass through ActionResult mapper.
        // Staged/uncertain outcomes must never enter the success macro recorder,
        // undo stack, or success-cached ledger.
        val terminalOutcome = dispatched.outcome in setOf(
            ActionOutcome.STAGED_FOR_USER,
            ActionOutcome.SUBMITTED_UNVERIFIED,
            ActionOutcome.OUTCOME_UNKNOWN
        )
        val raw = if (terminalOutcome && dispatched.success) {
            dispatched.copy(success = false)
        } else {
            dispatched
        }

        // v2.0.0 (#16): feed the turn recorder — successful executions
        // only, so a saved macro replays a sequence that worked. The
        // recorder is armed solely by AgentPipeline for its own turns;
        // replaying a macro re-enters execute() but the pipeline does
        // not arm the recorder then, so macros never record themselves.
        if (raw.success) {
            MacroRecorder.record(name, args.toString())
        } else {
            MacroRecorder.markFailure()
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
        // F18: type_text went through the repeatable gate, so its
        // record must still run to clear the in-flight mark
        // (activeLedger is null for repeatables).
        (activeLedger ?: ledger?.takeIf { name == "type_text" })?.record(name, args, raw)
        // F18: record sends that happened (or likely happened) so a
        // cross-turn retry of the identical message is refused above.
        // FAILED (no evidence of submission) is never recorded — a
        // failed send must stay retryable.
        if (sendGuardKey != null &&
            (raw.success || raw.outcome == ActionOutcome.SUBMITTED_UNVERIFIED ||
                raw.outcome == ActionOutcome.OUTCOME_UNKNOWN)
        ) {
            RecentSends.record(appContext, sendGuardKey)
        }
        if (!includeScreenSummary || name == "inspect_screen" ||
            name == "see_screen" || name == "vision_tap" ||
            name == "take_screenshot" || name == "read_page" ||
            name == "clipboard_get"
        ) return withUndoHint(name, raw)
        val service = JarvisAccessibilityService.instance
            ?: return withUndoHint(name, raw)
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
            withUndoHint(
                name,
                raw.copy(message = raw.message + "\nScreen now:\n" + summary)
            )
        } catch (e: Exception) {
            withUndoHint(name, raw)
        }
    }

    /**
     * v2.0.0: per-step undo hint — when a step's action is one of the
     * reversible kinds (#41 records an inverse for exactly these),
     * the result tells the model — and through it the user — that
     * "undo that" will revert it. Sends and playback are never
     * hinted: they cannot be undone and must not pretend otherwise.
     */
    private fun withUndoHint(name: String, result: ToolResult): ToolResult {
        if (!result.success || name !in UNDOABLE_TOOLS) return result
        return result.copy(
            message = result.message +
                " (Reversible: the user can say \"undo that\" to revert this step.)"
        )
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
                "vision-fallback wanted but no screenshot source is available — degrading to normal targeting"
            )
            return null
        }
        visionAssistsUsed++
        consecutiveTargetFailures = 0
        com.nshd.jarvis2.core.AppLog.i(
            "Hands",
            "vision-fallback used (assist $visionAssistsUsed/$MAX_VISION_ASSISTS) " +
                "for grounded target selection"
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
            "scroll" -> args.requiredString("direction")?.let { direction ->
                // F18: default is exactly ONE swipe. count > 1 only
                // when the user explicitly said a number — one tool
                // call, so the ledger can't double-fire it and the
                // model never needs a second scroll call for it.
                val count = args.optInt("count", 1).coerceIn(1, 5)
                var result = HandsActions.scroll(direction).toToolResult()
                var done = 1
                while (done < count && result.success) {
                    HandsActions.waitForSettle(600)
                    result = HandsActions.scroll(direction).toToolResult()
                    done++
                }
                result
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
            "instagram_inbox" -> intents.instagramInbox().toToolResult()
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
            // ── v2.0.0 "Brain Upgrade" ────────────────────────────
            "whatsapp_group_message" -> whatsappGroupMessage(args)
            "fill_form" -> fillForm()
            "wait_for_text" -> waitForText(args)
            // Internal quick-command tools (not in the model registry).
            "download_url" -> args.requiredString("url")?.let {
                downloadUrl(it)
            } ?: missing("url")
            "call_answer" -> callControl(answer = true)
            "call_reject" -> callControl(answer = false)
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
            ToolResult(
                false,
                "The screenshot was captured but could not be saved (${e.javaClass.simpleName})."
            )
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
        // Never persist actual source snippets in diagnostics; the
        // code may contain credentials, internal URLs, or private data.
        com.nshd.jarvis2.core.AppLog.i(
            "Tools",
            "insert_code: $lineCount lines / ${code.length} chars, " +
                "autoOpened=$autoOpened, success=${result.success}"
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

    /**
     * F18: cross-turn duplicate-send guard key, or null for non-send
     * tools. Channel is part of the key (same text via WhatsApp and
     * SMS are different sends); recipient is normalized, and only a
     * SHA-256 of the normalized text is kept — message text itself
     * is never stored (see [RecentSends]).
     */
    private fun sendGuardKey(name: String, args: JSONObject): String? {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        fun hash(s: String) = RecentSends.sha256(s)
        return when (name) {
            "whatsapp_message", "instagram_message", "telegram_message", "sms_message" -> {
                val contact = args.optString("contact").takeIf { it.isNotBlank() } ?: return null
                val message = args.optString("message").takeIf { it.isNotBlank() } ?: return null
                "$name|${norm(contact)}|${hash(norm(message))}"
            }
            "whatsapp_group_message" -> {
                val group = args.optString("group").takeIf { it.isNotBlank() } ?: return null
                val message = args.optString("message").takeIf { it.isNotBlank() } ?: return null
                "$name|${norm(group)}|${hash(norm(message))}"
            }
            "whatsapp_reply_current" -> {
                val message = args.optString("message").takeIf { it.isNotBlank() } ?: return null
                "$name|current|${hash(norm(message))}"
            }
            "whatsapp_photo" -> {
                val contact = args.optString("contact").takeIf { it.isNotBlank() } ?: return null
                "$name|${norm(contact)}|photo"
            }
            "gmail_send" -> {
                val to = args.optString("to").takeIf { it.isNotBlank() } ?: return null
                val subject = args.optString("subject") ?: ""
                val body = args.optString("body").takeIf { it.isNotBlank() } ?: return null
                "$name|${norm(to)}|${hash(norm(subject) + "\n\n" + norm(body))}"
            }
            else -> null
        }
    }

    private suspend fun whatsappMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        // F7: the send chain is stage-logged end to end (tag "Send")
        // so a "not sending" report can be traced to the exact stage:
        // routed → resolved → opened → typed → verified → asked → tapped → verified.
        com.nshd.jarvis2.core.AppLog.i(
            "Send", "routed: whatsapp_message (${message.length} chars)"
        )
        // F7 plan: the panel walks these steps; "Confirm & send"
        // (step 3) is the RUNNING step while the just-in-time send
        // ask waits, so the user sees WHY the task is paused.
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
                    "Send", "contact resolved for WhatsApp send (unique match)"
                )
                // v2.0.2: NO upfront gate — the old
                // whatsapp_message-always-ConfirmGate special case is
                // DELETED. The just-in-time ask runs inside
                // DeepIntents.whatsappMessage AFTER the chat opens and
                // the field is verified, BEFORE the send tap. YES here
                // grants the F7 SendPermit, so the approved send tap
                // does not hit the tap guard's second gate (the F3
                // double-gate that stalled sends). One permit per
                // package variant; the guard consumes exactly one.
                com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(3)
                if (SendAsk.isPending) {
                    return ToolResult(
                        false,
                        "Another send confirmation is already waiting — " +
                            "answer that one first. Nothing was sent."
                    )
                }
                val result = intents.whatsappMessage(
                    phone = resolution.contact.phone,
                    expectedContactName = resolution.contact.displayName,
                    text = message
                ) {
                    val approved = SendAsk.ask(
                        "$name (${resolution.contact.phone})", message
                    )
                    // DeepIntents grants a short-lived permit for the
                    // exact foreground package immediately before the send tap.
                    approved
                }
                // Preserve staged/unverified/unknown action outcomes from
                // DeepIntents; converting them to a plain Boolean result would
                // let the caller misunderstand delivery or retry the send.
                result.toToolResult()
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

    /** #43: send the latest gallery photo on WhatsApp — v2.0.2: the
     * just-in-time ask runs inside the share flow, after the photo is
     * attached and before the Send tap (never an upfront card). */
    private suspend fun whatsappPhoto(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        if (SendAsk.isPending) {
            return ToolResult(
                false,
                "Another send confirmation is already waiting — " +
                    "answer that one first. Nothing was sent."
            )
        }
        return when (val resolution = contactsResolver.resolve(contact)) {
            is ContactResolution.Found -> {
                val name = resolution.contact.displayName
                intents.whatsappShareLatestPhoto(
                    phone = resolution.contact.phone,
                    expectedContactName = resolution.contact.displayName
                ) {
                    SendAsk.ask(
                        "$name (${resolution.contact.phone})",
                        "Your most recent gallery photo"
                    )
                }.toToolResult()
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
        if (SendAsk.isPending) {
            return ToolResult(
                false,
                "Another send confirmation is already waiting — " +
                    "answer that one first. Nothing was sent."
            )
        }
        // v2.0.2: fill the open chat FIRST, then the just-in-time ask
        // inside typeAndSendInOpenChat, then the send tap.
        return intents.typeAndSendInOpenChat(pkg, message) {
            SendAsk.ask(title, message)
        }.toToolResult()
    }

    /** #45: Instagram DM — always-confirm rule, same as WhatsApp,
     * v2.0.2 via the just-in-time ask inside the DM flow (after the
     * thread opens + the text is typed, before the send tap). */
    private suspend fun instagramMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        if (SendAsk.isPending) {
            return ToolResult(
                false,
                "Another send confirmation is already waiting — " +
                    "answer that one first. Nothing was sent."
            )
        }
        return intents.instagramDm(contact, message) {
            SendAsk.ask(contact, message)
        }.toToolResult()
    }

    /** #46: Telegram message — always-confirm rule, same as WhatsApp,
     * v2.0.2 via the just-in-time ask inside the message flow. */
    private suspend fun telegramMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        if (SendAsk.isPending) {
            return ToolResult(
                false,
                "Another send confirmation is already waiting — " +
                    "answer that one first. Nothing was sent."
            )
        }
        return intents.telegramMessage(contact, message) {
            SendAsk.ask(contact, message)
        }.toToolResult()
    }

    /** #47: SMS via the default SMS app — resolved contact + gate first. */
    private suspend fun smsMessage(args: JSONObject): ToolResult {
        val contact = args.requiredString("contact") ?: return missing("contact")
        val message = args.requiredString("message") ?: return missing("message")
        if (ContactsResolver.looksLikePhone(contact)) {
            val number = ContactsResolver.normalizePhone(contact)
            if (SendAsk.isPending) {
                return ToolResult(
                    false,
                    "Another send confirmation is already waiting — " +
                        "answer that one first. Nothing was sent."
                )
            }
            // v2.0.2: the just-in-time ask runs inside smsSend, after
            // the composer opens, before the Send tap.
            return intents.smsSend(number, message) {
                SendAsk.ask(number, message)
            }.toToolResult()
        }
        return when (val resolution = contactsResolver.resolve(contact)) {
            is ContactResolution.Found -> {
                val name = resolution.contact.displayName
                if (SendAsk.isPending) {
                    return ToolResult(
                        false,
                        "Another send confirmation is already waiting — " +
                            "answer that one first. Nothing was sent."
                    )
                }
                intents.smsSend(resolution.contact.phone, message) {
                    SendAsk.ask("$name (${resolution.contact.phone})", message)
                }.toToolResult()
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

    /** #48: Gmail send — v2.0.2: the just-in-time ask inside
     * gmailSend shows recipient + subject + body after compose is
     * filled, before the Send tap. */
    private suspend fun gmailSend(args: JSONObject): ToolResult {
        val to = args.requiredString("to") ?: return missing("to")
        val subject = args.requiredString("subject") ?: return missing("subject")
        val body = args.requiredString("body") ?: return missing("body")
        if (SendAsk.isPending) {
            return ToolResult(
                false,
                "Another send confirmation is already waiting — " +
                    "answer that one first. Nothing was sent."
            )
        }
        return intents.gmailSend(to, subject, body) {
            SendAsk.ask(to, "Subject: $subject\n\n$body")
        }.toToolResult()
    }

    // ── v2.0.0 tool implementations ──────────────────────────────────

    /**
     * #49: group broadcast — one message to a named WhatsApp group.
     * The gate names the GROUP and shows the full text (per-group
     * confirm); the send tap runs under the usual one-shot permit.
     */
    private suspend fun whatsappGroupMessage(args: JSONObject): ToolResult {
        val group = args.requiredString("group") ?: return missing("group")
        val message = args.requiredString("message") ?: return missing("message")
        com.nshd.jarvis2.core.AppLog.i(
            "Send", "routed: whatsapp_group_message"
        )
        if (SendAsk.isPending) {
            return ToolResult(
                false,
                "Another send confirmation is already waiting — " +
                    "answer that one first. Nothing was sent."
            )
        }
        // v2.0.2: the just-in-time ask runs inside the group flow,
        // after the group chat opens + the text is typed, before the
        // send tap (the per-group confirm, same substance, new timing).
        return intents.whatsappGroupMessage(group, message) {
            SendAsk.ask("the group \"$group\"", message)
        }.toToolResult()
    }

    /**
     * #38: form-filling. Detect the editable fields on screen,
     * propose a value for each from Memory Core, show ALL proposals
     * in ONE confirm gate, then fill only the approved set. Fields
     * with text already in them are never overwritten.
     */
    private suspend fun fillForm(): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(
                false,
                "Accessibility is not enabled. Enable Jarvis Digital Hands in Permissions → Accessibility Service first."
            )
        val fields = service.screenReader().snapshot()
            .filter { it.editable && it.enabled }
        if (fields.isEmpty()) {
            return ToolResult(false, "No editable form fields are visible on this screen.")
        }
        val facts = memoryDb.allFacts(null, null)
        data class Proposal(
            val index: Int,
            val label: String,
            val value: String
        )
        val proposals = mutableListOf<Proposal>()
        val skipped = mutableListOf<String>()
        for (field in fields) {
            val label = field.label.ifBlank { field.desc }
                .ifBlank { "field #${field.index}" }
            if (field.text.isNotBlank()) {
                skipped += "\"$label\" (already filled)"
                continue
            }
            val value = proposeFormValue(label, facts)
            if (value == null) {
                skipped += "\"$label\" (nothing saved matches)"
            } else {
                proposals += Proposal(field.index, label, value)
            }
        }
        if (proposals.isEmpty()) {
            return ToolResult(
                true,
                "I found ${fields.size} field(s) but no saved memory matches " +
                    "any of them, so nothing was filled." +
                    if (skipped.isNotEmpty()) {
                        " Skipped: ${skipped.joinToString("; ")}."
                    } else {
                        ""
                    }
            )
        }
        val gateText = buildString {
            append("Fill this form from your saved memories?\n")
            proposals.forEach { p ->
                append("\n• ${p.label} → \"${p.value}\"")
            }
            if (skipped.isNotEmpty()) {
                append("\n\nLeft empty: ${skipped.joinToString("; ")}")
            }
        }
        val approved = ConfirmGate.confirm(gateText)
        if (!approved) {
            return ToolResult(false, "Form fill was not confirmed — nothing was typed.")
        }
        var filled = 0
        for (proposal in proposals) {
            // Re-check the stop flag before every field (#61).
            com.nshd.jarvis2.hands.TaskState.throwIfStopRequested()
            val tapped = HandsActions.clickElement(proposal.index)
            if (!tapped.success) continue
            HandsActions.waitForSettle(300)
            val typed = HandsActions.setTextOnFocused(proposal.value)
            if (typed.success) filled++
        }
        com.nshd.jarvis2.core.AppLog.i(
            "Tools", "fill_form: filled $filled/${proposals.size} fields"
        )
        return ToolResult(
            filled > 0,
            "Filled $filled of ${proposals.size} field(s) from your saved memories."
        )
    }

    /** #38: pick a value for a field label from Memory Core facts. */
    private fun proposeFormValue(
        label: String,
        facts: List<com.nshd.jarvis2.memory.Fact>
    ): String? {
        val lower = label.lowercase()
        fun factTextContaining(vararg needles: String): String? =
            facts.firstOrNull { fact ->
                needles.any { fact.text.contains(it, ignoreCase = true) }
            }?.text
        return when {
            lower.contains("email") || lower.contains("e-mail") -> {
                val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
                facts.firstNotNullOfOrNull { fact ->
                    emailRegex.find(fact.text)?.value
                }
            }
            lower.contains("phone") || lower.contains("mobile") ||
                lower.contains("contact number") -> {
                val phoneRegex = Regex("\\+?[0-9][0-9\\s-]{6,}[0-9]")
                facts.firstNotNullOfOrNull { fact ->
                    phoneRegex.find(fact.text)?.value?.trim()
                }
            }
            lower.contains("name") -> {
                val fact = factTextContaining("my name is", "name is")
                fact?.let {
                    val idx = it.lowercase().indexOf("name is")
                    if (idx >= 0) {
                        it.substring(idx + "name is".length).trim()
                            .trimEnd('.').take(60)
                    } else {
                        null
                    }
                }
            }
            lower.contains("address") -> {
                factTextContaining("address", "live in", "living in")?.let { fact ->
                    fact.substringAfter(":", fact).trim().take(120)
                }
            }
            lower.contains("city") -> {
                factTextContaining("live in", "living in")?.let { fact ->
                    val idx = fact.lowercase().indexOf("live in")
                    if (idx >= 0) {
                        fact.substring(idx + "live in".length).trim()
                            .trimEnd('.').take(40)
                    } else {
                        null
                    }
                }
            }
            else -> null
        }
    }

    /**
     * v2.0.0: wait until [text] appears on screen (poll the a11y
     * snapshot every 500 ms, up to the timeout). Read-only — used to
     * pace multi-step flows on slow screens instead of guessing.
     */
    private suspend fun waitForText(args: JSONObject): ToolResult {
        val text = args.requiredString("text") ?: return missing("text")
        val timeoutSec = args.optInt("timeout_seconds", 15).coerceIn(1, 30)
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(
                false,
                "Accessibility is not enabled. Enable Jarvis Digital Hands in Permissions → Accessibility Service first."
            )
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            com.nshd.jarvis2.hands.TaskState.throwIfStopRequested()
            polls++
            val found = service.screenReader().snapshot().any { element ->
                element.text.contains(text, ignoreCase = true) ||
                    element.desc.contains(text, ignoreCase = true)
            }
            if (found) {
                com.nshd.jarvis2.core.AppLog.d(
                    "Tools", "wait_for_text target matched after $polls poll(s) (${text.length} chars)"
                )
                return ToolResult(true, "\"$text\" is now on screen.")
            }
            delay(500)
        }
        return ToolResult(
            false,
            "\"$text\" did not appear within $timeoutSec seconds."
        )
    }

    /** #111: download a link into Downloads via DownloadManager. */
    private fun downloadUrl(url: String): ToolResult {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return ToolResult(false, "That doesn't look like a web link — nothing downloaded.")
        }
        val manager = appContext.getSystemService(DownloadManager::class.java)
            ?: return ToolResult(false, "The system download service is unavailable.")
        val fileName = Uri.parse(url).lastPathSegment
            ?.takeIf { it.isNotBlank() && it.contains('.') }
            ?: "jarvis-download"
        return try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloaded by Jarvis")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName
                )
            manager.enqueue(request)
            com.nshd.jarvis2.core.AppLog.i(
                "Tools", "download_url: request enqueued"
            )
            ToolResult(
                true,
                "Downloading $fileName to your Downloads folder — " +
                    "Android will notify you when it finishes."
            )
        } catch (e: Exception) {
            ToolResult(
                false,
                "The download couldn't start (${e.javaClass.simpleName}). Check the link and available storage."
            )
        }
    }

    /**
     * #112: answer / reject the ringing call via TelecomManager.
     * Both are the user's explicit command, so neither is gated
     * (the plan keeps reject gate-free; answering is equally his own
     * instruction). Requires the ANSWER_PHONE_CALLS runtime grant.
     */
    private fun callControl(answer: Boolean): ToolResult {
        if (ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ANSWER_PHONE_CALLS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                false,
                "I need the \"Answer calls\" permission first — grant it in " +
                    "Permissions so I can answer or reject calls by voice."
            )
        }
        val telecom = appContext.getSystemService(TelecomManager::class.java)
            ?: return ToolResult(false, "The phone service is unavailable right now.")
        return try {
            if (answer) {
                @Suppress("DEPRECATION")
                telecom.acceptRingingCall()
                ToolResult(
                    success = false,
                    message = "The answer command was issued, but Jarvis cannot verify the call is connected. Check the phone screen before retrying.",
                    outcome = ActionOutcome.SUBMITTED_UNVERIFIED
                )
            } else {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return ToolResult(
                        false,
                        "Rejecting a call by voice requires Android 9 or newer on this device. Use the phone screen instead."
                    )
                }
                val ended = telecom.endCall()
                if (ended) {
                    ToolResult(true, "Call rejected.")
                } else {
                    ToolResult(false, "No ringing or active call to reject.")
                }
            }
        } catch (e: SecurityException) {
            ToolResult(
                false,
                "Android refused the call action — the \"Answer calls\" permission may have been revoked."
            )
        } catch (e: Exception) {
            ToolResult(
                false,
                if (answer) {
                    "No ringing call to answer right now."
                } else {
                    "The call couldn't be rejected (${e.javaClass.simpleName})."
                }
            )
        }
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

    private fun ActionResult.toToolResult(): ToolResult {
        val statusPrefix = when (outcome) {
            ActionOutcome.STAGED_FOR_USER -> "STAGED_FOR_USER"
            ActionOutcome.SUBMITTED_UNVERIFIED -> "SUBMITTED_UNVERIFIED"
            ActionOutcome.OUTCOME_UNKNOWN -> "OUTCOME_UNKNOWN"
            else -> null
        }
        val resultMessage = if (statusPrefix != null) {
            "$statusPrefix — $message"
        } else {
            message
        }
        val outcomeIsTerminal = outcome in setOf(
            ActionOutcome.STAGED_FOR_USER,
            ActionOutcome.SUBMITTED_UNVERIFIED,
            ActionOutcome.OUTCOME_UNKNOWN
        )
        return ToolResult(
            // A staged/uncertain result can never be promoted to success
            // merely because an underlying helper returned an optimistic bool.
            success = success && !outcomeIsTerminal,
            message = resultMessage,
            outcome = outcome
        )
    }

    private fun isPaymentLike(text: String): Boolean =
        PAYMENT_REGEX.containsMatchIn(text.lowercase())

    private fun containsOtpPattern(text: String): Boolean =
        OTP_REGEX.containsMatchIn(text.lowercase()) ||
            CARD_NUMBER_REGEX.containsMatchIn(text)

    companion object {
        /** Hang-proofing: one tool dispatch may never hang a turn past this. */
        private const val TOOL_TIMEOUT_MS = 60_000L
        private const val MAX_UNCONFIRMED_BULK = 5
        /** F8 (#21): a bigger diff than this falls back to full compact. */
        private const val DIFF_ENTRY_CAP = 40
        /** F8 (#30): vision assists allowed per agent turn. */
        private const val MAX_VISION_ASSISTS = 2
        private const val QUICKEDIT_PACKAGE = "com.rhmsoft.edit"
        /** Read-only screen observation — exempt from the F3 ledger. */
        internal val OBSERVATION_TOOLS = setOf(
            "inspect_screen", "see_screen", "take_screenshot",
            // v1.9.0: SAFE reads (page text / clipboard) — always fresh.
            "read_page", "clipboard_get",
            // v2.0.0: waiting re-reads the CURRENT screen by design.
            "wait_for_text"
        )

        /** v1.9.0 (#28): apps whose transitions settle fast. */
        private val FAST_SETTLE_PACKAGES = setOf(
            "com.spotify.music", "com.google.android.youtube",
            "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android",
            "org.telegram.messenger"
        )

        /** v2.0.0: tools whose success has a recorded #41 inverse. */
        private val UNDOABLE_TOOLS = setOf(
            "type_text", "edit_text", "insert_code", "open_app"
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
        internal val REPEATABLE_TOOLS = setOf("edit_text", "type_text")
        // External side effects not already covered by UI_MUTATION_TOOLS.
        // Exceptions from these actions must not enable same-turn replay.
        private val EXTERNAL_SIDE_EFFECT_TOOLS = setOf(
            "call_contact", "answer_call", "reject_call", "set_alarm", "set_timer",
            "set_volume", "set_brightness", "toggle_torch", "set_silent",
            "media_control", "download_url", "calendar_event"
        )

        private val UI_MUTATION_TOOLS = setOf(
            "open_app", "click_element", "click_text", "type_text", "scroll",
            "press_key", "tap_percent", "whatsapp_message", "youtube_play",
            "spotify_play", "edit_text", "insert_code",
            // v1.9.0 mutations (gestures, clipboard writes, new sends).
            "long_press", "double_tap", "drag", "pinch_zoom", "set_slider",
            "undo_action", "clipboard_set", "copy_selection", "paste_clipboard",
            "whatsapp_photo", "whatsapp_reply_current", "instagram_message",
            "telegram_message", "sms_message", "gmail_send", "share_screenshot",
            // v2.0.0 mutations.
            "whatsapp_group_message", "fill_form"
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

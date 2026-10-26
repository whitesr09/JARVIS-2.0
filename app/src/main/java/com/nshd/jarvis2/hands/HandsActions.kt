package com.nshd.jarvis2.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.nshd.jarvis2.brain.tools.ConfirmGate
import com.nshd.jarvis2.brain.tools.SendAsk
import com.nshd.jarvis2.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.math.abs

enum class ActionOutcome {
    /** The requested action was completed to the level stated in its result. */
    COMPLETED,
    /** The relevant composer/draft is open, but Jarvis did not submit it. */
    STAGED_FOR_USER,
    /** A send was submitted and visible UI evidence appeared; delivery is not confirmed. */
    SUBMITTED_UNVERIFIED,
    /** The action may have happened, but the app cannot prove its final state. */
    OUTCOME_UNKNOWN,
    /** The action did not complete and there is no evidence it was submitted. */
    FAILED
}

data class ActionResult(
    val success: Boolean,
    val message: String,
    val outcome: ActionOutcome = if (success) ActionOutcome.COMPLETED else ActionOutcome.FAILED
)

/** Resolved editable target + everything the edit/insert paths need (F6). */
private class EditableTarget(
    val node: AccessibilityNodeInfo,
    val descriptor: UiElement,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val isSearchField: Boolean
)

/**
 * The single mutation gate for accessibility actions.
 *
 * Every public action is serialised through one mutex and bounded to 15
 * seconds. Click handling deliberately never performs both an
 * accessibility ACTION_CLICK and a gesture tap for the same target: the
 * gesture is used only when no clickable node accepts the action.
 */
object HandsActions {
    private val mutex = Mutex()

    // F6 speed pass constants: settle polling cadence and the short
    // read-back pause after SET_TEXT (was 180 ms, now 100 ms — the
    // read-back itself is the verification, the pause only lets the
    // editor commit the change).
    private const val SETTLE_POLL_MS = 100L
    private const val VERIFY_DELAY_MS = 100L

    // F6 insert_code chunking: above ~6000 inserted characters the
    // code is applied in ~3000-character chunks (SET_TEXT on the
    // growing content) to stay under OEM editor limits.
    private const val CHUNK_THRESHOLD = 6_000
    private const val CHUNK_SIZE = 3_000
    private const val LENGTH_TOLERANCE = 4

    // SensitiveTapGuard (F3) constants.
    private const val OWN_PACKAGE = "com.nshd.jarvis2"
    private val SOCIAL_PHRASES = listOf(
        "delete request", "remove follower", "accept request", "confirm request",
        "follow back", "unfollow", "reject", "block"
    )
    private val SEND_PHRASES = listOf(
        "share now", "send now", "send", "post", "publish", "comment", "tweet"
    )
    private val DESTRUCTIVE_PHRASES = listOf(
        "clear all", "delete", "remove", "erase", "discard"
    )
    private val NAV_LABELS = setOf(
        "back", "home", "recents", "recent apps", "notifications",
        "search", "explore", "reels", "profile", "library", "subscriptions"
    )

    /** F8 (#31): scroll-and-search budget for one find. */
    private const val MAX_SEARCH_SCROLLS = 4

    // ── F7 SendPermit ────────────────────────────────────────────────
    //
    // The send tools run their OWN just-in-time SendAsk (v2.0.2:
    // every send confirms with recipient + full text right before
    // the tap — his standing rule). The
    // deep intent then taps WhatsApp's Send button, whose label
    // matches the guard's SEND class — so a SECOND gate fired for the
    // same already-approved send, in a context (WhatsApp foreground,
    // voice session) where the prompt was often invisible: the 30 s
    // timeout denied the tap and the message never sent ("it is not
    // sending messages"). The fix: after the tool's gate approves,
    // ToolExecutor issues ONE permit; the guard consumes it for
    // exactly one SEND-class tap in the permitted package. Permits
    // never cover SOCIAL/DESTRUCTIVE phrases, expire in ~30 s, and a
    // second send tap gates again as usual.

    /** Exactly one confirmed package may consume the next send permit. */
    private var sendPermitPackage: String? = null

    @Volatile
    private var sendPermitExpiresAtMs: Long = 0L

    /** Issue a one-shot permit for the exact app containing the confirmed composer. */
    @Synchronized
    fun grantSendPermit(packageName: String, ttlMs: Long = 5_000L) {
        if (packageName.isBlank() || ttlMs <= 0L) {
            sendPermitPackage = null
            sendPermitExpiresAtMs = 0L
            return
        }
        sendPermitPackage = packageName
        sendPermitExpiresAtMs = System.currentTimeMillis() + ttlMs
        com.nshd.jarvis2.core.AppLog.i(
            "Send",
            "one-shot permit granted (5s max, package-scoped)"
        )
    }

    /** Revoke an unused permit when the associated tap exits without consuming it. */
    @Synchronized
    fun revokeSendPermit(packageName: String? = null) {
        if (packageName == null || sendPermitPackage == packageName) {
            sendPermitPackage = null
            sendPermitExpiresAtMs = 0L
        }
    }

    /** Consume the permit once, and only in the exact confirmed package. */
    @Synchronized
    private fun consumeSendPermit(packageName: String?): Boolean {
        val permittedPackage = sendPermitPackage ?: return false
        if (System.currentTimeMillis() > sendPermitExpiresAtMs ||
            packageName == null || packageName != permittedPackage
        ) {
            // Do not allow an unused permit to survive an app/package switch.
            sendPermitPackage = null
            sendPermitExpiresAtMs = 0L
            com.nshd.jarvis2.core.AppLog.d("Send", "one-shot permit expired or package mismatch")
            return false
        }
        sendPermitPackage = null
        sendPermitExpiresAtMs = 0L
        return true
    }

    suspend fun clickElement(index: Int): ActionResult = serializedLong {
        val service = serviceOrNull() ?: return@serializedLong disabled()
        val element = JarvisAccessibilityService.lastSnapshot.getOrNull(index)
            ?: return@serializedLong ActionResult(
                false,
                "Element #$index is not in the last screen snapshot. Call inspect_screen first."
            )
        // SensitiveTapGuard (F3): runs inside the serialized executor.
        sensitiveTapDenial(service, element, element.label)?.let { return@serializedLong it }
        clickResolved(service, element, "element #$index (${element.label.ifBlank { "unlabelled" }})")
    }

    suspend fun clickText(text: String): ActionResult = serializedLong {
        val service = serviceOrNull() ?: return@serializedLong disabled()
        val reader = service.screenReader()
        // F8 (#29): the self-healing strategy chain finds the target;
        // the winning strategy is logged so a layout change at the
        // app side shows up as a strategy shift, not a silent failure.
        // F8 (#31): when the target isn't on screen, scroll-and-search
        // (up to 4 scrolls: down ×3 revealing lower content, then one
        // back up) re-snapshotting after each scroll.
        var found = reader.findSmart(text, packageName = reader.currentPackage())
        var scrollsUsed = 0
        while (found == null && scrollsUsed < MAX_SEARCH_SCROLLS) {
            val direction = if (scrollsUsed < 3) "down" else "up"
            scrollInternal(direction)
            scrollsUsed++
            found = reader.findSmart(text, packageName = reader.currentPackage())
        }
        if (scrollsUsed > 0) {
            com.nshd.jarvis2.core.AppLog.i(
                "Hands",
                "scroll-search for \"$text\": $scrollsUsed scroll(s) used, " +
                    if (found != null) "found" else "still not found"
            )
        }
        val element = found?.element
            ?: return@serializedLong ActionResult(false, "No visible element matched \"$text\".")
        com.nshd.jarvis2.core.AppLog.d(
            "Hands", "find strategy=${found?.strategy ?: "none"} for \"$text\""
        )
        sensitiveTapDenial(service, element, text)?.let { return@serializedLong it }
        clickResolved(service, element, "\"${element.label.ifBlank { text }}\"")
    }

    /**
     * F6 (Part D): dictation semantics. Before F6 this REPLACED the
     * whole field (SET_TEXT with the chunk alone, verified by exact
     * equality) — dictating into an editor destroyed what was already
     * written, and there was no way to delete. Now the text is
     * INSERTED at the selection end (appended at the end when no
     * selection is known); SEARCH fields keep replace semantics (a
     * new search should not concatenate with the old one). Entry is
     * still verified by reading the field back. Deletion lives in
     * [editTextOnFocused].
     */
    suspend fun setTextOnFocused(text: String): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        if (text.isBlank()) return@serialized ActionResult(false, "Text to type is blank.")
        val root = service.activeRoot()
            ?: return@serialized ActionResult(false, "No active window is available.")
        val resolvedTarget = resolveEditableTarget(root)
            ?: return@serialized ActionResult(false, "No editable text field was found on this screen.")
        // #35: scroll an IME-covered field into view first, then use
        // the re-fetched target (insert-at-selection below is unchanged).
        val target = ensureEditableVisible(service, resolvedTarget)
        val existing = target.text
        val replace = target.isSearchField || existing.isBlank()
        val insertAt = when {
            replace -> 0
            target.selectionEnd in 0..existing.length -> target.selectionEnd
            else -> existing.length
        }
        val newText = if (replace) {
            text
        } else {
            existing.substring(0, insertAt) + text + existing.substring(insertAt)
        }
        if (existing == newText) {
            return@serialized ActionResult(
                true,
                "The field already contains the exact requested text; nothing was typed again."
            )
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val accepted = target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        delay(VERIFY_DELAY_MS)
        var readBack = freshTarget(service, target.descriptor)?.text.orEmpty()
        if (accepted && readBack == newText) {
            return@serialized ActionResult(
                true,
                if (replace) "Text entered and verified." else "Text inserted and verified."
            )
        }

        // Verified paste fallback: only used after SET_TEXT failed to
        // produce the exact value. Paste inserts at the cursor, which
        // matches insert semantics directly; in replace mode the old
        // content is selected first so the paste overwrites it.
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@serialized ActionResult(false, "Text entry could not be verified and clipboard is unavailable.")
        clipboard.setPrimaryClip(ClipData.newPlainText("JarvisInput", text))
        val pasteTarget = freshTarget(service, target.descriptor)
            ?: return@serialized ActionResult(false, "The text field disappeared before paste could be verified.")
        if (replace && pasteTarget.text.isNotEmpty()) {
            pasteTarget.node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                selectionArguments(0, pasteTarget.text.length)
            )
        }
        val pasted = pasteTarget.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        delay(VERIFY_DELAY_MS)
        readBack = freshTarget(service, target.descriptor)?.text.orEmpty()
        if (pasted && readBack == newText) {
            ActionResult(true, "Text entered with paste fallback and verified.")
        } else {
            ActionResult(
                false,
                "Text entry was not verified. The field contains \"$readBack\", not the expected text."
            )
        }
    }

    /** True when any editable node is resolvable on the active window. */
    fun focusedEditablePresent(): Boolean {
        val service = serviceOrNull() ?: return false
        val root = service.activeRoot() ?: return false
        return findEditable(root) != null
    }

    /**
     * F6 (Part D): delete text in the focused editable field on
     * command — the edit_text tool's engine. Computes the new content,
     * applies it with ACTION_SET_TEXT and verifies by reading back.
     * [action]: clear_all / delete_last_line / delete_last_word /
     * delete_last_chars ([count]) / delete_selection. Deliberately
     * NOT routed through the SensitiveTapGuard: this is the user's
     * own explicit delete command, not a UI tap.
     */
    suspend fun editTextOnFocused(action: String, count: Int): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val root = service.activeRoot()
            ?: return@serialized ActionResult(false, "No active window is available.")
        val target = resolveEditableTarget(root)
            ?: return@serialized ActionResult(
                false,
                "No text field is focused — tap into the editor first."
            )
        val text = target.text
        if (text.isEmpty()) {
            return@serialized ActionResult(true, "The field is already empty — nothing to delete.")
        }
        val newText: String
        val description: String
        when (action) {
            "clear_all" -> {
                newText = ""
                description = "Cleared all ${text.length} characters."
            }
            "delete_last_line" -> {
                val body = text.removeSuffix("\n")
                val idx = body.lastIndexOf('\n')
                newText = if (idx >= 0) body.substring(0, idx + 1) else ""
                val deleted = body.substring(if (idx >= 0) idx + 1 else 0)
                description = "Deleted the last line: '${preview(deleted)}'."
            }
            "delete_last_word" -> {
                val trimmed = text.trimEnd()
                if (trimmed.isEmpty()) {
                    return@serialized ActionResult(true, "Only whitespace was there — nothing to delete.")
                }
                val boundary = maxOf(
                    trimmed.lastIndexOf(' '),
                    trimmed.lastIndexOf('\n'),
                    trimmed.lastIndexOf('\t')
                )
                val cut = if (boundary >= 0) boundary else 0
                newText = trimmed.substring(0, cut)
                val deleted = trimmed.substring(cut).trim()
                description = "Deleted the last word: '${preview(deleted)}'."
            }
            "delete_last_chars" -> {
                val n = count.coerceIn(1, text.length)
                newText = text.dropLast(n)
                description = "Deleted the last $n characters: '${preview(text.takeLast(n))}'."
            }
            "delete_selection" -> {
                val start = target.selectionStart
                val end = target.selectionEnd
                if (start < 0 || end < 0 || start >= end || end > text.length) {
                    return@serialized ActionResult(
                        false,
                        "No text is selected in the field, so there is no " +
                            "selection to delete. Select the text first, or " +
                            "use clear_all / delete_last_line instead."
                    )
                }
                newText = text.removeRange(start, end)
                description = "Deleted the selected text: '${preview(text.substring(start, end))}'."
            }
            else -> return@serialized ActionResult(false, "Unknown edit action: $action")
        }
        if (newText == text) {
            return@serialized ActionResult(true, "Nothing changed — the field already matches.")
        }
        val accepted = target.node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
            }
        )
        delay(VERIFY_DELAY_MS)
        var readBack = freshTarget(service, target.descriptor)?.text
        if (accepted && readBack == newText) {
            com.nshd.jarvis2.core.AppLog.d(
                "Tools",
                "edit_text $action: ${text.length} → ${newText.length} chars"
            )
            return@serialized ActionResult(true, description)
        }
        // clear_all fallback: SELECT_ALL + CUT for editors that shrug
        // off SET_TEXT with an empty value.
        if (action == "clear_all") {
            val fresh = freshTarget(service, target.descriptor)
            if (fresh != null && fresh.text.isNotEmpty()) {
                fresh.node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    selectionArguments(0, fresh.text.length)
                )
                val cut = fresh.node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                delay(VERIFY_DELAY_MS)
                readBack = freshTarget(service, target.descriptor)?.text
                if (cut && readBack.isNullOrEmpty()) {
                    com.nshd.jarvis2.core.AppLog.d(
                        "Tools", "edit_text clear_all via CUT: ${text.length} chars removed"
                    )
                    return@serialized ActionResult(true, description)
                }
            }
        }
        ActionResult(
            false,
            "The edit could not be verified — the field still contains " +
                "${readBack?.length ?: 0} characters."
        )
    }

    /**
     * F6 (Part D2): insert COMPLETE code into the focused editable
     * field. Empty field → the code alone; otherwise the code goes at
     * the selection end (a selection range is replaced), appending at
     * the end when no selection is known, with a separating newline
     * when the preceding character isn't one. Code longer than
     * [CHUNK_THRESHOLD] is applied in ~[CHUNK_SIZE]-character chunks
     * (SET_TEXT on the growing content) to stay under OEM editor
     * limits. Verified by re-reading: success requires the exact
     * expected content, or — for editors that normalize the tail —
     * the content to end with the code's last non-blank line with the
     * expected length growth. One chunked retry on mismatch.
     */
    suspend fun insertCodeIntoFocused(code: String): ActionResult = serializedLong {
        val service = serviceOrNull() ?: return@serializedLong disabled()
        if (code.isBlank()) return@serializedLong ActionResult(false, "The code to insert is blank.")
        val root = service.activeRoot()
            ?: return@serializedLong ActionResult(false, "No active window is available.")
        val resolvedTarget = resolveEditableTarget(root)
            ?: return@serializedLong ActionResult(
                false,
                "No editable text field was found on this screen — tap into the editing area first."
            )
        // #35: same keyboard-cover fix on the code-insert path.
        val target = ensureEditableVisible(service, resolvedTarget)
        val existing = target.text
        val selStart = target.selectionStart
        val selEnd = target.selectionEnd
        val hasRange = selStart >= 0 && selEnd > selStart && selEnd <= existing.length
        val insertAt = when {
            hasRange -> selStart
            selEnd in 0..existing.length -> selEnd
            else -> existing.length
        }
        val prefix = existing.substring(0, insertAt)
        val suffix = if (hasRange) {
            existing.substring(selEnd)
        } else if (selEnd in 0..existing.length) {
            existing.substring(selEnd)
        } else {
            ""
        }
        val separator = if (prefix.isNotEmpty() && !prefix.endsWith("\n")) "\n" else ""
        val inserted = separator + code
        val expected = prefix + inserted + suffix
        val lineCount = code.lines().count()

        suspend fun verify(readBack: String?): Boolean {
            if (readBack == null) return false
            if (readBack == expected) return true
            // Editor tail normalization tolerance (trailing newline /
            // spaces): the code must be there, ending the content.
            val lastLine = code.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (lastLine.isEmpty()) return false
            val grew = readBack.length >= existing.length + code.length - LENGTH_TOLERANCE
            return grew && readBack.trimEnd().endsWith(lastLine) &&
                readBack.startsWith(prefix.take(40))
        }

        suspend fun setWhole(value: String): String? {
            val fresh = freshTarget(service, target.descriptor) ?: return null
            fresh.node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
            )
            delay(VERIFY_DELAY_MS)
            return freshTarget(service, target.descriptor)?.text
        }

        suspend fun applyChunked(): String? {
            // Grow the content chunk by chunk; each SET_TEXT carries
            // the full (growing) text, sized to stay editor-friendly.
            var current = prefix
            var readBack: String? = null
            for (chunk in chunkCode(inserted)) {
                val next = current + chunk + suffix
                readBack = setWhole(next) ?: break
                // Build the next chunk on the intended value; the
                // final verify judges the actual read-back.
                current += chunk
            }
            return readBack
        }

        var readBack = if (inserted.length <= CHUNK_THRESHOLD) {
            setWhole(expected)
        } else {
            applyChunked()
        }
        if (!verify(readBack)) {
            // One retry, always via the chunked path.
            readBack = applyChunked()
        }
        return@serializedLong if (verify(readBack)) {
            com.nshd.jarvis2.core.AppLog.i(
                "Tools",
                "insert_code ok: $lineCount lines / ${code.length} chars " +
                    "(field ${existing.length} → ${readBack?.length ?: -1} chars)"
            )
            ActionResult(
                true,
                "Inserted $lineCount lines of code into the editor."
            )
        } else {
            ActionResult(
                false,
                "The code insert could not be verified — the editor now " +
                    "holds ${readBack?.length ?: 0} characters; the code may " +
                    "be incomplete. Nothing was retried further."
            )
        }
    }

    /** ~40-char single-line preview for result strings and logs. */
    private fun preview(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length > 40) flat.take(39) + "…" else flat
    }

    private fun selectionArguments(start: Int, end: Int): Bundle = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
    }

    /** Split [text] into ~[CHUNK_SIZE]-char chunks at line boundaries. */
    private fun chunkCode(text: String): List<String> {
        if (text.length <= CHUNK_SIZE) return listOf(text)
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.length > CHUNK_SIZE) {
            val cut = rest.lastIndexOf('\n', CHUNK_SIZE)
            val at = if (cut > CHUNK_SIZE / 2) cut + 1 else CHUNK_SIZE
            chunks += rest.substring(0, at)
            rest = rest.substring(at)
        }
        if (rest.isNotEmpty()) chunks += rest
        return chunks
    }

    private fun resolveEditableTarget(root: AccessibilityNodeInfo): EditableTarget? {
        val node = findEditable(root) ?: return null
        return describeTarget(node)
    }

    private fun describeTarget(node: AccessibilityNodeInfo): EditableTarget {
        val viewId = node.viewIdResourceName.orEmpty()
        val hint = runCatching { node.hintText?.toString() }.getOrNull().orEmpty()
        val className = node.className?.toString().orEmpty()
        val isSearch = viewId.contains("search", ignoreCase = true) ||
            hint.contains("search", ignoreCase = true) ||
            className.contains("Search", ignoreCase = true)
        return EditableTarget(
            node = node,
            descriptor = node.toDescriptor(),
            text = node.text?.toString().orEmpty(),
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
            isSearchField = isSearch
        )
    }

    /** Re-resolve a previously described target on the CURRENT tree. */
    private fun freshTarget(
        service: JarvisAccessibilityService,
        descriptor: UiElement
    ): EditableTarget? {
        val root = service.activeRoot() ?: return null
        val node = resolveNode(root, descriptor) ?: findEditable(root) ?: return null
        return describeTarget(node)
    }

    suspend fun scroll(direction: String): ActionResult = serialized {
        scrollInternal(direction)
    }

    /**
     * F8: the scroll body WITHOUT the mutex — clickText's
     * scroll-and-search (#31) runs inside serializedLong and the one
     * HandsActions mutex is not reentrant.
     */
    private suspend fun scrollInternal(direction: String): ActionResult {
        val service = serviceOrNull() ?: return disabled()
        val reader = service.screenReader()
        val before = reader.signature()
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "down" -> listOf(width * 0.50f, height * 0.58f, width * 0.50f, height * 0.40f)
            "up" -> listOf(width * 0.50f, height * 0.42f, width * 0.50f, height * 0.60f)
            "left" -> listOf(width * 0.58f, height * 0.50f, width * 0.40f, height * 0.50f)
            "right" -> listOf(width * 0.42f, height * 0.50f, width * 0.60f, height * 0.50f)
            else -> return ActionResult(false, "Scroll direction must be up, down, left or right.")
        }
        val dispatched = dispatchSwipe(service, startX, startY, endX, endY, durationMs = 600)
        if (!dispatched) return ActionResult(false, "The scroll gesture was not dispatched.")
        // F6: 450 ms → 250 ms — the signature comparison right below
        // is the real change detector; the pause only lets the fling
        // animation start.
        delay(250)
        val changed = reader.signature() != before
        return ActionResult(
            changed,
            if (changed) "Scrolled ${direction.lowercase()} and the screen changed."
            else "Scroll gesture completed, but the screen did not change (possibly the end of the content)."
        )
    }

    /**
     * F8 (#15): lock the screen via the accessibility global action
     * (API 28+). Older APIs have no service-side lock — fail honestly.
     */
    suspend fun lockScreen(): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        if (android.os.Build.VERSION.SDK_INT <
            android.os.Build.VERSION_CODES.P
        ) {
            return@serialized ActionResult(
                false,
                "Locking the screen needs Android 9 or newer."
            )
        }
        val ok = service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        )
        com.nshd.jarvis2.core.AppLog.i("Hands", "lock screen (ok=$ok)")
        ActionResult(ok, if (ok) "Screen locked." else "The lock action was not accepted.")
    }

    // ── v1.9.0 (#32/#33): gesture tools ──────────────────────────────
    //
    // long-press / double-tap / drag / pinch / slider-set. All are
    // plain gesture dispatches through the same [dispatchSwipe]
    // plumbing as taps — no new guard surface (a coordinate gesture
    // carries no label; the SensitiveTapGuard story is unchanged
    // from tap_percent's).

    /** #32: press-and-hold at a percentage point. */
    suspend fun longPressPercent(
        xPercent: Double,
        yPercent: Double,
        durationMs: Long = 700
    ): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        if (xPercent !in 0.0..100.0 || yPercent !in 0.0..100.0) {
            return@serialized ActionResult(false, "Percentages must be between 0 and 100.")
        }
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * (xPercent / 100.0).toFloat()
        val y = metrics.heightPixels * (yPercent / 100.0).toFloat()
        val ok = dispatchSwipe(service, x, y, x, y, durationMs)
        com.nshd.jarvis2.core.AppLog.d("Hands", "long-press at $xPercent%, $yPercent% (ok=$ok)")
        ActionResult(ok, if (ok) "Long-pressed at $xPercent%, $yPercent%." else "Long-press was not dispatched.")
    }

    /** #32: two quick taps at a percentage point. */
    suspend fun doubleTapPercent(xPercent: Double, yPercent: Double): ActionResult =
        serialized {
            val service = serviceOrNull() ?: return@serialized disabled()
            if (xPercent !in 0.0..100.0 || yPercent !in 0.0..100.0) {
                return@serialized ActionResult(false, "Percentages must be between 0 and 100.")
            }
            val metrics = service.resources.displayMetrics
            val x = metrics.widthPixels * (xPercent / 100.0).toFloat()
            val y = metrics.heightPixels * (yPercent / 100.0).toFloat()
            val first = dispatchSwipe(service, x, y, x, y, durationMs = 50)
            delay(120)
            val second = dispatchSwipe(service, x, y, x, y, durationMs = 50)
            val ok = first && second
            com.nshd.jarvis2.core.AppLog.d("Hands", "double-tap at $xPercent%, $yPercent% (ok=$ok)")
            ActionResult(ok, if (ok) "Double-tapped at $xPercent%, $yPercent%." else "Double-tap was not fully dispatched.")
        }

    /** #32: drag from one percentage point to another. */
    suspend fun dragPercent(
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
        durationMs: Long = 600
    ): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val points = listOf(fromX, fromY, toX, toY)
        if (points.any { it !in 0.0..100.0 }) {
            return@serialized ActionResult(false, "Percentages must be between 0 and 100.")
        }
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val ok = dispatchSwipe(
            service,
            w * (fromX / 100.0).toFloat(), h * (fromY / 100.0).toFloat(),
            w * (toX / 100.0).toFloat(), h * (toY / 100.0).toFloat(),
            durationMs
        )
        com.nshd.jarvis2.core.AppLog.d("Hands", "drag ($fromX,$fromY)->($toX,$toY) (ok=$ok)")
        ActionResult(ok, if (ok) "Dragged from $fromX%, $fromY% to $toX%, $toY%." else "Drag was not dispatched.")
    }

    /** #33: two-finger pinch — [zoomIn] spreads the fingers, else closes them. */
    suspend fun pinch(zoomIn: Boolean): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val metrics = service.resources.displayMetrics
        val cx = metrics.widthPixels * 0.5f
        val cy = metrics.heightPixels * 0.5f
        val near = metrics.widthPixels * 0.08f
        val far = metrics.widthPixels * 0.32f
        val (startOffset, endOffset) = if (zoomIn) near to far else far to near
        fun fingerPath(dx: Float, dy: Float): Path = Path().apply {
            moveTo(cx + dx * startOffset, cy + dy * startOffset)
            lineTo(cx + dx * endOffset, cy + dy * endOffset)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(fingerPath(-1f, -1f), 0, 350))
            .addStroke(GestureDescription.StrokeDescription(fingerPath(1f, 1f), 0, 350))
            .build()
        val ok = suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val accepted = try {
                service.dispatchGesture(gesture, callback, null)
            } catch (e: Exception) {
                false
            }
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
        com.nshd.jarvis2.core.AppLog.d("Hands", "pinch ${if (zoomIn) "out (zoom in)" else "in (zoom out)"} (ok=$ok)")
        ActionResult(
            ok,
            if (ok) "Pinch ${if (zoomIn) "zoomed in" else "zoomed out"}."
            else "Pinch gesture was not dispatched."
        )
    }

    /**
     * #32: set the first visible slider/seek bar to [percent] of its
     * range — ACTION_SET_PROGRESS when the node exposes range info,
     * else a drag along the bar's track to the target position.
     */
    suspend fun setSliderPercent(percent: Int): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val target = percent.coerceIn(0, 100)
        val reader = service.screenReader()
        val slider = reader.snapshot().firstOrNull {
            it.className.contains("SeekBar", ignoreCase = true) ||
                it.className.contains("Slider", ignoreCase = true)
        } ?: return@serialized ActionResult(
            false, "No slider or seek bar is visible on this screen."
        )
        val root = service.activeRoot()
            ?: return@serialized ActionResult(false, "No active window is available.")
        val node = resolveNode(root, slider)
        val range = node?.rangeInfo
        if (node != null && range != null) {
            val value = range.min + (range.max - range.min) * (target / 100f)
            val args = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
            }
            val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
            com.nshd.jarvis2.core.AppLog.d("Hands", "slider set_progress $target% (ok=$ok)")
            if (ok) return@serialized ActionResult(true, "Slider set to $target%.")
        }
        // Fallback: drag the thumb along the track.
        val bounds = slider.bounds
        if (bounds.width() <= 0) {
            return@serialized ActionResult(false, "The slider has no measurable track.")
        }
        val y = bounds.exactCenterY()
        val fromX = bounds.left + bounds.width() * 0.5f
        val toX = bounds.left + bounds.width() * (target / 100f)
        val ok = dispatchSwipe(service, fromX, y, toX, y, durationMs = 300)
        com.nshd.jarvis2.core.AppLog.d("Hands", "slider drag to $target% (ok=$ok)")
        ActionResult(ok, if (ok) "Slider dragged to $target%." else "Slider could not be set.")
    }

    // ── v1.9.0 (#41): undo last action ───────────────────────────────
    //
    // A small in-memory stack of REVERSIBLE action records, pushed by
    // ToolExecutor after a successful mutating tool. Only inverses
    // that are honest are recorded: a text edit restores the field's
    // previous content, a dictation insert deletes its characters,
    // an opened app goes Back. Sends and plays are NEVER recorded —
    // they can't be unsent/unplayed, and pretending otherwise would
    // be a lie in the UI.

    data class UndoEntry(val label: String, val kind: String, val payload: String)

    private val undoStack = ArrayDeque<UndoEntry>()

    @Synchronized
    fun pushUndo(entry: UndoEntry) {
        undoStack.addLast(entry)
        while (undoStack.size > 10) undoStack.removeFirst()
        com.nshd.jarvis2.core.AppLog.d("Hands", "undo recorded: ${entry.label}")
    }

    @Synchronized
    fun peekUndo(): UndoEntry? = undoStack.lastOrNull()

    /** Execute the inverse of the most recent recorded action. */
    suspend fun undoLast(): ActionResult {
        val entry = synchronized(this) {
            if (undoStack.isEmpty()) null else undoStack.removeLast()
        } ?: return ActionResult(
            false,
            "Nothing to undo — no reversible action was recorded this session."
        )
        com.nshd.jarvis2.core.AppLog.i("Hands", "undo: ${entry.label} (kind=${entry.kind})")
        return when (entry.kind) {
            "restore_text" -> restoreFocusedText(entry.payload)
            "delete_chars" -> editTextOnFocused(
                "delete_last_chars", entry.payload.toIntOrNull() ?: 0
            )
            "press_back" -> pressKey("back")
            else -> ActionResult(false, "That action can't be undone.")
        }
    }

    /** Replace the focused field's whole content (undo's text restore). */
    suspend fun restoreFocusedText(text: String): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val root = service.activeRoot()
            ?: return@serialized ActionResult(false, "No active window is available.")
        val target = resolveEditableTarget(root)
            ?: return@serialized ActionResult(false, "The text field is no longer on screen.")
        val accepted = target.node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
        )
        delay(VERIFY_DELAY_MS)
        val readBack = freshTarget(service, target.descriptor)?.text
        if (accepted && readBack == text) {
            ActionResult(true, "Restored the field's previous text.")
        } else {
            ActionResult(false, "The previous text could not be restored.")
        }
    }

    /** The focused field's current selection text, or null (for #64 diffs). */
    fun focusedSelectionText(): String? {
        val service = serviceOrNull() ?: return null
        val root = service.activeRoot() ?: return null
        val target = resolveEditableTarget(root) ?: return null
        val start = target.selectionStart
        val end = target.selectionEnd
        if (start < 0 || end <= start || end > target.text.length) return null
        return target.text.substring(start, end)
    }

    /** The focused field's full current text, or null when none is focused. */
    fun focusedFieldText(): String? {
        val service = serviceOrNull() ?: return null
        val root = service.activeRoot() ?: return null
        return resolveEditableTarget(root)?.text
    }

    // ── v1.9.0 (#42): clipboard tools ────────────────────────────────

    /** Current clipboard text (null when empty/unavailable). */
    fun clipboardText(): String? {
        val service = serviceOrNull() ?: return null
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(service)?.toString()
    }

    /** Put [text] on the clipboard. */
    fun setClipboardText(text: String): Boolean {
        val service = serviceOrNull() ?: return false
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
        return true
    }

    /** Copy the focused field's current selection to the clipboard. */
    suspend fun copySelection(): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val root = service.activeRoot()
            ?: return@serialized ActionResult(false, "No active window is available.")
        val target = resolveEditableTarget(root)
            ?: return@serialized ActionResult(
                false, "No text field is focused — select text first."
            )
        val copied = target.node.performAction(AccessibilityNodeInfo.ACTION_COPY)
        if (!copied) {
            return@serialized ActionResult(
                false, "Nothing was copied — is any text selected?"
            )
        }
        delay(100)
        val clip = clipboardText()
        ActionResult(
            true,
            "Copied to clipboard" +
                (clip?.let { ": \"${it.take(60)}\"" } ?: ".")
        )
    }

    /** Paste the clipboard into the focused field (verified by read-back). */
    suspend fun pasteIntoFocused(): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val clip = clipboardText()
            ?: return@serialized ActionResult(false, "The clipboard is empty.")
        val root = service.activeRoot()
            ?: return@serialized ActionResult(false, "No active window is available.")
        val target = resolveEditableTarget(root)
            ?: return@serialized ActionResult(
                false, "No text field is focused — tap into a field first."
            )
        val pasted = target.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        delay(VERIFY_DELAY_MS)
        val readBack = freshTarget(service, target.descriptor)?.text.orEmpty()
        if (pasted && readBack.contains(clip.take(20))) {
            ActionResult(true, "Pasted ${clip.length} characters from the clipboard.")
        } else {
            ActionResult(false, "Paste was not verified in the field.")
        }
    }

    suspend fun pressKey(key: String): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val action = when (key.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings", "quick_settings", "quick settings" ->
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return@serialized ActionResult(
                false,
                "Key must be back, home, recents, notifications or quickSettings."
            )
        }
        ActionResult(service.performGlobalAction(action), "Pressed ${key.lowercase()}.")
    }

    /** Explicit coordinate tool; not used as a hidden fallback for labelled targets. */
    suspend fun tapPercent(xPercent: Double, yPercent: Double): ActionResult = serializedLong {
        val service = serviceOrNull() ?: return@serializedLong disabled()
        if (xPercent !in 0.0..100.0 || yPercent !in 0.0..100.0) {
            return@serializedLong ActionResult(false, "Tap percentages must be between 0 and 100.")
        }
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * (xPercent / 100.0).toFloat()
        val y = metrics.heightPixels * (yPercent / 100.0).toFloat()
        // SensitiveTapGuard (F3): classify by the element under the
        // point (or the nearest labelled one) — this also covers the
        // vision_tap path, which lands here via ToolExecutor. No label
        // information at all → allow.
        val near = elementNearPoint(x.toInt(), y.toInt(), metrics.density)
        if (near != null) {
            sensitiveTapDenial(service, near, near.label)?.let { return@serializedLong it }
        }
        val dispatched = dispatchSwipe(service, x, y, x, y, durationMs = 60)
        com.nshd.jarvis2.core.AppLog.d(
            "Hands", "tap at $xPercent%, $yPercent% (ok=$dispatched)"
        )
        if (!dispatched) saveTapFailureScreenshot(service, x, y) // #40
        ActionResult(dispatched, if (dispatched) "Tapped at $xPercent%, $yPercent%." else "Tap was not dispatched.")
    }

    /** Element whose bounds contain the point, else the nearest within ~120dp. */
    private fun elementNearPoint(x: Int, y: Int, density: Float): UiElement? {
        val snapshot = JarvisAccessibilityService.lastSnapshot
        if (snapshot.isEmpty()) return null
        snapshot.lastOrNull { it.bounds.contains(x, y) }?.let { return it }
        val maxDistancePx = 120f * density
        return snapshot
            .filter { it.label.isNotBlank() || it.text.isNotBlank() || it.desc.isNotBlank() }
            .minByOrNull { element ->
                val dx = element.bounds.exactCenterX() - x
                val dy = element.bounds.exactCenterY() - y
                dx * dx + dy * dy
            }
            ?.takeIf { element ->
                val dx = element.bounds.exactCenterX() - x
                val dy = element.bounds.exactCenterY() - y
                dx * dx + dy * dy <= maxDistancePx * maxDistancePx
            }
    }

    /**
     * Settle = the tree signature is unchanged across 2 consecutive
     * polls. F6 speed pass: the poll interval dropped 150 ms → 100 ms
     * (the minimum settle is now ~200 ms instead of ~450 ms), and
     * callers pass context caps (≤ 900 ms after taps, ≤ 400 ms after
     * typing) instead of the old generous defaults.
     */
    suspend fun waitForSettle(timeoutMs: Long = 2_000): Boolean {
        val service = serviceOrNull() ?: return false
        val reader = service.screenReader()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100, 10_000)
        var previous = reader.signature()
        var stableSamples = 0
        while (System.currentTimeMillis() < deadline) {
            delay(SETTLE_POLL_MS)
            val current = reader.signature()
            if (current == previous) {
                stableSamples++
                if (stableSamples >= 2) return true
            } else {
                stableSamples = 0
                previous = current
            }
        }
        return false
    }

    /**
     * F6: poll until [packageName] owns the active window (app-launch
     * wait), exiting the moment it appears — this replaces fixed
     * post-launch sleeps on the deep-intent paths.
     */
    suspend fun waitForPackage(packageName: String, timeoutMs: Long = 2_500): Boolean {
        val service = serviceOrNull() ?: return false
        val reader = service.screenReader()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100, 10_000)
        while (System.currentTimeMillis() < deadline) {
            if (reader.currentPackage() == packageName) return true
            delay(SETTLE_POLL_MS)
        }
        return reader.currentPackage() == packageName
    }

    /** F6: poll until any visible editable node exists on screen. */
    suspend fun waitForEditable(timeoutMs: Long = 2_500): Boolean {
        val service = serviceOrNull() ?: return false
        val reader = service.screenReader()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100, 10_000)
        while (System.currentTimeMillis() < deadline) {
            if (reader.snapshot().any { it.editable }) return true
            delay(SETTLE_POLL_MS)
        }
        return reader.snapshot().any { it.editable }
    }

    /** F6: poll until [predicate] accepts a fresh snapshot. */
    suspend fun waitForSnapshot(
        timeoutMs: Long,
        predicate: (List<UiElement>) -> Boolean
    ): List<UiElement>? {
        val service = serviceOrNull() ?: return null
        val reader = service.screenReader()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100, 10_000)
        while (true) {
            val snapshot = reader.snapshot()
            if (predicate(snapshot)) return snapshot
            if (System.currentTimeMillis() >= deadline) return null
            delay(SETTLE_POLL_MS)
        }
    }

    private suspend fun <T> serialized(block: suspend () -> T): T =
        mutex.withLock { withTimeout(15_000) { block() } }

    /**
     * Same single-mutex serialization, but with a 45-second bound: tap
     * actions may suspend in the SensitiveTapGuard's ConfirmGate or
     * SendAsk, whose own timeouts are 30 s — the 15 s bound would cut
     * the prompt short.
     */
    private suspend fun <T> serializedLong(block: suspend () -> T): T =
        mutex.withLock { withTimeout(45_000) { block() } }

    // ── SensitiveTapGuard (F3) ─────────────────────────────────────────
    //
    // Before any tap executes, the target's label / contentDescription /
    // text is classified. v2.0.3: SEND targets NEVER reach ConfirmGate
    // (no card) — a send tap without a live SendPermit suspends in
    // SendAsk, the just-in-time voice ask (plain panel line + chat /
    // caption message, voice/text "yes"), exactly like a send tool's
    // own ask. SOCIAL and DESTRUCTIVE targets still suspend in
    // ConfirmGate (non-send classes keep their card). Allow runs the
    // tap, Deny/timeout returns ActionResult(false, "User declined
    // this action"). Never gated: our own package (including the
    // ConfirmGate dialog's own buttons), soft-keyboard keys, pure
    // navigation (back/home/tabs and friends), switches and sliders.

    /** Returns a denial result when the tap must not run, else null. */
    private suspend fun sensitiveTapDenial(
        service: JarvisAccessibilityService,
        element: UiElement?,
        fallbackLabel: String
    ): ActionResult? {
        val packageName = JarvisAccessibilityService.lastPackageName
            ?: service.screenReader().currentPackage()
        if (packageName == OWN_PACKAGE) return null
        if (isGuardExempt(element, packageName)) return null
        val labelText = listOf(
            element?.label.orEmpty(),
            element?.text.orEmpty(),
            element?.desc.orEmpty(),
            fallbackLabel
        ).joinToString(" ").trim()
        if (labelText.isBlank()) return null
        val phrase = sensitivePhrase(labelText.lowercase()) ?: return null
        // F7 SendPermit: a SEND-class tap covered by a live permit
        // (the send tool's just-in-time ask already approved THIS
        // send) passes without a second gate. One-shot — consumed
        // here; SOCIAL/DESTRUCTIVE phrases never see the permit.
        if (phrase in SEND_PHRASES && consumeSendPermit(packageName)) {
            com.nshd.jarvis2.core.AppLog.i(
                "Send",
                "permit consumed — approved send tap passed the guard in " +
                    (packageName ?: "unknown app")
            )
            return null
        }
        val displayLabel = element?.label?.ifBlank { fallbackLabel }
            ?: fallbackLabel.ifBlank { labelText }
        // F5: every guard trigger is a log line (the ConfirmGate prompt
        // + decision are logged by the gate itself; SendAsk logs its
        // own ask + decision under the "SendAsk" tag).
        com.nshd.jarvis2.core.AppLog.w(
            "Hands",
            "SensitiveTapGuard triggered ('$phrase') on " +
                "'${displayLabel.trim().take(50)}' in ${packageName ?: "unknown app"}"
        )
        if (phrase in SEND_PHRASES) {
            // v2.0.3: the surviving send card lived HERE. A generic
            // tap on a Send/Post control (model tap, macro replay,
            // any path that did not come through a send tool) used to
            // pop a ConfirmGate card — often invisible over the
            // foreground app, so it timed out and the send died. Now
            // it asks the same just-in-time voice ask instead: the
            // recipient is the app, the text names the control. A
            // send tool's own tap never reaches this branch (its
            // permit is consumed above), so an approved send is never
            // asked twice. If another ask is already pending,
            // SendAsk.ask returns false (no stacking) and the tap is
            // denied — never auto-sent.
            val approved = SendAsk.ask(
                appDisplayName(service, packageName),
                "Send action '${displayLabel.trim().take(60)}'"
            )
            return if (approved) {
                null
            } else {
                ActionResult(false, "Send tap was not confirmed — nothing was sent.")
            }
        }
        // SOCIAL / DESTRUCTIVE only from here down: ConfirmGate
        // STAYS for these non-send classes (v2.0.3 keeps that gate).
        val approved = ConfirmGate.confirm(
            "Tap '${displayLabel.trim().take(60)}' in ${appDisplayName(service, packageName)}?"
        )
        return if (approved) null else ActionResult(false, "User declined this action")
    }

    private fun isGuardExempt(element: UiElement?, packageName: String?): Boolean {
        if (packageName != null && (
                packageName.contains("inputmethod") ||
                    packageName.contains("honeyboard") ||
                    packageName == "com.google.android.inputmethod.latin"
                )
        ) return true // soft-keyboard keys
        if (element == null) return false
        val className = element.className
        if (className.contains("Switch") || className.contains("ToggleButton") ||
            className.contains("SeekBar") || className.contains("Slider") ||
            className.contains("CheckBox") || className.contains("RadioButton") ||
            className.contains("TabLayout") || className.contains("TabWidget")
        ) return true // switches / sliders / tab strips
        if (element.checked != null) return true // checkable toggle state control
        if (element.viewId.contains("tab", ignoreCase = true)) return true
        val normalizedLabel = element.label.trim().lowercase()
        if (normalizedLabel in NAV_LABELS) return true // pure navigation
        return false
    }

    /** First sensitive phrase (word-boundary match), most specific class first. */
    private fun sensitivePhrase(lowerLabel: String): String? {
        for (phrase in SOCIAL_PHRASES + SEND_PHRASES + DESTRUCTIVE_PHRASES) {
            if (Regex("\\b" + Regex.escape(phrase) + "\\b").containsMatchIn(lowerLabel)) {
                return phrase
            }
        }
        return null
    }

    private fun appDisplayName(
        service: JarvisAccessibilityService,
        packageName: String?
    ): String {
        if (packageName.isNullOrBlank()) return "this app"
        return try {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "this app" }
        }
    }

    private fun serviceOrNull(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    private fun disabled(): ActionResult = ActionResult(
        false,
        "Accessibility is not enabled. Enable Jarvis Digital Hands in Permissions → Accessibility Service first."
    )

    /**
     * #37 retry-with-variation + #40 tap-failure screenshot.
     *
     * Guard invariant: the SensitiveTapGuard was already consulted by
     * the caller (clickElement / clickText) BEFORE this method runs,
     * for this exact element. Every strategy below is part of that one
     * guard-cleared tap — a retry never re-decides or bypasses the
     * guard, and no new target is ever introduced by a retry.
     *
     * Order: (primary) clickable-ancestor ACTION_CLICK, then node
     * centre; (retry 1) clickable parent centre; (retry 2) a point at
     * 25% from the node's left edge, centre-height. A vanished node
     * skips straight to the coordinate strategies from the snapshot
     * bounds. The winning strategy is logged.
     */
    private suspend fun clickResolved(
        service: JarvisAccessibilityService,
        element: UiElement,
        targetDescription: String
    ): ActionResult {
        val root = service.activeRoot()
            ?: return ActionResult(false, "No active window is available for the click.")
        val node = resolveNode(root, element)
        val centerX = element.bounds.exactCenterX()
        val centerY = element.bounds.exactCenterY()
        var lastX = centerX
        var lastY = centerY

        if (node != null) {
            var candidate: AccessibilityNodeInfo? = node
            var steps = 0
            while (candidate != null && steps <= 8) {
                if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    com.nshd.jarvis2.core.AppLog.i(
                        "Hands", "click strategy=a11y-click succeeded for ${targetDescription.take(60)}"
                    )
                    return ActionResult(true, "Clicked $targetDescription.")
                }
                candidate = candidate.parent
                steps++
            }
        } else {
            com.nshd.jarvis2.core.AppLog.w(
                "Hands", "click target vanished, using snapshot bounds for ${targetDescription.take(60)}"
            )
        }

        // Primary coordinate tap: centre of the node bounds.
        if (dispatchSwipe(service, centerX, centerY, centerX, centerY, durationMs = 60)) {
            com.nshd.jarvis2.core.AppLog.i(
                "Hands", "click strategy=centre succeeded for ${targetDescription.take(60)}"
            )
            return ActionResult(true, "Clicked $targetDescription with a coordinate tap fallback.")
        }

        // Retry 1: centre of the node's clickable parent.
        val parent = node?.parent
        if (parent != null) {
            val parentBounds = Rect().also { parent.getBoundsInScreen(it) }
            if (parentBounds.width() > 0 && parentBounds.height() > 0) {
                val parentX = parentBounds.exactCenterX()
                val parentY = parentBounds.exactCenterY()
                lastX = parentX
                lastY = parentY
                if (dispatchSwipe(service, parentX, parentY, parentX, parentY, durationMs = 60)) {
                    com.nshd.jarvis2.core.AppLog.i(
                        "Hands", "click strategy=parent-centre succeeded for ${targetDescription.take(60)}"
                    )
                    return ActionResult(true, "Clicked $targetDescription with a coordinate tap fallback.")
                }
            }
        }

        // Retry 2: 25% from the node's left edge, centre-height.
        val edgeX = element.bounds.left + element.bounds.width() * 0.25f
        val edgeY = centerY
        lastX = edgeX
        lastY = edgeY
        if (dispatchSwipe(service, edgeX, edgeY, edgeX, edgeY, durationMs = 60)) {
            com.nshd.jarvis2.core.AppLog.i(
                "Hands", "click strategy=edge-25 succeeded for ${targetDescription.take(60)}"
            )
            return ActionResult(true, "Clicked $targetDescription with a coordinate tap fallback.")
        }

        // #40: all strategies exhausted — annotated failure screenshot.
        saveTapFailureScreenshot(service, lastX, lastY)
        return ActionResult(false, "Could not click $targetDescription.")
    }

    /**
     * #40: annotated tap-failure screenshot. The capture itself is
     * fetched first (suspend, failure -> log + skip); the decode /
     * draw / save work is wrapped in runCatching so a screenshot
     * failure can never mask the original tap failure. The capture is
     * a JPEG scaled to width <= 720, so the tap point is scaled from
     * display pixels into bitmap pixels before drawing.
     */
    private suspend fun saveTapFailureScreenshot(
        service: JarvisAccessibilityService,
        tapX: Float,
        tapY: Float
    ) {
        val encoded = try {
            service.captureScreenshotBase64()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.nshd.jarvis2.core.AppLog.w("Hands", "Tap failed — screenshot capture failed: ${e.message}")
            null
        }
        if (encoded == null) {
            com.nshd.jarvis2.core.AppLog.i("Hands", "Tap failed — screenshot unavailable, skipping annotated screenshot")
            return
        }
        runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("screenshot decode returned null")
            val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: decoded
            try {
                val metrics = service.resources.displayMetrics
                val scaleX = if (metrics.widthPixels > 0) bitmap.width.toFloat() / metrics.widthPixels else 1f
                val scaleY = if (metrics.heightPixels > 0) bitmap.height.toFloat() / metrics.heightPixels else 1f
                val cx = tapX * scaleX
                val cy = tapY * scaleY
                val canvas = Canvas(bitmap)
                val ring = Paint().apply {
                    color = Color.RED
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                    isAntiAlias = true
                }
                canvas.drawCircle(cx, cy, 42f, ring)
                val dot = Paint().apply {
                    color = Color.RED
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(cx, cy, 7f, dot)
                val dir = File(service.filesDir, "tapshots").apply { mkdirs() }
                val file = File(dir, "tapfail_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                // Prune to the last 10 failure shots.
                dir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("tapfail_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(10)
                    ?.forEach { stale -> runCatching { stale.delete() } }
                com.nshd.jarvis2.core.AppLog.i(
                    "Hands", "Tap failed — annotated screenshot saved: ${file.absolutePath}"
                )
            } finally {
                bitmap.recycle()
                if (decoded !== bitmap) decoded.recycle()
            }
        }.onFailure { e ->
            com.nshd.jarvis2.core.AppLog.w("Hands", "Tap failed — annotated screenshot failed: ${e.message}")
        }
    }

    /**
     * #35 keyboard-cover fix: when the editable target lies in the
     * bottom ~35% of the screen it is likely covered by the IME.
     * Try, in order: the node's own ACTION_SCROLL_FORWARD, a
     * scrollable ancestor's ACTION_SCROLL_FORWARD, then a small
     * swipe-up. Afterwards the target is re-fetched so callers set
     * text on the fresh node (selection / bounds may have moved).
     * When the field is not in the bottom zone (or nothing scrolls)
     * the original target is returned untouched.
     */
    private suspend fun ensureEditableVisible(
        service: JarvisAccessibilityService,
        target: EditableTarget
    ): EditableTarget {
        val metrics = service.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        if (screenHeight <= 0) return target
        val bounds = target.descriptor.bounds
        val bottomZoneTop = (screenHeight * 0.65f).toInt()
        if (bounds.centerY() < bottomZoneTop && bounds.top < bottomZoneTop) return target
        com.nshd.jarvis2.core.AppLog.i(
            "Hands", "editable field in bottom zone (IME cover risk) — scrolling into view"
        )
        var scrolled = target.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (!scrolled) {
            var ancestor = target.node.parent
            var steps = 0
            while (ancestor != null && steps <= 8 && !scrolled) {
                if (ancestor.isScrollable) {
                    scrolled = ancestor.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                ancestor = ancestor.parent
                steps++
            }
        }
        if (!scrolled) {
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            scrolled = dispatchSwipe(service, width * 0.5f, height * 0.75f, width * 0.5f, height * 0.55f, durationMs = 300)
        }
        if (scrolled) delay(150)
        return freshTarget(service, target.descriptor) ?: target
    }

    private fun resolveNode(root: AccessibilityNodeInfo, element: UiElement): AccessibilityNodeInfo? {
        if (element.viewId.isNotBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(element.viewId)
            byId.firstOrNull { boundsMatch(it, element.bounds) }?.let { return it }
            if (byId.size == 1) return byId.first()
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        var visited = 0
        while (!queue.isEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            var score = 0
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (boundsMatch(node, element.bounds)) score += 100
            if (bounds.intersect(element.bounds)) score += 20
            if (node.viewIdResourceName.orEmpty() == element.viewId && element.viewId.isNotBlank()) score += 80
            if (node.className?.toString().orEmpty() == element.className) score += 10
            if (node.text?.toString().orEmpty() == element.text && element.text.isNotBlank()) score += 40
            if (node.contentDescription?.toString().orEmpty() == element.desc && element.desc.isNotBlank()) score += 40
            if (node.isClickable == element.clickable) score += 2
            if (node.isEditable == element.editable) score += 2
            if (score > bestScore) {
                bestScore = score
                best = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best?.takeIf { bestScore >= 30 }
    }

    private fun boundsMatch(node: AccessibilityNodeInfo, expected: Rect): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return abs(bounds.left - expected.left) <= 4 &&
            abs(bounds.top - expected.top) <= 4 &&
            abs(bounds.right - expected.right) <= 4 &&
            abs(bounds.bottom - expected.bottom) <= 4
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (!queue.isEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun AccessibilityNodeInfo.toDescriptor(): UiElement {
        val bounds = Rect().also { getBoundsInScreen(it) }
        return UiElement(
            index = -1,
            text = text?.toString().orEmpty(),
            desc = contentDescription?.toString().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            className = className?.toString().orEmpty(),
            bounds = bounds,
            clickable = isClickable,
            editable = isEditable,
            scrollable = isScrollable,
            checked = if (isCheckable) isChecked else null,
            enabled = isEnabled
        )
    }

    private suspend fun dispatchSwipe(
        service: JarvisAccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val accepted = try {
                service.dispatchGesture(gesture, callback, null)
            } catch (e: Exception) {
                false
            }
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }
}

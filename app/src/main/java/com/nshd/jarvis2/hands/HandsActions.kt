package com.nshd.jarvis2.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.nshd.jarvis2.brain.tools.ConfirmGate
import com.nshd.jarvis2.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.math.abs

data class ActionResult(val success: Boolean, val message: String)

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
        val element = reader.findByText(text)
            ?: return@serializedLong ActionResult(false, "No visible element matched \"$text\".")
        sensitiveTapDenial(service, element, text)?.let { return@serializedLong it }
        clickResolved(service, element, "\"${element.label.ifBlank { text }}\"")
    }

    suspend fun setTextOnFocused(text: String): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        if (text.isBlank()) return@serialized ActionResult(false, "Text to type is blank.")
        val root = service.activeRoot()
            ?: return@serialized ActionResult(false, "No active window is available.")
        val target = findEditable(root)
            ?: return@serialized ActionResult(false, "No editable text field was found on this screen.")
        val descriptor = target.toDescriptor()
        val existing = target.text?.toString().orEmpty()
        if (existing == text) {
            return@serialized ActionResult(
                true,
                "The field already contains the exact requested text; nothing was typed again."
            )
        }

        fun freshTarget(): AccessibilityNodeInfo? {
            val freshRoot = service.activeRoot() ?: return null
            return resolveNode(freshRoot, descriptor) ?: findEditable(freshRoot)
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        delay(180)
        var readBack = freshTarget()?.text?.toString().orEmpty()
        if (accepted && readBack == text) {
            return@serialized ActionResult(true, "Text entered and verified.")
        }

        // Verified paste fallback: only used after SET_TEXT failed to produce
        // the exact value. Never append blindly.
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@serialized ActionResult(false, "Text entry could not be verified and clipboard is unavailable.")
        clipboard.setPrimaryClip(ClipData.newPlainText("JarvisInput", text))
        val pasteTarget = freshTarget()
            ?: return@serialized ActionResult(false, "The text field disappeared before paste could be verified.")
        val pasted = pasteTarget.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        delay(180)
        readBack = freshTarget()?.text?.toString().orEmpty()
        if (pasted && readBack == text) {
            ActionResult(true, "Text entered with paste fallback and verified.")
        } else {
            ActionResult(
                false,
                "Text entry was not verified. The field contains \"$readBack\", not the requested text."
            )
        }
    }

    suspend fun scroll(direction: String): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
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
            else -> return@serialized ActionResult(false, "Scroll direction must be up, down, left or right.")
        }
        val dispatched = dispatchSwipe(service, startX, startY, endX, endY, durationMs = 600)
        if (!dispatched) return@serialized ActionResult(false, "The scroll gesture was not dispatched.")
        delay(450)
        val changed = reader.signature() != before
        ActionResult(
            changed,
            if (changed) "Scrolled ${direction.lowercase()} and the screen changed."
            else "Scroll gesture completed, but the screen did not change (possibly the end of the content)."
        )
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

    suspend fun waitForSettle(timeoutMs: Long = 2_000): Boolean {
        val service = serviceOrNull() ?: return false
        val reader = service.screenReader()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100, 10_000)
        var previous = reader.signature()
        var stableSamples = 0
        while (System.currentTimeMillis() < deadline) {
            delay(150)
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

    private suspend fun <T> serialized(block: suspend () -> T): T =
        mutex.withLock { withTimeout(15_000) { block() } }

    /**
     * Same single-mutex serialization, but with a 45-second bound: tap
     * actions may suspend in the SensitiveTapGuard's ConfirmGate, whose
     * own timeout is 30 s — the 15 s bound would cut the prompt short.
     */
    private suspend fun <T> serializedLong(block: suspend () -> T): T =
        mutex.withLock { withTimeout(45_000) { block() } }

    // ── SensitiveTapGuard (F3) ─────────────────────────────────────────
    //
    // Before any tap executes, the target's label / contentDescription /
    // text is classified. SEND, SOCIAL and DESTRUCTIVE targets suspend
    // in ConfirmGate (the same mechanism ToolExecutor uses); Allow runs
    // the tap, Deny/timeout returns ActionResult(false, "User declined
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
        val displayLabel = element?.label?.ifBlank { fallbackLabel }
            ?: fallbackLabel.ifBlank { labelText }
        // F5: every guard trigger is a log line (the ConfirmGate prompt
        // + decision are logged by the gate itself).
        com.nshd.jarvis2.core.AppLog.w(
            "Hands",
            "SensitiveTapGuard triggered ('$phrase') on " +
                "'${displayLabel.trim().take(50)}' in ${packageName ?: "unknown app"}"
        )
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

    private suspend fun clickResolved(
        service: JarvisAccessibilityService,
        element: UiElement,
        targetDescription: String
    ): ActionResult {
        val root = service.activeRoot()
            ?: return ActionResult(false, "No active window is available for the click.")
        val node = resolveNode(root, element)
            ?: return ActionResult(false, "The target $targetDescription could not be re-found on the current screen.")

        var candidate: AccessibilityNodeInfo? = node
        var steps = 0
        while (candidate != null && steps <= 8) {
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                com.nshd.jarvis2.core.AppLog.d(
                    "Hands", "click ${targetDescription.take(60)}"
                )
                return ActionResult(true, "Clicked $targetDescription.")
            }
            candidate = candidate.parent
            steps++
        }

        val centerX = element.bounds.exactCenterX()
        val centerY = element.bounds.exactCenterY()
        val tapped = dispatchSwipe(service, centerX, centerY, centerX, centerY, durationMs = 60)
        com.nshd.jarvis2.core.AppLog.d(
            "Hands",
            "tap ${targetDescription.take(60)} (coordinate fallback, ok=$tapped)"
        )
        return ActionResult(
            tapped,
            if (tapped) "Clicked $targetDescription with a coordinate tap fallback."
            else "Could not click $targetDescription."
        )
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

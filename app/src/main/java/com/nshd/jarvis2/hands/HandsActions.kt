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

    suspend fun clickElement(index: Int): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val element = JarvisAccessibilityService.lastSnapshot.getOrNull(index)
            ?: return@serialized ActionResult(
                false,
                "Element #$index is not in the last screen snapshot. Call inspect_screen first."
            )
        clickResolved(service, element, "element #$index (${element.label.ifBlank { "unlabelled" }})")
    }

    suspend fun clickText(text: String): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        val reader = service.screenReader()
        val element = reader.findByText(text)
            ?: return@serialized ActionResult(false, "No visible element matched \"$text\".")
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
    suspend fun tapPercent(xPercent: Double, yPercent: Double): ActionResult = serialized {
        val service = serviceOrNull() ?: return@serialized disabled()
        if (xPercent !in 0.0..100.0 || yPercent !in 0.0..100.0) {
            return@serialized ActionResult(false, "Tap percentages must be between 0 and 100.")
        }
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * (xPercent / 100.0).toFloat()
        val y = metrics.heightPixels * (yPercent / 100.0).toFloat()
        val dispatched = dispatchSwipe(service, x, y, x, y, durationMs = 60)
        ActionResult(dispatched, if (dispatched) "Tapped at $xPercent%, $yPercent%." else "Tap was not dispatched.")
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
                return ActionResult(true, "Clicked $targetDescription.")
            }
            candidate = candidate.parent
            steps++
        }

        val centerX = element.bounds.exactCenterX()
        val centerY = element.bounds.exactCenterY()
        val tapped = dispatchSwipe(service, centerX, centerY, centerX, centerY, durationMs = 60)
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

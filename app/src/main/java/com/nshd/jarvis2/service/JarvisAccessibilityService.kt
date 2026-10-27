package com.nshd.jarvis2.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.nshd.jarvis2.brain.tools.ConfirmGate
import com.nshd.jarvis2.brain.tools.SendAsk
import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.hands.ScreenReader
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.hands.UiElement
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Digital Hands service (Level 4).
 *
 * The important Level 4 change is root selection: use the focused active
 * window, never an arbitrary first window. Dialogs, keyboards, split-screen
 * and floating windows can reorder [windows], and acting on a guessed root
 * can tap the wrong application.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (pkg != null) lastPackageName = pkg
        // #34: system-dialog awareness — report only, never auto-tap.
        try {
            handlePossibleSystemDialog(event, pkg)
        } catch (e: Exception) {
            // Dialog detection must never crash the service.
        }
        // F16: user takeover — a real tap/scroll while a task runs
        // stops the pending task (see handlePossibleUserTakeover).
        try {
            handlePossibleUserTakeover(event)
        } catch (e: Exception) {
            // Takeover detection must never crash the service.
        }
    }

    /**
     * F16: the user took over the screen. While a task is active, a
     * genuine tap (VIEW_CLICKED) or scroll (VIEW_SCROLLED) on another
     * app's UI means the user grabbed control — the pending task
     * stops instead of fighting them for the screen.
     *
     * Deliberately NOT triggered by:
     * - window state/content changes (automation causes these constantly);
     * - focus changes (too noisy: delayed focus after app loads);
     * - taps on Jarvis's own package (panel buttons already abort
     *   directly; the chat send path stops the old turn itself);
     * - events inside [USER_TAKEOVER_QUIET_MS] after our own last
     *   automation action (our own taps/clicks echo back as events);
     * - moments when a just-in-time ask is pending (SendAsk /
     *   ConfirmGate): the turn is suspended waiting for the user's
     *   answer, and cancelling it would orphan the ask — answering
     *   "yes" would then report a send that never happens.
     */
    private fun handlePossibleUserTakeover(event: AccessibilityEvent?) {
        if (event == null || !TaskState.active.value) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return
        if (event.packageName?.toString() == packageName) return
        // F18: scroll echoes get the longer scroll grace — a fling's
        // trailing events past 1.5 s are still ours, not a takeover.
        // Clicks keep the short window: a real user tap during a
        // scroll must still stop the task immediately.
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (System.currentTimeMillis() - lastAutomationScrollMs < SCROLL_TAKEOVER_QUIET_MS) return
        } else if (System.currentTimeMillis() - lastAutomationActionMs < USER_TAKEOVER_QUIET_MS) return
        if (SendAsk.pending.value != null || ConfirmGate.pending.value != null) return
        AppLog.i("Hands", "User took over the screen — stopping pending task")
        TaskState.step("✋ You took over — task stopped.")
        TaskState.abort()
    }

    /**
     * #34: detect dialogs from system packages on window-state-changed
     * (or dialog-class) events, extract their visible text, log it and
     * — only while a task is active — surface it on the Task HUD.
     * REPORT ONLY: this code performs no clicks, no actions and no
     * grants in system dialogs, ever.
     */
    private fun handlePossibleSystemDialog(event: AccessibilityEvent?, pkg: String?) {
        if (event == null || pkg == null) return
        if (pkg !in SYSTEM_DIALOG_PACKAGES) return
        val className = event.className?.toString().orEmpty()
        val isWindowState = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isDialogClass = className.contains("Dialog", ignoreCase = true) ||
            className.contains("Alert", ignoreCase = true) ||
            className.contains("permission", ignoreCase = true)
        if (!isWindowState && !isDialogClass) return
        val texts = mutableListOf<String>()
        event.text?.forEach { part ->
            part?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts += it }
        }
        event.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }?.let { texts += it }
        collectNodeText(event.source, texts, depth = 0)
        rootInActiveWindow
            ?.takeIf { it.packageName?.toString() == pkg }
            ?.let { collectNodeText(it, texts, depth = 0) }
        // Also scan service windows belonging to the system package.
        try {
            windows.firstOrNull { window ->
                window.root?.packageName?.toString() == pkg &&
                    (window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION)
            }?.root?.let { collectNodeText(it, texts, depth = 0) }
        } catch (e: Exception) {
            // Window scan is best-effort only.
        }
        val dialogText = texts.distinct().joinToString(" ").trim().take(300)
        if (dialogText.isBlank()) return
        if (dialogText == lastSystemDialog) return // de-dupe repeat events
        lastSystemDialog = dialogText
        AppLog.i("Hands", "System dialog: ${dialogText.take(200)}")
        if (TaskState.active.value) {
            TaskState.step("⚠️ Android is asking: ${dialogText.take(120)}")
        }
    }

    /** Bounded recursive text/desc collection for dialog detection. */
    private fun collectNodeText(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > 6 || out.size >= 40) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        node.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }?.let { out += it }
        for (i in 0 until node.childCount) {
            collectNodeText(node.getChild(i), out, depth + 1)
            if (out.size >= 40) return
        }
    }

    override fun onInterrupt() {
        // Android requires this callback; individual actions are short-lived.
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            lastSnapshot = emptyList()
        }
        super.onDestroy()
    }

    /** Root of the focused application window, with conservative fallbacks. */
    fun activeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        appWindows.firstOrNull { it.isFocused }?.root?.let { return it }
        appWindows.firstOrNull { it.isActive }?.root?.let { return it }
        return null
    }

    fun screenReader(): ScreenReader = ScreenReader(this)

    internal fun updateLastSnapshot(elements: List<UiElement>, packageName: String?) {
        lastSnapshot = elements
        if (!packageName.isNullOrBlank()) lastPackageName = packageName
    }

    /** Retained for older UI callers; now backed by the structured reader. */
    fun screenText(): List<String> = screenReader().snapshot()
        .map { it.label }
        .filter { it.isNotBlank() }
        .take(MAX_SCREEN_TEXT)

    /**
     * Prompt-free screen still via the accessibility takeScreenshot API
     * (Level 5 vision source #2 — no user consent dialog, unlike
     * MediaProjection). Returns a Base64 JPEG (width ≤ 720, q70) or null
     * when unavailable: API < 31 (Bitmap.wrapHardwareBuffer), service not
     * connected, or capture error.
     */
    suspend fun captureScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val executor = Executors.newSingleThreadExecutor()
        return try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val encoded = try {
                            val buffer = result.hardwareBuffer
                            // Copy out of the hardware buffer BEFORE closing it —
                            // a wrapped bitmap must not be read after close.
                            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val softwareCopy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                            wrapped?.recycle()
                            val jpeg = softwareCopy?.let { bitmap ->
                                try {
                                    val scale = if (bitmap.width > MAX_SHOT_WIDTH) {
                                        MAX_SHOT_WIDTH.toFloat() / bitmap.width
                                    } else 1f
                                    val out = if (scale < 1f) {
                                        Bitmap.createScaledBitmap(
                                            bitmap,
                                            MAX_SHOT_WIDTH,
                                            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                                            true
                                        )
                                    } else bitmap
                                    try {
                                        val stream = ByteArrayOutputStream()
                                        out.compress(Bitmap.CompressFormat.JPEG, SHOT_JPEG_QUALITY, stream)
                                        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                    } finally {
                                        if (out !== bitmap) out.recycle()
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            jpeg
                        } catch (e: Exception) {
                            null
                        }
                        if (continuation.isActive) continuation.resume(encoded)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    companion object {
        private const val MAX_SCREEN_TEXT = 100
        private const val MAX_SHOT_WIDTH = 720
        private const val SHOT_JPEG_QUALITY = 70

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        /** Elements from the most recent [ScreenReader.snapshot] call. */
        @Volatile
        var lastSnapshot: List<UiElement> = emptyList()
            internal set

        @Volatile
        var lastPackageName: String? = null
            internal set

        /** #34: text of the most recent system dialog seen (report-only). */
        @Volatile
        var lastSystemDialog: String? = null

        /**
         * F16: wall-clock of the automation's own last dispatched
         * action (tap / click / scroll / gesture / global action),
         * stamped by HandsActions. User-tap/scroll events arriving
         * within [USER_TAKEOVER_QUIET_MS] after this are treated as
         * echoes of our own actions, not a user takeover.
         */
        @Volatile
        var lastAutomationActionMs: Long = 0L
            private set

        /**
         * F18: separate stamp for our own SCROLL gestures. A scroll's
         * fling keeps emitting TYPE_VIEW_SCROLLED for well past the
         * 1.5 s general quiet window (slow device, reel transition),
         * and F16 read those trailing echoes as the user grabbing
         * the phone — which aborted the task right after a scroll
         * ("task stopped"). Scroll echoes get their own longer grace.
         */
        @Volatile
        var lastAutomationScrollMs: Long = 0L
            private set

        /** F16: stamp one automation action (call at every dispatch site). */
        fun noteAutomationAction() {
            lastAutomationActionMs = System.currentTimeMillis()
        }

        /** F18: stamp one automation scroll (call after every scroll dispatch). */
        fun noteAutomationScroll() {
            lastAutomationScrollMs = System.currentTimeMillis()
        }

        /** F16: quiet window after our own action (echo suppression). */
        private const val USER_TAKEOVER_QUIET_MS = 1_500L

        /** F18: quiet window for our own scrolls' trailing events. */
        private const val SCROLL_TAKEOVER_QUIET_MS = 3_500L

        /** #34: packages whose dialogs are system dialogs. */
        private val SYSTEM_DIALOG_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )

        fun isEnabled(): Boolean = instance != null
    }
}

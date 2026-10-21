package com.nshd.jarvis2.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.nshd.jarvis2.hands.ScreenReader
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
        event?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let {
            lastPackageName = it
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

        fun isEnabled(): Boolean = instance != null
    }
}

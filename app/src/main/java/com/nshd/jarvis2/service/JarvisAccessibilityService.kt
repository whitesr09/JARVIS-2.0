package com.nshd.jarvis2.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.nshd.jarvis2.hands.ScreenReader
import com.nshd.jarvis2.hands.UiElement

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

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    companion object {
        private const val MAX_SCREEN_TEXT = 100

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

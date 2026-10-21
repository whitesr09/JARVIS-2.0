package com.nshd.jarvis2.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Digital Hands foundation (Level 1): the service registers itself and can
 * read the current screen's text and perform global actions. The automation
 * engine (click/type/swipe, verified action loop) lands in Level 4.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // L1: no event handling — the agent loop arrives in Level 4.
    }

    override fun onInterrupt() {
        // Nothing to interrupt in L1.
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** All visible text + content descriptions on the current screen (max 100 entries). */
    fun screenText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<String>()
        collectText(root, out)
        return out
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.size >= MAX_SCREEN_TEXT) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        if (out.size < MAX_SCREEN_TEXT) {
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= MAX_SCREEN_TEXT) break
            collectText(node.getChild(i), out)
        }
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    companion object {
        private const val MAX_SCREEN_TEXT = 100

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}

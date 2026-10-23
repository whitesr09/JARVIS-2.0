package com.nshd.jarvis2.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nshd.jarvis2.core.AppLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * v2.0.0 (#86): opt-in notification listener — powers the unread
 * reader (#50: "read my WhatsApp messages" / "who messaged me?").
 *
 * Opt-in twice over: Android only delivers notifications after the
 * user grants Notification Access in system settings, AND the
 * pipeline only reads them when the in-app "Notification reader"
 * switch (BrainStore) is on — off by default. Nothing is persisted:
 * the store is an in-memory ring of the most recent notifications,
 * gone when the listener unbinds. Jarvis's own notifications are
 * never recorded.
 */
class JarvisNotificationListener : NotificationListenerService() {

    data class Note(
        val packageName: String,
        val title: String,
        val text: String,
        val atMs: Long
    )

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        private val notes = CopyOnWriteArrayList<Note>()

        /** Newest first. */
        fun recent(): List<Note> = notes.toList()

        fun recentForPackage(packageName: String): List<Note> =
            notes.filter { it.packageName == packageName }

        private const val MAX_NOTES = 40
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        AppLog.i("App", "notification listener connected (#86)")
    }

    override fun onListenerDisconnected() {
        connected = false
        notes.clear()
        AppLog.i("App", "notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName == packageName) return
        val extras = posted.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        notes.add(
            0,
            Note(
                packageName = posted.packageName,
                title = title,
                text = text,
                atMs = posted.postTime
            )
        )
        while (notes.size > MAX_NOTES) notes.removeAt(notes.lastIndex)
    }
}

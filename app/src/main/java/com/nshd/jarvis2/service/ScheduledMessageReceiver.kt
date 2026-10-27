package com.nshd.jarvis2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nshd.jarvis2.MainActivity
import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.core.BrainStore
import com.nshd.jarvis2.core.ScheduledAlarms

/**
 * v2.0.0 (#51): a scheduled message's alarm fired. The draft is
 * marked due and a notification is posted — tapping it opens Jarvis
 * on the due-draft dialog, where "Send now" re-enters the normal
 * always-confirm send flow (recipient + full text gate at fire
 * time). Nothing is ever sent straight from the alarm.
 */
class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduledAlarms.ACTION_FIRE) return
        val id = intent.getLongExtra(ScheduledAlarms.EXTRA_ID, -1L)
        if (id < 0) return
        val item = BrainStore.getScheduled(context, id) ?: return
        BrainStore.markScheduledDue(context, id)
        AppLog.i("Brain", "scheduled message #$id due")
        postDueNotification(context, item)
    }

    private fun postDueNotification(
        context: Context,
        item: BrainStore.ScheduledMessage
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Scheduled messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "A scheduled message draft is due to review and send"
                }
            )
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_SCHEDULED_ID, item.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            item.id.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Scheduled message due")
            // F17: lock-screen privacy — the full draft must not sit
            // on the lock screen; it stays readable once unlocked.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentText("To ${item.contact}: \"${item.message.take(60)}\"")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("To ${item.contact}:\n\n\"${item.message}\"")
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(item.id.toInt(), notification) }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_scheduled"
    }
}

package com.nshd.jarvis2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Floating overlay skeleton (Level 1): runs as a specialUse foreground
 * service and shows a small "J" bubble when the overlay permission is
 * granted. The full 12-avatar set, drag/edge-snap and the 4-step Task HUD
 * land in Level 5.
 */
class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        showBubbleIfPermitted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeBubble()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText("Overlay ready")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showBubbleIfPermitted() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        val bubble = TextView(this).apply {
            text = "J"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFCE470F.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            BUBBLE_SIZE_PX,
            BUBBLE_SIZE_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }
        try {
            wm.addView(bubble, params)
            bubbleView = bubble
        } catch (e: Exception) {
            // Overlay revoked between check and add — fail quietly in L1.
            stopSelf()
        }
    }

    private fun removeBubble() {
        val view = bubbleView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // Already detached.
        }
        bubbleView = null
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val BUBBLE_SIZE_PX = 132
    }
}

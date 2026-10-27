package com.nshd.jarvis2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.nshd.jarvis2.MainActivity
import com.nshd.jarvis2.core.AppLog

/**
 * v2.0.4 "Live Bridge" — foreground-service host for [BridgeServer].
 * Started only when the user flips Settings → Live Bridge ON; the
 * toggle OFF (or the notification's Stop action) releases the port.
 * Follows the codebase's existing foreground-service pattern
 * (FloatingOverlayService): specialUse type, its own channel, and a
 * stop action on the notification.
 *
 * A bind failure (port already in use) does NOT crash: the service
 * stays up with [lastError] set, and Settings shows the error line.
 */
class BridgeService : Service() {

    private var server: BridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat("Jarvis bridge running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A restart (port/token change) must release the old socket
        // first — otherwise the new bind fails with "port in use".
        runCatching { server?.stop() }
        server = null
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_PORT, 8765)
        var token = prefs.getString(KEY_TOKEN, "") ?: ""
        if (token.isBlank()) {
            // Belt and braces: MainActivity generates on enable, but a
            // service started by any other path must never serve with
            // an empty token (empty tokens are rejected by the server,
            // so this keeps the bridge usable, not open).
            token = BridgeServer.generateToken()
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
        val server = BridgeServer(applicationContext)
        this.server = server
        val started = server.start(
            port = port,
            token = {
                applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_TOKEN, "") ?: ""
            },
            remoteConfirm = {
                applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_REMOTE_CONFIRM, false)
            }
        )
        running = true
        boundPort = server.boundPort
        lastError = server.lastError
        if (!started) {
            updateNotification("Jarvis bridge error: ${server.lastError ?: "?"}")
        } else {
            updateNotification("Jarvis bridge running on port ${server.boundPort}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        running = false
        lastError = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, BridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
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

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "com.nshd.jarvis2.action.STOP_BRIDGE"
        private const val CHANNEL_ID = "jarvis_bridge"
        private const val NOTIFICATION_ID = 1007
        private const val PREFS_NAME = "jarvis_settings"
        private const val KEY_PORT = "bridge_port"
        private const val KEY_TOKEN = "bridge_token"
        private const val KEY_REMOTE_CONFIRM = "bridge_remote_confirm"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var boundPort: Int = 0
            private set

        fun start(context: Context) {
            if (running) return
            runCatching {
                context.startForegroundService(Intent(context, BridgeService::class.java))
            }.onFailure {
                AppLog.w("Bridge", "service start failed: ${it.message ?: "?"}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BridgeService::class.java))
            }
            // onDestroy clears the flags; clear eagerly too so the
            // Settings toggle state never shows a stale "running".
            running = false
        }

        fun isRunning(): Boolean = running
    }
}

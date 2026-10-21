package com.nshd.jarvis2.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import com.nshd.jarvis2.MainActivity
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * MediaProjection screen share (Level 5): captures the display at ~1 FPS
 * into [ScreenShareState] as JPEG q70 frames (width ≤ 720), following
 * the Tech GPTx Part 5/6 pattern (VirtualDisplay + ImageReader, 1000 ms
 * minimum frame interval).
 *
 * Started only after the user grants the system consent dialog; the
 * resultCode + consent Intent arrive as start extras. Runs as a
 * mediaProjection foreground service with a Stop action.
 */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var lastFrameAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Consent revoked / system stopped the projection.
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startForegroundCompat()
        if (projection == null) {
            startCapture(resultCode, data)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseCapture()
        ScreenShareState.setActive(false)
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: run {
            stopSelf()
            return
        }
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, null)

        val metrics = resources.displayMetrics
        val width = minOf(MAX_WIDTH, metrics.widthPixels)
        val height = (metrics.heightPixels * (width.toFloat() / metrics.widthPixels))
            .roundToInt().coerceAtLeast(1)
        val density = metrics.densityDpi

        val thread = HandlerThread("JarvisScreenShare").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                lastFrameAt = now
                publishFrame(image, width, height)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "JarvisScreenShare",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        ScreenShareState.setActive(true)
    }

    private fun publishFrame(image: android.media.Image, width: Int, height: Int) {
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding == 0) bitmap else {
                val c = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                scaled = c
                c
            }
            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()
            if (bytes.isNotEmpty()) {
                ScreenShareState.publish(
                    bytes,
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                )
            }
        } catch (e: Exception) {
            // A dropped frame is fine at 1 FPS; the next one replaces it.
        } finally {
            if (scaled != null && scaled !== bitmap) scaled?.recycle()
            bitmap?.recycle()
        }
    }

    private fun releaseCapture() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            // Already released.
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (e: Exception) {
            // Already stopped.
        }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis screen share",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis is watching the screen")
            .setContentText("Sharing ~1 frame per second for vision targeting. Tap Stop to end.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.nshd.jarvis2.action.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "jarvis_screen_share"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_WIDTH = 720
        private const val JPEG_QUALITY = 70
        private const val FRAME_INTERVAL_MS = 1000L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

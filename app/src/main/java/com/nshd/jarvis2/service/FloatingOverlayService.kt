package com.nshd.jarvis2.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.nshd.jarvis2.MainActivity
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.ui.AvatarCatalog
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating overlay (Level 5): the chosen companion avatar floats over
 * other apps as a bare figure on transparency — NO background disc or
 * card behind it — plus the 4-step Task HUD (Goal, Recent Steps, Running
 * Indicator, Progress) fed by [TaskState].
 *
 * Interactions (locked preferences):
 *  - single-tap: abort the running task, or (idle) open the Voice screen
 *  - double-tap: expand/collapse the Task HUD
 *  - drag + release: snap to the nearest screen edge
 *
 * Built with plain Android Views on purpose: Compose is not hosted in
 * this window. Flow collection happens on the service's main-thread
 * coroutine scope, so view updates are already on the UI thread.
 */
class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var bubbleView: ImageView? = null
    private var hudPanel: LinearLayout? = null
    private var hudGoalView: TextView? = null
    private var hudStepViews: List<TextView> = emptyList()
    private var hudProgressBar: ProgressBar? = null
    private var hudStatusView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ValueAnimator? = null

    @Volatile
    private var wasTaskActive = false

    // ── Drag / tap bookkeeping ─────────────────────────────────────────
    private var downRawX = 0f
    private var downRawY = 0f
    private var startParamX = 0
    private var startParamY = 0
    private var downAtMs = 0L
    private var dragging = false
    private var lastTapAtMs = 0L
    private var pendingSingleTap: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        startForegroundCompat(buildNotificationText())
        showOverlayIfPermitted()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-read the chosen avatar so picking a new one refreshes the bubble.
        refreshAvatar()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        scope.cancel()
        stopPulse()
        pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
        removeOverlay()
        super.onDestroy()
    }

    // ── Overlay construction ───────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showOverlayIfPermitted() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm
        val bubbleSize = dp(56)

        val bubble = ImageView(this).apply {
            // Transparent container: the avatar drawable floats as-is,
            // with NO background drawable/disc behind it (design rule).
            setImageResource(currentAvatarRes())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Jarvis floating avatar"
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        }

        val hud = buildHud(::dp)
        hud.visibility = View.GONE

        val root = FrameLayout(this).apply {
            // Fully transparent root — nothing paints behind the avatar.
            setBackgroundColor(0x00000000)
            addView(bubble)
            addView(hud)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }
        params = lp
        bubbleView = bubble
        hudPanel = hud
        rootView = root
        bubble.setOnTouchListener { _, event -> handleBubbleTouch(event) }
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            // Overlay permission revoked between check and add.
            stopSelf()
        }
    }

    private fun buildHud(dp: (Int) -> Int): LinearLayout {
        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(0xF2FFFFFF.toInt())
        }
        val steps = mutableListOf<TextView>()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg // The HUD is a panel — its card is allowed.
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(248), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(62)
            }
        }
        val goalView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF1D2733.toInt())
            maxLines = 2
        }
        panel.addView(goalView)
        hudGoalView = goalView
        repeat(4) {
            val stepView = TextView(this).apply {
                textSize = 12f
                setTextColor(0xFF44536A.toInt())
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            }
            steps += stepView
            panel.addView(stepView)
        }
        hudStepViews = steps
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        panel.addView(progress)
        hudProgressBar = progress
        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFCE470F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        panel.addView(status)
        hudStatusView = status
        refreshHud()
        return panel
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // Already detached.
        }
        rootView = null
        bubbleView = null
        hudPanel = null
    }

    // ── State observation ──────────────────────────────────────────────

    private fun observeState() {
        scope.launch {
            TaskState.goal.collect { refreshHud() }
        }
        scope.launch {
            TaskState.steps.collect { refreshHud() }
        }
        scope.launch {
            TaskState.progress.collect { refreshHud() }
        }
        scope.launch {
            TaskState.lastOutcome.collect { refreshHud() }
        }
        scope.launch {
            TaskState.active.collect { taskActive ->
                refreshHud()
                updateNotification()
                if (taskActive && !wasTaskActive) bounceBubble()
                wasTaskActive = taskActive
                updatePulse()
            }
        }
        scope.launch {
            VoiceSessionManager.uiState.collect { updatePulse() }
        }
    }

    private fun refreshHud() {
        val goal = TaskState.goal.value
        hudGoalView?.text = if (goal.isBlank()) "No task running" else "🎯 $goal"
        val steps = TaskState.steps.value
        hudStepViews.forEachIndexed { index, view ->
            val step = steps.getOrNull(index)
            view.text = step.orEmpty()
            view.visibility = if (step == null) View.GONE else View.VISIBLE
        }
        val progress = TaskState.progress.value
        hudProgressBar?.let { bar ->
            if (progress < 0f) {
                bar.isIndeterminate = TaskState.active.value
                if (!TaskState.active.value) bar.progress = 0
            } else {
                bar.isIndeterminate = false
                bar.progress = (progress * 100).toInt()
            }
        }
        hudStatusView?.text = when {
            TaskState.active.value -> "● Running — tap avatar to abort"
            TaskState.lastOutcome.value != null -> TaskState.lastOutcome.value
            else -> "Idle — tap avatar for voice, double-tap hides this panel"
        }
    }

    private fun updatePulse() {
        val voiceActive = VoiceSessionManager.uiState.value.status != VoiceStatus.IDLE &&
            VoiceSessionManager.uiState.value.status != VoiceStatus.ERROR
        if (TaskState.active.value || voiceActive) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        val bubble = bubbleView ?: return
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.12f).apply {
            duration = 620
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                bubble.scaleX = scale
                bubble.scaleY = scale
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        bubbleView?.scaleX = 1f
        bubbleView?.scaleY = 1f
    }

    private fun bounceBubble() {
        val bubble = bubbleView ?: return
        if (pulseAnimator != null) return // pulse already communicates activity
        ValueAnimator.ofFloat(1f, 1.28f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                bubble.scaleX = scale
                bubble.scaleY = scale
            }
            start()
        }
    }

    // ── Touch: drag, single-tap, double-tap ────────────────────────────

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        val lp = params ?: return false
        val wm = windowManager ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startParamX = lp.x
                startParamY = lp.y
                downAtMs = System.currentTimeMillis()
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > TOUCH_SLOP_PX || abs(dy) > TOUCH_SLOP_PX)) {
                    dragging = true
                    pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
                    pendingSingleTap = null
                }
                if (dragging) {
                    lp.x = startParamX + dx.toInt()
                    lp.y = startParamY + dy.toInt()
                    try {
                        wm.updateViewLayout(rootView, lp)
                    } catch (e: Exception) {
                        // Window detached mid-drag.
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    snapToEdge()
                } else if (System.currentTimeMillis() - downAtMs < TAP_MAX_MS) {
                    handleTap()
                }
                return true
            }
        }
        return false
    }

    private fun handleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAtMs < DOUBLE_TAP_MS) {
            // Double-tap: toggle the HUD panel.
            lastTapAtMs = 0L
            pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
            pendingSingleTap = null
            toggleHud()
            return
        }
        lastTapAtMs = now
        val action = Runnable {
            pendingSingleTap = null
            lastTapAtMs = 0L
            onSingleTap()
        }
        pendingSingleTap = action
        mainHandler.postDelayed(action, DOUBLE_TAP_MS)
    }

    private fun onSingleTap() {
        if (TaskState.active.value) {
            TaskState.abort()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_OPEN_VOICE, true)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Activity launch blocked (rare OEM policy) — stay floating.
            }
        }
    }

    private fun toggleHud() {
        val hud = hudPanel ?: return
        hud.visibility = if (hud.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (hud.visibility == View.VISIBLE) refreshHud()
    }

    private fun snapToEdge() {
        val lp = params ?: return
        val wm = windowManager ?: return
        val root = rootView ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = if (root.width > 0) root.width else bubbleView?.width ?: 0
        val center = lp.x + viewWidth / 2
        val targetX = if (center <= screenWidth / 2) 0 else (screenWidth - viewWidth).coerceAtLeast(0)
        val animator = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                try {
                    wm.updateViewLayout(root, lp)
                } catch (e: Exception) {
                    anim.cancel()
                }
            }
        }
        animator.start()
    }

    // ── Avatar + notification ──────────────────────────────────────────

    private fun currentAvatarRes(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AvatarCatalog.byId(prefs.getString(KEY_AVATAR_ID, AvatarCatalog.DEFAULT_ID)).resId
    }

    private fun refreshAvatar() {
        bubbleView?.setImageResource(currentAvatarRes())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotificationText(): String {
        val goal = TaskState.goal.value
        return if (TaskState.active.value && goal.isNotBlank()) {
            "Running: $goal"
        } else {
            "Jarvis is floating • tap bubble for voice"
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(buildNotificationText())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundCompat() {
        startForegroundCompat(buildNotificationText())
    }

    private fun startForegroundCompat(text: String) {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(text)
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

    companion object {
        private const val CHANNEL_ID = "jarvis_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "jarvis_settings"
        private const val KEY_AVATAR_ID = "avatar_id"
        private const val TOUCH_SLOP_PX = 14f
        private const val TAP_MAX_MS = 350L
        private const val DOUBLE_TAP_MS = 320L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }

        fun isRunning(): Boolean = running

        @Volatile
        private var running = false
    }
}

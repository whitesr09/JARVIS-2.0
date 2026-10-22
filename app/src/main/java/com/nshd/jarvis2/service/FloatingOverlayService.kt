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
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
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
import com.nshd.jarvis2.brain.TaskQueue
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.ui.AppTheme
import com.nshd.jarvis2.ui.AvatarCatalog
import com.nshd.jarvis2.ui.HudPalette
import com.nshd.jarvis2.ui.ThemePalette
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
 * Interactions (locked preferences, F3):
 *  - single-tap: abort the running task; otherwise toggle a BACKGROUND
 *    voice session (never opens the app)
 *  - double-tap: expand/collapse the Task HUD — the HUD's own
 *    "Open Jarvis app" button is the only overlay element that
 *    launches MainActivity
 *  - drag + release: snap to the nearest screen edge
 *
 * F3 HUD: the panel is a task-queue view — header, up to 6 task rows
 * from [TaskQueue], the current task's last 3 steps ([TaskState]),
 * and a footer progress bar + "X of Y done". With an empty queue but a
 * single task running it falls back to that one task as a single row.
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
    private var hudHeaderView: TextView? = null
    private var hudTaskGlyphViews: List<TextView> = emptyList()
    private var hudTaskTitleViews: List<TextView> = emptyList()
    private var hudTaskRowViews: List<LinearLayout> = emptyList()
    private var hudMoreView: TextView? = null
    private var hudOpenAppView: TextView? = null
    private var hudOpenAppBackground: GradientDrawable? = null
    private var hudStepViews: List<TextView> = emptyList()
    private var hudProgressBar: ProgressBar? = null
    private var hudStatusView: TextView? = null
    private var hudBackground: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null

    /** Current theme palette for the HUD (D3) — re-read on every start. */
    private var palette: HudPalette = ThemePalette.hudPalette(AppTheme.DARK, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ValueAnimator? = null

    @Volatile
    private var wasTaskActive = false

    /**
     * F2: the bubble (and HUD) hide while Jarvis's own app is in the
     * foreground — floating over itself looks broken. [AppForeground]
     * notifies on activity start/stop edges.
     */
    private val foregroundListener: (Boolean) -> Unit = { foreground ->
        mainHandler.post { applyForegroundVisibility(foreground) }
    }

    private fun applyForegroundVisibility(appInForeground: Boolean) {
        rootView?.visibility = if (appInForeground) View.GONE else View.VISIBLE
    }

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
        palette = readPalette()
        createNotificationChannel()
        startForegroundCompat(buildNotificationText())
        showOverlayIfPermitted()
        applyForegroundVisibility(AppForeground.isForeground())
        AppForeground.addListener(foregroundListener)
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-read the chosen avatar so picking a new one refreshes the bubble.
        refreshAvatar()
        // Re-read the theme palette too — MainActivity re-sends a start
        // when the theme changes, so the HUD follows without a restart
        // of the whole service (D3 theme-blind HUD fix).
        applyPalette(readPalette())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        AppForeground.removeListener(foregroundListener)
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
            setColor(palette.background)
            setStroke(dp(1), palette.border)
        }
        hudBackground = panelBg
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg // The HUD is a panel — its card is allowed.
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(264), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(62)
            }
        }
        // Header: "<AssistantName> • Tasks"
        val header = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.textPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panel.addView(header)
        hudHeaderView = header
        hudGoalView = header // legacy alias: header replaced the goal line

        // Task rows: up to MAX_HUD_TASKS, glyph + 1-line ellipsized title.
        val glyphs = mutableListOf<TextView>()
        val titles = mutableListOf<TextView>()
        val rows = mutableListOf<LinearLayout>()
        repeat(MAX_HUD_TASKS) {
            val glyph = TextView(this).apply {
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val title = TextView(this).apply {
                textSize = 12f
                setTextColor(palette.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                addView(glyph)
                addView(title)
            }
            glyphs += glyph
            titles += title
            rows += row
            panel.addView(row)
        }
        hudTaskGlyphViews = glyphs
        hudTaskTitleViews = titles
        hudTaskRowViews = rows

        // Overflow line: "…and N more"
        val more = TextView(this).apply {
            textSize = 11f
            setTextColor(palette.textSecondary)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3); leftMargin = dp(18) }
        }
        panel.addView(more)
        hudMoreView = more

        // Current task's last 3 steps (small text).
        val steps = mutableListOf<TextView>()
        repeat(MAX_HUD_STEPS) {
            val stepView = TextView(this).apply {
                textSize = 11f
                setTextColor(palette.textSecondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3); leftMargin = dp(18) }
            }
            steps += stepView
            panel.addView(stepView)
        }
        hudStepViews = steps

        // Footer: overall progress bar + "X of Y done" status line.
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(palette.accent)
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(palette.progressTrack)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        panel.addView(progress)
        hudProgressBar = progress
        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(palette.accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        panel.addView(status)
        hudStatusView = status

        // The ONLY overlay element allowed to launch MainActivity (F3).
        val openBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(palette.accent)
        }
        hudOpenAppBackground = openBg
        val openApp = TextView(this).apply {
            text = "Open Jarvis app"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = openBg
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) }
            setOnClickListener { openJarvisApp() }
        }
        panel.addView(openApp)
        hudOpenAppView = openApp
        refreshHud()
        return panel
    }

    /** HUD-button-only app launch (F3) — never used from the tap path. */
    private fun openJarvisApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Activity launch blocked (rare OEM policy) — stay floating.
        }
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
        hudBackground = null
        hudOpenAppBackground = null
        hudTaskGlyphViews = emptyList()
        hudTaskTitleViews = emptyList()
        hudTaskRowViews = emptyList()
    }

    // ── State observation ──────────────────────────────────────────────

    private fun observeState() {
        scope.launch {
            TaskQueue.tasks.collect { refreshHud() }
        }
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

    /** One display row in the HUD task list (queue item or fallback). */
    private data class HudRow(val glyph: String, val glyphColor: Int, val title: String, val running: Boolean)

    private fun glyphFor(statusName: String): Pair<String, Int> = when (statusName) {
        "DONE" -> "✓" to GLYPH_DONE
        "RUNNING" -> "▶" to palette.accent
        "FAILED" -> "✕" to GLYPH_FAILED
        "SKIPPED" -> "•" to palette.textSecondary
        else -> "•" to palette.textSecondary // QUEUED and anything new
    }

    private fun refreshHud() {
        hudHeaderView?.text = "${assistantName()} • Tasks"
        val queue = TaskQueue.tasks.value
        val rows: List<HudRow>
        val total: Int
        val done: Int
        val overallProgress: Float // -1f = indeterminate
        if (queue.isNotEmpty()) {
            rows = queue.take(MAX_HUD_TASKS).map { item ->
                val (glyph, color) = glyphFor(item.status.name)
                HudRow(glyph, color, item.title, item.status.name == "RUNNING")
            }
            total = queue.size
            done = queue.count { it.status.name == "DONE" }
            overallProgress = if (total > 0) done.toFloat() / total else -1f
            val extra = queue.size - MAX_HUD_TASKS
            hudMoreView?.let { more ->
                more.text = if (extra > 0) "…and $extra more" else ""
                more.visibility = if (extra > 0) View.VISIBLE else View.GONE
            }
        } else if (TaskState.goal.value.isNotBlank() || TaskState.active.value) {
            // Fallback (old behaviour): the single TaskState task as one row.
            val goal = TaskState.goal.value.ifBlank { "Task" }
            val running = TaskState.active.value
            val failed = !running && TaskState.lastOutcome.value?.startsWith("❌") == true
            val (glyph, color) = when {
                running -> glyphFor("RUNNING")
                failed -> glyphFor("FAILED")
                !running && TaskState.lastOutcome.value != null -> glyphFor("DONE")
                else -> glyphFor("QUEUED")
            }
            rows = listOf(HudRow(glyph, color, goal, running))
            total = 1
            done = if (!running && !failed && TaskState.lastOutcome.value != null) 1 else 0
            overallProgress = TaskState.progress.value
            hudMoreView?.visibility = View.GONE
        } else {
            rows = emptyList()
            total = 0
            done = 0
            overallProgress = -1f
            hudMoreView?.visibility = View.GONE
        }
        hudTaskRowViews.forEachIndexed { index, rowView ->
            val row = rows.getOrNull(index)
            rowView.visibility = if (row == null) View.GONE else View.VISIBLE
            if (row != null) {
                hudTaskGlyphViews.getOrNull(index)?.let { glyph ->
                    glyph.text = row.glyph
                    glyph.setTextColor(row.glyphColor)
                }
                hudTaskTitleViews.getOrNull(index)?.let { title ->
                    title.text = row.title
                    title.setTextColor(palette.textPrimary)
                }
            }
        }
        // Current task's last 3 steps (TaskState), small text.
        val steps = TaskState.steps.value.takeLast(MAX_HUD_STEPS)
        hudStepViews.forEachIndexed { index, view ->
            val step = steps.getOrNull(index)
            view.text = step.orEmpty()
            view.visibility = if (step == null) View.GONE else View.VISIBLE
        }
        hudProgressBar?.let { bar ->
            if (overallProgress < 0f) {
                bar.isIndeterminate = TaskState.active.value ||
                    queue.any { it.status.name == "RUNNING" }
                if (!bar.isIndeterminate) bar.progress = 0
            } else {
                bar.isIndeterminate = false
                bar.progress = (overallProgress * 100).toInt().coerceIn(0, 100)
            }
        }
        hudStatusView?.text = when {
            total > 0 -> "$done of $total done"
            TaskState.lastOutcome.value != null -> TaskState.lastOutcome.value
            else -> "0 of 0 done — idle"
        }
    }

    private fun assistantName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ASSISTANT_NAME, "Jarvis")?.takeIf { it.isNotBlank() } ?: "Jarvis"
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

    /**
     * F3: single-tap NEVER launches the app. With a task running it
     * aborts (unchanged); otherwise it toggles a background voice
     * session — start one via [VoiceSessionManager.startFromWake] when
     * idle, stop the live one otherwise.
     */
    private fun onSingleTap() {
        if (TaskState.active.value) {
            TaskState.abort()
            return
        }
        val status = VoiceSessionManager.uiState.value.status
        val voiceActive = status != VoiceStatus.IDLE && status != VoiceStatus.ERROR
        if (voiceActive) {
            VoiceSessionManager.stop()
        } else {
            try {
                VoiceSessionManager.startFromWake(this)
            } catch (e: Exception) {
                // Mic permission / engine failure: stay floating; the
                // session state surfaces the error in-app, the wake
                // path owns the failure notification.
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

    // ── Theme palette (D3) ─────────────────────────────────────────────

    /** Reads the saved theme (+ custom hex) from the app's settings prefs. */
    private fun readPalette(): HudPalette {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawTheme = prefs.getString(KEY_THEME, AppTheme.DARK.name) ?: AppTheme.DARK.name
        // Mirror MainActivity's F4 migration: retired PURPLE reads as DARK.
        val theme = if (rawTheme == "PURPLE") {
            AppTheme.DARK
        } else {
            try {
                AppTheme.valueOf(rawTheme)
            } catch (e: IllegalArgumentException) {
                AppTheme.DARK
            }
        }
        return ThemePalette.hudPalette(theme, prefs.getString(KEY_CUSTOM_BG, null))
    }

    /** Repaints the HUD with [newPalette]; a no-op before the HUD exists. */
    private fun applyPalette(newPalette: HudPalette) {
        palette = newPalette
        hudBackground?.let { bg ->
            bg.setColor(newPalette.background)
            bg.setStroke(dp(1), newPalette.border)
        }
        hudGoalView?.setTextColor(newPalette.textPrimary)
        hudHeaderView?.setTextColor(newPalette.textPrimary)
        hudTaskTitleViews.forEach { it.setTextColor(newPalette.textPrimary) }
        hudMoreView?.setTextColor(newPalette.textSecondary)
        hudStepViews.forEach { it.setTextColor(newPalette.textSecondary) }
        hudStatusView?.setTextColor(newPalette.accent)
        hudOpenAppBackground?.setColor(newPalette.accent)
        hudProgressBar?.let { bar ->
            bar.progressTintList = ColorStateList.valueOf(newPalette.accent)
            bar.indeterminateTintList = ColorStateList.valueOf(newPalette.accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(newPalette.progressTrack)
        }
        // Glyph colours that follow the palette (▶/•) re-derive on the
        // next refresh; force one now so a theme change recolours rows
        // immediately (✓/✕ keep their fixed success/failure colours).
        refreshHud()
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
        private const val KEY_ASSISTANT_NAME = "assistant_name"
        private const val KEY_THEME = "theme"
        private const val KEY_CUSTOM_BG = "custom_bg"
        private const val MAX_HUD_TASKS = 6
        private const val MAX_HUD_STEPS = 3
        private const val GLYPH_DONE = 0xFF43A047.toInt()
        private const val GLYPH_FAILED = 0xFFE53935.toInt()
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

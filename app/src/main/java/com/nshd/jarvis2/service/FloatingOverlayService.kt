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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import com.nshd.jarvis2.brain.PlanStepState
import com.nshd.jarvis2.brain.TaskQueue
import com.nshd.jarvis2.brain.TaskStatus
import com.nshd.jarvis2.brain.tools.ConfirmGate
import com.nshd.jarvis2.brain.tools.PendingConfirm
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.ui.AppTheme
import com.nshd.jarvis2.ui.AvatarCatalog
import com.nshd.jarvis2.ui.HudPalette
import com.nshd.jarvis2.ui.MotionSettings
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

    // ── F7 task-panel views ────────────────────────────────────────────
    private var panelHeaderRow: LinearLayout? = null
    private var panelGripView: GripDotsView? = null
    private var panelGoalView: TextView? = null
    private var panelStepSpinnerViews: List<ProgressBar> = emptyList()
    private var panelResultView: TextView? = null
    private var panelQueueNoteView: TextView? = null

    /** True while the visible panel was auto-shown (vs opened by double-tap). */
    private var panelAutoShown = false
    private var panelCollapseRunnable: Runnable? = null

    // ── v1.8.0 panel additions (#53–#60) ─────────────────────────────
    // #53 mini pill (collapsed-while-running compact panel variant).
    private var miniPill: LinearLayout? = null
    private var miniPillBackground: GradientDrawable? = null
    private var miniPillSpinner: ProgressBar? = null
    private var miniPillText: TextView? = null
    private var miniPillPercent: TextView? = null
    /** True while the user collapsed the panel mid-task → pill shows. */
    private var panelMiniMode = false
    // #56 mood glyph in the panel header.
    private var panelMoodView: TextView? = null
    // #55 waveform strip in the panel header area.
    private var waveformView: WaveformView? = null
    private var waveformTicker: Runnable? = null
    // #54 Recent (history) section.
    private var historySection: LinearLayout? = null
    private var historyHeaderView: TextView? = null
    private var historyRowViews: List<LinearLayout> = emptyList()
    private var historyGlyphViews: List<TextView> = emptyList()
    private var historyTitleViews: List<TextView> = emptyList()
    private var historyResultViews: List<TextView> = emptyList()
    // #60 quick actions row.
    private var panelActionsRow: LinearLayout? = null
    private var panelStopBtn: TextView? = null
    private var panelPauseBtn: TextView? = null
    private var panelStopBackground: GradientDrawable? = null
    private var panelPauseBackground: GradientDrawable? = null
    // #58 haptic bookkeeping (last plan DONE count seen per task id).
    private var lastHapticItemId: Long = -1L
    private var lastHapticDoneCount: Int = 0
    private var panelHapticsEnabled: Boolean = true

    // ── v2.0.1: overlay appearance (avatar opacity / panel size /
    // panel background opacity) — mirrors of the JarvisSettings
    // values, re-read with the other panel settings (onCreate +
    // every onStartCommand) and applied by applyOverlayAppearance().
    private var overlayAvatarOpacity: Float = 1.0f
    private var panelSizePercent: Int = 100
    private var panelBgOpacity: Float = 0.97f
    /** Unscaled geometry captured at build time (px), for size scaling. */
    private var panelBaseWidthPx = 0
    private var panelBasePadHPx = 0
    private var panelBasePadVPx = 0
    private var pillBaseWidthPx = 0
    // #57 per-foreground-app position memory.
    private var lastForegroundPkg: String? = null
    private var foregroundPoll: Runnable? = null

    // ── F7 GateHost overlay card (rendered INSIDE the panel window) ────
    private var gateSection: LinearLayout? = null
    private var gateSectionBackground: GradientDrawable? = null
    private var gateTitleView: TextView? = null
    private var gateBodyView: TextView? = null
    private var gateTimerBar: ProgressBar? = null
    private var gatePrimaryBtn: TextView? = null
    private var gateCancelBtn: TextView? = null
    private var gatePrimaryBackground: GradientDrawable? = null
    private var gateCancelBackground: GradientDrawable? = null

    @Volatile
    private var gatePendingId: Long = -1L

    @Volatile
    private var gateCreatedAtMs: Long = 0L
    private var gateTicker: Runnable? = null

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
        com.nshd.jarvis2.core.AppLog.i("Overlay", "overlay service created")
        running = true
        palette = readPalette()
        readPanelSettings()
        createNotificationChannel()
        createWatchdogChannel()
        startForegroundCompat(buildNotificationText())
        showOverlayIfPermitted()
        applyForegroundVisibility(AppForeground.isForeground())
        AppForeground.addListener(foregroundListener)
        observeState()
        startAccessibilityWatchdog()
        startForegroundPoll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-read the chosen avatar so picking a new one refreshes the bubble.
        refreshAvatar()
        // Re-read the theme palette too — MainActivity re-sends a start
        // when the theme changes, so the HUD follows without a restart
        // of the whole service (D3 theme-blind HUD fix).
        applyPalette(readPalette())
        // #58/#24 settings (panel_haptics / reduce_motion) re-read too.
        readPanelSettings()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        com.nshd.jarvis2.core.AppLog.i("Overlay", "overlay service destroyed")
        running = false
        AppForeground.removeListener(foregroundListener)
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
        foregroundPoll?.let { mainHandler.removeCallbacks(it) }
        foregroundPoll = null
        stopWaveformTicker()
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
            // v2.0.1: avatar opacity (figure only — still plate-free).
            alpha = overlayAvatarOpacity
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        }

        val hud = buildHud(::dp)
        hud.visibility = View.GONE

        val pill = buildMiniPill(::dp)
        pill.visibility = View.GONE

        val root = FrameLayout(this).apply {
            // Fully transparent root — nothing paints behind the avatar.
            setBackgroundColor(0x00000000)
            addView(bubble)
            addView(hud)
            addView(pill)
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

    /**
     * v1.8.0 (#53): the mini pill — the panel's compact variant in the
     * SAME window. While a task is RUNNING and the user has collapsed
     * the panel, the pill shows spinner + current step (truncated) +
     * plan percent; tapping it expands the full panel again.
     */
    private fun buildMiniPill(dp: (Int) -> Int): LinearLayout {
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            // v2.0.1: the pill is a panel surface — same bg opacity.
            setColor(panelSurfaceColor())
            setStroke(dp(1), palette.border)
        }
        miniPillBackground = pillBg
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        }
        val stepText = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.textPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = dp(8) }
        }
        val percent = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        }
        pillBaseWidthPx = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                (pillBaseWidthPx * panelScale()).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(62) }
            addView(spinner)
            addView(stepText)
            addView(percent)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Tap → expand back to the full panel.
                panelMiniMode = false
                showPanel(auto = false)
                updateMiniPill()
            }
        }
        miniPill = pill
        miniPillSpinner = spinner
        miniPillText = stepText
        miniPillPercent = percent
        return pill
    }

    /**
     * F7: the floating TASK PANEL (his reference screenshot is the
     * spec): header "JARVIS" + drag grip · goal title · plan-step
     * checklist (✓ accent / spinner / faint · / ✗ red) · result line ·
     * progress bar + "<x>/<y> done · <z>%" footer · the F3 "Open
     * Jarvis app" button. The ConfirmGate card (GateHost renderer b)
     * renders INSIDE this same window — one window, no z-fighting:
     * when the panel is up the gate is a section of it; the gate
     * forces the panel open when it isn't.
     */
    private fun buildHud(dp: (Int) -> Int): LinearLayout {
        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            // v2.0.1: the user's panel background opacity over the
            // palette fill (default = the shipped ~0.97 alpha).
            setColor(panelSurfaceColor())
            setStroke(dp(1), palette.border)
        }
        hudBackground = panelBg
        // v2.0.1: panel size scales the shipped geometry — base width
        // and base padding are captured unscaled so applyOverlayAppearance
        // can re-derive them when the slider moves.
        panelBaseWidthPx = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        panelBasePadHPx = dp(14)
        panelBasePadVPx = dp(12)
        val scale = panelScale()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg // The panel is a card — its background is allowed.
            setPadding(
                (panelBasePadHPx * scale).toInt(), (panelBasePadVPx * scale).toInt(),
                (panelBasePadHPx * scale).toInt(), (panelBasePadVPx * scale).toInt()
            )
            layoutParams = FrameLayout.LayoutParams(
                (panelBaseWidthPx * scale).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(62)
            }
        }

        // ── Header row: "JARVIS" bold + drag grip (drag by header) ────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // #56: mood glyph next to the title (follows plan/task state).
        val mood = TextView(this).apply {
            text = "🤖"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) }
        }
        panelMoodView = mood
        headerRow.addView(mood)
        val header = TextView(this).apply {
            text = "JARVIS"
            textSize = 13f
            setTextColor(palette.textPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = 0.14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val grip = GripDotsView(this, palette.textSecondary)
        grip.layoutParams = LinearLayout.LayoutParams(dp(14), dp(20))
        headerRow.addView(header)
        headerRow.addView(grip)
        headerRow.setOnTouchListener { _, event -> handlePanelTouch(event) }
        panel.addView(headerRow)
        panelHeaderRow = headerRow
        panelGripView = grip
        hudHeaderView = header

        // #55: waveform strip under the header — visible only while a
        // voice session is LISTENING (fed from VSM.amplitude, see
        // updateWaveform / the 80 ms ticker).
        val wave = WaveformView(this, palette.accent)
        wave.visibility = View.GONE
        wave.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(18)
        ).apply { topMargin = dp(4) }
        panel.addView(wave)
        waveformView = wave

        // ── Goal title (current task, ≤2 lines, semibold) ─────────────
        val goal = TextView(this).apply {
            textSize = 15f
            setTextColor(palette.textPrimary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        panel.addView(goal)
        panelGoalView = goal
        hudGoalView = goal

        // ── Checklist rows: [glyph|spinner slot][label] ───────────────
        val glyphs = mutableListOf<TextView>()
        val spinners = mutableListOf<ProgressBar>()
        val titles = mutableListOf<TextView>()
        val rows = mutableListOf<LinearLayout>()
        repeat(MAX_HUD_TASKS) {
            val glyph = TextView(this).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22))
            }
            val spinner = ProgressBar(
                this, null, android.R.attr.progressBarStyleSmall
            ).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(palette.accent)
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                    gravity = Gravity.CENTER
                }
            }
            val slot = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                addView(glyph)
                addView(spinner)
            }
            val title = TextView(this).apply {
                textSize = 13f
                setTextColor(palette.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { leftMargin = dp(6) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
                addView(slot)
                addView(title)
            }
            glyphs += glyph
            spinners += spinner
            titles += title
            rows += row
            panel.addView(row)
        }
        hudTaskGlyphViews = glyphs
        panelStepSpinnerViews = spinners
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
            ).apply { topMargin = dp(3); leftMargin = dp(28) }
        }
        panel.addView(more)
        hudMoreView = more

        // F6 fallback step lines (current task's live step text when
        // no plan exists — e.g. a Live tool turn).
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
                ).apply { topMargin = dp(3); leftMargin = dp(28) }
            }
            steps += stepView
            panel.addView(stepView)
        }
        hudStepViews = steps

        // ── Result line (Part D completion recognition) ───────────────
        val result = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.accent)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) }
        }
        panel.addView(result)
        panelResultView = result

        // ── Progress bar ───────────────────────────────────────────────
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(palette.accent)
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(palette.progressTrack)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) }
        }
        panel.addView(progress)
        hudProgressBar = progress

        // ── Footer: "<x>/<y> done · <z>%" left, "Task i of n" right ───
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(palette.accent)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val queueNote = TextView(this).apply {
            textSize = 11f
            setTextColor(palette.textSecondary)
        }
        footerRow.addView(status)
        footerRow.addView(queueNote)
        panel.addView(footerRow)
        hudStatusView = status
        panelQueueNoteView = queueNote

        // ── v1.8.0 (#60): quick actions (Stop / Pause-Resume) ────────
        panel.addView(buildActionsRow(dp))

        // ── v1.8.0 (#54): Recent — last finished tasks ───────────────
        panel.addView(buildHistorySection(dp))

        // ── GateHost card (Part A2b): inside the panel window ─────────
        panel.addView(buildGateSection(dp))

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
            setTextColor(onAccentColor(palette.accent))
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

    /**
     * v1.8.0 (#60): the expanded panel's quick-actions row, visible
     * while a task runs. Stop = cooperative stop flag + abort (the
     * pipeline notices [TaskState.requestStop] between steps; abort
     * cancels its coroutine and aborts the queue). Pause/Resume
     * toggles [TaskQueue.paused], which holds QUEUED items from being
     * promoted; the button only shows when queued items exist.
     */
    private fun buildActionsRow(dp: (Int) -> Int): LinearLayout {
        val stopBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(GLYPH_FAILED)
        }
        panelStopBackground = stopBg
        val pauseBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(palette.progressTrack)
            setStroke(dp(1), palette.border)
        }
        panelPauseBackground = pauseBg
        val stop = TextView(this).apply {
            text = "Stop"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = stopBg
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnClickListener {
                com.nshd.jarvis2.core.AppLog.i("Hands", "panel Stop pressed")
                TaskState.requestStop()
                TaskState.abort()
                refreshHud()
            }
        }
        val pause = TextView(this).apply {
            text = "Pause"
            textSize = 12f
            setTextColor(palette.textPrimary)
            gravity = Gravity.CENTER
            background = pauseBg
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = dp(8) }
            setOnClickListener {
                if (TaskQueue.paused) TaskQueue.resumeQueue() else TaskQueue.pauseQueue()
                refreshHud()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(stop)
            addView(pause)
        }
        panelActionsRow = row
        panelStopBtn = stop
        panelPauseBtn = pause
        return row
    }

    /**
     * v1.8.0 (#54): the "Recent" section — up to 5 rows from
     * [TaskQueue.history], each title + result line (always visible
     * as the subtitle) with a ✓/✕ outcome glyph. Tapping a row logs
     * the open (no log viewer is built) and expands its result text
     * inline from 1 to 3 lines.
     */
    private fun buildHistorySection(dp: (Int) -> Int): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) }
        }
        val header = TextView(this).apply {
            text = "Recent"
            textSize = 11f
            setTextColor(palette.textSecondary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
        }
        section.addView(header)
        historyHeaderView = header
        val rows = mutableListOf<LinearLayout>()
        val glyphs = mutableListOf<TextView>()
        val titles = mutableListOf<TextView>()
        val results = mutableListOf<TextView>()
        repeat(HISTORY_ROWS) { index ->
            val glyph = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            val title = TextView(this).apply {
                textSize = 12f
                setTextColor(palette.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val result = TextView(this).apply {
                textSize = 11f
                setTextColor(palette.textSecondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { leftMargin = dp(4) }
                addView(title)
                addView(result)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                addView(glyph)
                addView(column)
                setOnClickListener {
                    val item = TaskQueue.history.value.getOrNull(index) ?: return@setOnClickListener
                    com.nshd.jarvis2.core.AppLog.i(
                        "Hands", "Panel history opened: ${item.title}"
                    )
                    // Inline expand of the result text (1 → 3 lines).
                    result.maxLines = if (result.maxLines == 1) 3 else 1
                }
            }
            rows += row
            glyphs += glyph
            titles += title
            results += result
            section.addView(row)
        }
        historyRowViews = rows
        historyGlyphViews = glyphs
        historyTitleViews = titles
        historyResultViews = results
        historySection = section
        return section
    }

    /**
     * F7 GateHost renderer (b): the ConfirmGate card hosted INSIDE the
     * panel window. Title = the gate's first line ("Send WhatsApp
     * message to Mom (+91…)?"), body = the rest (the full message
     * text), a draining 30 s timer bar, and [Send]/[Allow] + [Cancel]
     * buttons that complete the SAME ConfirmGate request the in-app
     * dialog and the voice intercept race to answer.
     */
    private fun buildGateSection(dp: (Int) -> Int): LinearLayout {
        val sectionBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(palette.progressTrack)
            setStroke(dp(1), palette.border)
        }
        gateSectionBackground = sectionBg
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = sectionBg
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) }
        }
        val title = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        section.addView(title)
        gateTitleView = title
        val body = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.textSecondary)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        section.addView(body)
        gateBodyView = body
        val timer = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 100
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(palette.background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) }
        }
        section.addView(timer)
        gateTimerBar = timer

        val primaryBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(palette.accent)
        }
        gatePrimaryBackground = primaryBg
        val cancelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9).toFloat()
            setColor(palette.background)
            setStroke(dp(1), palette.border)
        }
        gateCancelBackground = cancelBg
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val primary = TextView(this).apply {
            text = "Allow"
            textSize = 13f
            setTextColor(onAccentColor(palette.accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = primaryBg
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnClickListener {
                val id = gatePendingId
                if (id != -1L) ConfirmGate.complete(id, approved = true)
            }
        }
        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 13f
            setTextColor(palette.textPrimary)
            gravity = Gravity.CENTER
            background = cancelBg
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = dp(8) }
            setOnClickListener {
                val id = gatePendingId
                if (id != -1L) ConfirmGate.complete(id, approved = false)
            }
        }
        buttons.addView(primary)
        buttons.addView(cancel)
        section.addView(buttons)
        gatePrimaryBtn = primary
        gateCancelBtn = cancel
        gateSection = section
        return section
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
        stopGateTicker()
        stopWaveformTicker()
        cancelPanelCollapse()
        gatePendingId = -1L
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
        panelHeaderRow = null
        panelGripView = null
        panelGoalView = null
        panelStepSpinnerViews = emptyList()
        panelResultView = null
        panelQueueNoteView = null
        gateSection = null
        gateSectionBackground = null
        gateTitleView = null
        gateBodyView = null
        gateTimerBar = null
        gatePrimaryBtn = null
        gateCancelBtn = null
        gatePrimaryBackground = null
        gateCancelBackground = null
        miniPill = null
        miniPillBackground = null
        miniPillSpinner = null
        miniPillText = null
        miniPillPercent = null
        panelMiniMode = false
        panelMoodView = null
        waveformView = null
        historySection = null
        historyHeaderView = null
        historyRowViews = emptyList()
        historyGlyphViews = emptyList()
        historyTitleViews = emptyList()
        historyResultViews = emptyList()
        panelActionsRow = null
        panelStopBtn = null
        panelPauseBtn = null
        panelStopBackground = null
        panelPauseBackground = null
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
            VoiceSessionManager.uiState.collect {
                updatePulse()
                // #55: waveform strip follows the voice session state.
                updateWaveform()
            }
        }
        // #54: history changes re-render the panel's Recent section.
        scope.launch {
            TaskQueue.history.collect { refreshHud() }
        }
        // F7: the panel footer shows "Task i of n" for compound queues.
        scope.launch {
            TaskQueue.currentIndex.collect { refreshHud() }
        }
        // F7 GateHost renderer (b): ConfirmGate requests render in
        // the panel's gate section (see onGateChanged).
        scope.launch {
            ConfirmGate.pending.collect { pending -> onGateChanged(pending) }
        }
    }

    /** One checklist row in the F7 task panel. */
    private data class PanelRow(
        val glyph: String,
        val glyphColor: Int,
        val label: String,
        val labelColor: Int,
        val spinner: Boolean,
        val bold: Boolean
    )

    private fun glyphFor(status: TaskStatus): Pair<String, Int> = when (status) {
        TaskStatus.DONE -> "✓" to GLYPH_DONE
        TaskStatus.RUNNING -> "▶" to palette.accent
        TaskStatus.FAILED -> "✕" to GLYPH_FAILED
        TaskStatus.SKIPPED -> "•" to palette.textSecondary
        TaskStatus.QUEUED -> "·" to palette.textSecondary
    }

    /** Plan-step → row: DONE ✓ accent-green, RUNNING spinner, PENDING faint ·, FAILED ✗ red. */
    private fun rowForPlanStep(step: com.nshd.jarvis2.brain.PlanStep): PanelRow =
        when (step.state) {
            PlanStepState.DONE -> PanelRow(
                "✓", GLYPH_DONE, step.label, palette.textPrimary,
                spinner = false, bold = false
            )
            PlanStepState.RUNNING -> PanelRow(
                "", palette.accent, step.label, palette.textPrimary,
                spinner = true, bold = true
            )
            PlanStepState.FAILED -> PanelRow(
                "✗", GLYPH_FAILED, step.label, palette.textPrimary,
                spinner = false, bold = false
            )
            PlanStepState.PENDING -> PanelRow(
                "·", palette.textSecondary, step.label, palette.textSecondary,
                spinner = false, bold = false
            )
        }

    private fun renderRows(rows: List<PanelRow>) {
        hudTaskRowViews.forEachIndexed { index, rowView ->
            // #62: row click targets are re-assigned per render by the
            // queue-mode branch; every other mode starts clean.
            rowView.setOnClickListener(null)
            val row = rows.getOrNull(index)
            rowView.visibility = if (row == null) View.GONE else View.VISIBLE
            if (row != null) {
                hudTaskGlyphViews.getOrNull(index)?.let { glyph ->
                    glyph.text = row.glyph
                    glyph.setTextColor(row.glyphColor)
                    glyph.visibility = if (row.spinner) View.GONE else View.VISIBLE
                }
                panelStepSpinnerViews.getOrNull(index)?.visibility =
                    if (row.spinner) View.VISIBLE else View.GONE
                hudTaskTitleViews.getOrNull(index)?.let { title ->
                    title.text = row.label
                    title.setTextColor(row.labelColor)
                    title.setTypeface(
                        title.typeface,
                        if (row.bold) {
                            android.graphics.Typeface.BOLD
                        } else {
                            android.graphics.Typeface.NORMAL
                        }
                    )
                }
            }
        }
    }

    private fun renderFallbackSteps(queue: List<com.nshd.jarvis2.brain.TaskItem>) {
        // F6 behaviour, kept for plan-less tasks: the TaskState step
        // lines, else the RUNNING item's live step text — the panel
        // never looks idle mid-task.
        val stateSteps = TaskState.steps.value.takeLast(MAX_HUD_STEPS)
        val steps = if (stateSteps.isNotEmpty()) {
            stateSteps
        } else {
            queue.firstOrNull { it.status == TaskStatus.RUNNING }
                ?.step?.takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                ?: emptyList()
        }
        hudStepViews.forEachIndexed { index, view ->
            val step = steps.getOrNull(index)
            view.text = step.orEmpty()
            view.visibility = if (step == null) View.GONE else View.VISIBLE
        }
    }

    // ── F7 panel visibility (auto-expand / auto-collapse) ─────────────

    private fun showPanel(auto: Boolean) {
        val hud = hudPanel ?: return
        cancelPanelCollapse()
        // Expanding always leaves mini-mode (#53).
        panelMiniMode = false
        miniPill?.visibility = View.GONE
        if (auto) panelAutoShown = true
        if (hud.visibility != View.VISIBLE) {
            hud.visibility = View.VISIBLE
            com.nshd.jarvis2.core.AppLog.d(
                "Overlay", "task panel shown (${if (auto) "auto" else "manual"})"
            )
            refreshHud()
        }
    }

    private fun hidePanel() {
        cancelPanelCollapse()
        panelAutoShown = false
        hudPanel?.visibility = View.GONE
        // #53: collapsing WHILE a task runs → mini pill takes over;
        // with nothing running the panel simply disappears.
        val taskRunning = TaskQueue.runningItem() != null || TaskState.active.value
        panelMiniMode = taskRunning
        updateMiniPill()
    }

    /**
     * #53: pill visibility + content. Shown only in mini-mode with a
     * task running and the full panel hidden: spinner (always
     * indeterminate while running) + current step text + plan %.
     */
    private fun updateMiniPill() {
        val pill = miniPill ?: return
        val running = TaskQueue.runningItem()
        val show = panelMiniMode &&
            hudPanel?.visibility != View.VISIBLE &&
            (running != null || TaskState.active.value)
        if (!show) {
            pill.visibility = View.GONE
            return
        }
        pill.visibility = View.VISIBLE
        val stepText = when {
            running == null -> TaskState.goal.value.ifBlank { "Working…" }
            running.plan.isNotEmpty() ->
                running.plan.firstOrNull { it.state == PlanStepState.RUNNING }?.label
                    ?: running.step.ifBlank { running.title }
            running.step.isNotBlank() -> running.step
            else -> running.title
        }
        miniPillText?.text = stepText
        miniPillPercent?.text = when {
            running != null && running.plan.isNotEmpty() ->
                "${TaskQueue.planPercent(running)}%"
            TaskState.progress.value >= 0f ->
                "${(TaskState.progress.value * 100).toInt()}%"
            else -> ""
        }
    }

    // ── #55 waveform strip ─────────────────────────────────────────────

    /** Show/hide the strip + start/stop its 80 ms sampler. */
    private fun updateWaveform() {
        val status = VoiceSessionManager.uiState.value.status
        val listening = status == VoiceStatus.LISTENING ||
            status == VoiceStatus.UNIVERSAL_ACTIVE
        waveformView?.visibility = if (listening) View.VISIBLE else View.GONE
        if (listening) startWaveformTicker() else stopWaveformTicker()
    }

    private fun startWaveformTicker() {
        if (waveformTicker != null) return
        val ticker = object : Runnable {
            override fun run() {
                waveformView?.push(VoiceSessionManager.amplitude.value)
                mainHandler.postDelayed(this, WAVEFORM_TICK_MS)
            }
        }
        waveformTicker = ticker
        mainHandler.post(ticker)
    }

    private fun stopWaveformTicker() {
        waveformTicker?.let { mainHandler.removeCallbacks(it) }
        waveformTicker = null
    }

    // ── #56 mood glyph ─────────────────────────────────────────────────

    /** Mood from task state: running 🛠️, done 😊, failed 😵, else the avatar's emoji. */
    private fun moodGlyph(
        running: com.nshd.jarvis2.brain.TaskItem?,
        display: com.nshd.jarvis2.brain.TaskItem?
    ): String = when {
        running != null -> "🛠️"
        display?.status == TaskStatus.DONE -> "😊"
        display?.status == TaskStatus.FAILED -> "😵"
        else -> avatarEmoji()
    }

    /** AvatarCatalog carries no emoji per avatar — map its ids, default 🤖. */
    private fun avatarEmoji(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_AVATAR_ID, AvatarCatalog.DEFAULT_ID)) {
            "alien" -> "👽"
            "cat" -> "🐱"
            "ghost" -> "👻"
            "cloud" -> "☁️"
            "flower" -> "🌸"
            "drop" -> "💧"
            "hexagon" -> "⬡"
            "pebble" -> "🪨"
            "blob" -> "🫠"
            "circle" -> "🔵"
            else -> "🤖" // droid, mech, unknown
        }
    }

    // ── #58 plan-step haptic ───────────────────────────────────────────

    /**
     * One subtle tick when the displayed task's plan DONE-count
     * increments. The first sighting of a task only sets the baseline
     * (no tick for steps completed before the panel saw the task).
     */
    private fun maybePlanHaptic(item: com.nshd.jarvis2.brain.TaskItem?) {
        if (item == null || item.plan.isEmpty()) return
        val done = TaskQueue.planDoneCount(item)
        if (item.id != lastHapticItemId) {
            lastHapticItemId = item.id
            lastHapticDoneCount = done
            return
        }
        if (done > lastHapticDoneCount) {
            lastHapticDoneCount = done
            if (panelHapticsEnabled) {
                hudPanel?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } else if (done < lastHapticDoneCount) {
            lastHapticDoneCount = done
        }
    }

    // ── #57 per-app panel position memory ──────────────────────────────

    /** Reads panel_haptics (#58) + reduce_motion into their mirrors. */
    private fun readPanelSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        panelHapticsEnabled = prefs.getBoolean(KEY_PANEL_HAPTICS, true)
        MotionSettings.reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        // v2.0.1: overlay appearance mirrors + live apply. This runs
        // on onCreate AND every onStartCommand — MainActivity re-sends
        // a start when these settings change, so slider moves reach
        // the running overlay without a service restart.
        overlayAvatarOpacity = prefs.getFloat(KEY_OVERLAY_AVATAR_OPACITY, 1.0f)
            .coerceIn(0.25f, 1.0f)
        panelSizePercent = prefs.getInt(KEY_PANEL_SIZE_PERCENT, 100)
            .coerceIn(80, 120)
        panelBgOpacity = prefs.getFloat(KEY_PANEL_BG_OPACITY, 0.97f)
            .coerceIn(0.35f, 1.0f)
        applyOverlayAppearance()
    }

    /** v2.0.1: panel width scale from [panelSizePercent] (80–120%). */
    private fun panelScale(): Float = panelSizePercent.coerceIn(80, 120) / 100f

    /**
     * v2.0.1: the panel card fill — the palette background's RGB with
     * the user's [panelBgOpacity] as its alpha (palette default
     * ≈ 0.97). Only the card surface takes this alpha; panel text and
     * content stay fully opaque.
     */
    private fun panelSurfaceColor(): Int {
        val alpha = (panelBgOpacity.coerceIn(0.35f, 1.0f) * 255f)
            .toInt().coerceIn(0, 255)
        return (alpha shl 24) or (palette.background and 0x00FFFFFF)
    }

    /**
     * v2.0.1: applies the appearance mirrors to the live views —
     * avatar alpha, panel + mini-pill surface alpha, panel/pill width
     * and panel padding scaled from the build-time base geometry.
     * A no-op for views not built yet (they read the mirrors at build).
     */
    private fun applyOverlayAppearance() {
        bubbleView?.alpha = overlayAvatarOpacity
        val surface = panelSurfaceColor()
        hudBackground?.setColor(surface)
        miniPillBackground?.setColor(surface)
        val scale = panelScale()
        hudPanel?.let { panel ->
            val lp = panel.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && panelBaseWidthPx > 0) {
                lp.width = (panelBaseWidthPx * scale).toInt()
                panel.layoutParams = lp
            }
            if (panelBasePadHPx > 0) {
                panel.setPadding(
                    (panelBasePadHPx * scale).toInt(),
                    (panelBasePadVPx * scale).toInt(),
                    (panelBasePadHPx * scale).toInt(),
                    (panelBasePadVPx * scale).toInt()
                )
            }
        }
        miniPill?.let { pill ->
            val lp = pill.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && pillBaseWidthPx > 0) {
                lp.width = (pillBaseWidthPx * scale).toInt()
                pill.layoutParams = lp
            }
        }
    }

    /**
     * The accessibility service is the only foreground-package source
     * ([JarvisAccessibilityService.lastPackageName]); poll it ~1/s
     * and restore the saved panel position when the app changes.
     */
    private fun startForegroundPoll() {
        lastForegroundPkg = JarvisAccessibilityService.lastPackageName
        val poll = object : Runnable {
            override fun run() {
                pollForegroundApp()
                mainHandler.postDelayed(this, FOREGROUND_POLL_MS)
            }
        }
        foregroundPoll = poll
        mainHandler.postDelayed(poll, FOREGROUND_POLL_MS)
    }

    private fun pollForegroundApp() {
        val pkg = JarvisAccessibilityService.lastPackageName ?: return
        if (pkg == lastForegroundPkg) return
        lastForegroundPkg = pkg
        if (pkg == packageName) return // own UI hides the overlay anyway
        restorePanelPosition(pkg)
    }

    /** Restore pkg's saved panel Y + X edge, when one was saved. */
    private fun restorePanelPosition(pkg: String) {
        val lp = params ?: return
        val root = rootView ?: return
        val prefs = getSharedPreferences(PANEL_POS_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(pkg + POS_Y_SUFFIX)) return
        val savedY = prefs.getInt(pkg + POS_Y_SUFFIX, lp.y)
        val edgeRight = prefs.getInt(pkg + POS_EDGE_SUFFIX, 0) == 1
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = if (root.width > 0) root.width else dp(56)
        lp.y = savedY
        lp.x = if (edgeRight) (screenWidth - viewWidth).coerceAtLeast(0) else 0
        try {
            windowManager?.updateViewLayout(root, lp)
        } catch (e: Exception) {
            // Window detached — the next drag re-anchors anyway.
        }
    }

    /** Save the current panel Y + X edge under the foreground package. */
    private fun savePanelPosition(edgeRight: Boolean) {
        val pkg = lastForegroundPkg
            ?: JarvisAccessibilityService.lastPackageName
            ?: return
        if (pkg == packageName) return
        val lp = params ?: return
        getSharedPreferences(PANEL_POS_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(pkg + POS_Y_SUFFIX, lp.y)
            .putInt(pkg + POS_EDGE_SUFFIX, if (edgeRight) 1 else 0)
            .apply()
    }

    /** Collapse an AUTO-shown panel ~6 s after the task settles. */
    private fun schedulePanelCollapse() {
        if (!panelAutoShown || hudPanel?.visibility != View.VISIBLE) return
        cancelPanelCollapse()
        val runnable = Runnable {
            panelCollapseRunnable = null
            hidePanel()
        }
        panelCollapseRunnable = runnable
        mainHandler.postDelayed(runnable, PANEL_AUTO_COLLAPSE_MS)
    }

    private fun cancelPanelCollapse() {
        panelCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        panelCollapseRunnable = null
    }

    // ── F7 GateHost renderer (b) ───────────────────────────────────────

    private fun onGateChanged(pending: PendingConfirm?) {
        if (pending == null) {
            gatePendingId = -1L
            gateSection?.visibility = View.GONE
            stopGateTicker()
            refreshHud() // re-run auto-collapse bookkeeping
            return
        }
        com.nshd.jarvis2.core.AppLog.i(
            "Gate", "overlay gate card shown for request #${pending.id}"
        )
        gatePendingId = pending.id
        gateCreatedAtMs = pending.createdAtMs
        val lines = pending.description.lines()
        gateTitleView?.text = lines.firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Confirm this action"
        val bodyText = lines.drop(1).joinToString("\n").trim()
        gateBodyView?.text = bodyText
        gateBodyView?.visibility = if (bodyText.isBlank()) View.GONE else View.VISIBLE
        gatePrimaryBtn?.text =
            if (pending.description.startsWith("Send")) "Send" else "Allow"
        gateSection?.visibility = View.VISIBLE
        showPanel(auto = true)
        startGateTicker()
    }

    /** Drain the gate's 30 s timer bar (the gate itself enforces the timeout). */
    private fun startGateTicker() {
        stopGateTicker()
        val ticker = object : Runnable {
            override fun run() {
                if (gatePendingId == -1L) return
                val elapsed = System.currentTimeMillis() - gateCreatedAtMs
                val remaining = (GATE_TIMEOUT_MS - elapsed).coerceIn(0L, GATE_TIMEOUT_MS)
                gateTimerBar?.progress = (remaining * 100 / GATE_TIMEOUT_MS).toInt()
                if (remaining > 0L) mainHandler.postDelayed(this, 200L)
            }
        }
        gateTicker = ticker
        mainHandler.post(ticker)
    }

    private fun stopGateTicker() {
        gateTicker?.let { mainHandler.removeCallbacks(it) }
        gateTicker = null
    }

    // ── F7 panel rendering ─────────────────────────────────────────────

    private fun refreshHud() {
        hudHeaderView?.text = assistantName().uppercase()
        val queue = TaskQueue.tasks.value
        val runningItem = queue.firstOrNull { it.status == TaskStatus.RUNNING }
        val displayItem = runningItem
            ?: queue.lastOrNull {
                it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED
            }
            ?: queue.lastOrNull()
        val anyRunning = runningItem != null || TaskState.active.value

        var statusText = "Idle"
        var progressValue = 0
        var progressIndeterminate = false
        var resultText: String? = null
        var resultFailed = false
        var overflowText: String? = null

        when {
            // ── Plan mode: the screenshot's checklist ─────────────────
            displayItem != null && displayItem.plan.isNotEmpty() -> {
                panelGoalView?.text = displayItem.title
                renderRows(displayItem.plan.take(MAX_HUD_TASKS).map { rowForPlanStep(it) })
                val extra = displayItem.plan.size - MAX_HUD_TASKS
                overflowText = if (extra > 0) "…and $extra more steps" else null
                hudStepViews.forEach { it.visibility = View.GONE }
                val doneCount = TaskQueue.planDoneCount(displayItem)
                val totalSteps = displayItem.plan.size
                val percent = TaskQueue.planPercent(displayItem)
                statusText = "$doneCount/$totalSteps done · $percent%"
                progressValue = percent
                if (displayItem.status == TaskStatus.DONE ||
                    displayItem.status == TaskStatus.FAILED
                ) {
                    resultText = displayItem.resultLine.ifBlank { null }
                    resultFailed = displayItem.status == TaskStatus.FAILED
                }
            }
            // ── Queue mode: several sub-tasks, none with a plan ───────
            queue.size > 1 -> {
                panelGoalView?.text = displayItem?.title ?: "Tasks"
                renderRows(
                    queue.take(MAX_HUD_TASKS).map { item ->
                        val (glyph, color) = glyphFor(item.status)
                        PanelRow(
                            glyph, color,
                            // v1.9.0 (#62): queued rows are tappable —
                            // say so right on the row.
                            if (item.status == TaskStatus.QUEUED) {
                                item.title + "  (tap to skip)"
                            } else {
                                item.title
                            },
                            labelColor = palette.textPrimary,
                            spinner = item.status == TaskStatus.RUNNING,
                            bold = item.status == TaskStatus.RUNNING
                        )
                    }
                )
                // v1.9.0 (#62): per-sub-task management — tapping a
                // QUEUED row skips (cancels) just that sub-task; the
                // pipeline honours SKIPPED before promoting it.
                hudTaskRowViews.forEachIndexed { index, rowView ->
                    val item = queue.getOrNull(index)
                    if (item != null && item.status == TaskStatus.QUEUED) {
                        rowView.setOnClickListener {
                            com.nshd.jarvis2.brain.TaskQueue.skipTask(item.id)
                        }
                    } else {
                        rowView.setOnClickListener(null)
                    }
                }
                val extra = queue.size - MAX_HUD_TASKS
                overflowText = if (extra > 0) "…and $extra more" else null
                renderFallbackSteps(queue)
                val doneCount = queue.count { it.status == TaskStatus.DONE }
                val percent = if (queue.isEmpty()) 0 else doneCount * 100 / queue.size
                statusText = "$doneCount of ${queue.size} done · $percent%"
                progressValue = percent
                progressIndeterminate = anyRunning && doneCount == 0
            }
            // ── Single plan-less task (Live turns, wake commands) ─────
            displayItem != null -> {
                panelGoalView?.text = displayItem.title
                val (glyph, color) = glyphFor(displayItem.status)
                val label = when {
                    displayItem.status == TaskStatus.RUNNING &&
                        displayItem.step.isNotBlank() -> displayItem.step
                    displayItem.status == TaskStatus.RUNNING -> "Working…"
                    displayItem.status == TaskStatus.DONE -> "Done"
                    displayItem.status == TaskStatus.FAILED -> "Failed"
                    else -> "Queued"
                }
                renderRows(
                    listOf(
                        PanelRow(
                            glyph, color, label,
                            labelColor = palette.textPrimary,
                            spinner = displayItem.status == TaskStatus.RUNNING,
                            bold = displayItem.status == TaskStatus.RUNNING
                        )
                    )
                )
                renderFallbackSteps(queue)
                statusText = label
                progressIndeterminate = displayItem.status == TaskStatus.RUNNING
                progressValue = if (displayItem.status == TaskStatus.DONE) 100 else 0
                if (displayItem.status == TaskStatus.DONE ||
                    displayItem.status == TaskStatus.FAILED
                ) {
                    resultText = displayItem.resultLine.ifBlank { null }
                        ?: TaskState.lastOutcome.value
                    resultFailed = displayItem.status == TaskStatus.FAILED
                }
            }
            // ── Empty queue: TaskState fallback / idle ────────────────
            else -> {
                panelGoalView?.text = TaskState.goal.value
                    .ifBlank { "${assistantName()} is idle" }
                renderRows(emptyList())
                renderFallbackSteps(queue)
                val outcome = TaskState.lastOutcome.value
                statusText = outcome ?: "Idle"
                progressIndeterminate = TaskState.active.value
                progressValue = (TaskState.progress.value * 100).toInt().coerceIn(0, 100)
                resultText = outcome
            }
        }

        hudMoreView?.let { more ->
            more.text = overflowText.orEmpty()
            more.visibility = if (overflowText != null) View.VISIBLE else View.GONE
        }
        panelResultView?.let { result ->
            result.text = resultText.orEmpty()
            result.setTextColor(if (resultFailed) GLYPH_FAILED else palette.accent)
            result.visibility = if (resultText != null) View.VISIBLE else View.GONE
        }
        hudProgressBar?.let { bar ->
            bar.isIndeterminate = progressIndeterminate
            if (!progressIndeterminate) {
                bar.progress = progressValue.coerceIn(0, 100)
            }
        }
        hudStatusView?.text = statusText
        // Compound queues: "Task i of n" beside the footer counts
        // (currentIndex is -1 when no queue position is active).
        val currentIndex = TaskQueue.currentIndex.value
        panelQueueNoteView?.text = if (queue.size > 1 && currentIndex >= 0) {
            "Task ${currentIndex + 1} of ${queue.size}"
        } else {
            ""
        }

        // ── v1.8.0 panel additions ─────────────────────────────────────
        // #56: header mood glyph follows the task state.
        panelMoodView?.text = moodGlyph(runningItem, displayItem)
        // #58: haptic tick on plan-step completion.
        maybePlanHaptic(displayItem)
        // #60: quick actions while a task runs; Pause only when the
        // queue actually has QUEUED items to hold back.
        val hasQueued = queue.any { it.status == TaskStatus.QUEUED }
        panelActionsRow?.visibility = if (anyRunning) View.VISIBLE else View.GONE
        panelPauseBtn?.let { pauseBtn ->
            pauseBtn.visibility = if (hasQueued) View.VISIBLE else View.GONE
            pauseBtn.text = if (TaskQueue.paused) "Resume" else "Pause"
        }
        // #54: Recent section — expanded + idle + history non-empty.
        renderHistory(anyRunning)

        // ── Auto-behaviour ─────────────────────────────────────────────
        // A running task (or a pending gate) pulls the panel up over
        // whatever app is foreground; when everything settles, an
        // auto-shown panel lingers ~6 s on its final state (Part D)
        // and collapses back to the avatar. A manually opened panel
        // (double-tap) is never auto-collapsed. In mini-mode (#53) the
        // user collapsed on purpose: the pill stays, the panel doesn't
        // re-expand — except a pending gate always forces it open.
        if (anyRunning || gatePendingId != -1L) {
            cancelPanelCollapse()
            if (panelMiniMode && gatePendingId == -1L) {
                // Stay collapsed as the pill.
            } else if (hudPanel?.visibility != View.VISIBLE) {
                showPanel(auto = true)
            }
        } else {
            val anyFinished = queue.any {
                it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED
            } || TaskState.lastOutcome.value != null
            if (anyFinished) schedulePanelCollapse()
        }
        // #53: mini-mode ends with the task; pill content tracks state.
        if (!anyRunning) panelMiniMode = false
        updateMiniPill()
    }

    /** #54: render the Recent rows from [TaskQueue.history]. */
    private fun renderHistory(anyRunning: Boolean) {
        val section = historySection ?: return
        val history = TaskQueue.history.value
        val show = !anyRunning && history.isNotEmpty()
        section.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        historyRowViews.forEachIndexed { index, row ->
            val item = history.getOrNull(index)
            row.visibility = if (item == null) View.GONE else View.VISIBLE
            if (item != null) {
                val done = item.status == TaskStatus.DONE
                historyGlyphViews.getOrNull(index)?.let { glyph ->
                    glyph.text = if (done) "✓" else "✕"
                    glyph.setTextColor(if (done) GLYPH_DONE else GLYPH_FAILED)
                }
                historyTitleViews.getOrNull(index)?.text = item.title
                historyResultViews.getOrNull(index)?.text =
                    item.resultLine.ifBlank { if (done) "Done" else "Failed" }
            }
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
            com.nshd.jarvis2.core.AppLog.d("Overlay", "double-tap — toggle HUD")
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
            com.nshd.jarvis2.core.AppLog.d("Overlay", "tap — abort running task")
            TaskState.abort()
            return
        }
        com.nshd.jarvis2.core.AppLog.d("Overlay", "tap — toggle voice session")
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
        if (hud.visibility == View.VISIBLE) {
            hidePanel()
        } else {
            // Manual open: not auto-shown, so it never auto-collapses.
            panelAutoShown = false
            com.nshd.jarvis2.core.AppLog.d("Overlay", "task panel opened (double-tap)")
            showPanel(auto = false)
        }
    }

    /**
     * F7: drag the whole overlay by the panel's header (same window
     * math as the bubble drag); a clean tap on the header collapses
     * the panel back to the avatar.
     */
    private fun handlePanelTouch(event: MotionEvent): Boolean {
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
                    hidePanel()
                }
                return true
            }
        }
        return false
    }

    private fun snapToEdge() {
        val lp = params ?: return
        val wm = windowManager ?: return
        val root = rootView ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = if (root.width > 0) root.width else bubbleView?.width ?: 0
        val center = lp.x + viewWidth / 2
        val targetX = if (center <= screenWidth / 2) 0 else (screenWidth - viewWidth).coerceAtLeast(0)
        // #57: drag end — remember this position for the foreground app.
        savePanelPosition(edgeRight = targetX > 0)
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

    /**
     * #59: readable text colour ON an accent fill — dark ink when the
     * accent itself is light (e.g. the DARK palette's pale lavender),
     * white otherwise.
     */
    private fun onAccentColor(accent: Int): Int =
        if (android.graphics.Color.luminance(accent) > 0.55f) {
            0xFF1B1B1F.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

    /** Repaints the HUD with [newPalette]; a no-op before the HUD exists. */
    private fun applyPalette(newPalette: HudPalette) {
        palette = newPalette
        hudBackground?.let { bg ->
            // v2.0.1: panel fill keeps the user's bg opacity.
            bg.setColor(panelSurfaceColor())
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
        // F7 panel views follow the palette too.
        panelGripView?.setDotColor(newPalette.textSecondary)
        panelQueueNoteView?.setTextColor(newPalette.textSecondary)
        panelResultView?.setTextColor(newPalette.accent)
        panelStepSpinnerViews.forEach {
            it.indeterminateTintList = ColorStateList.valueOf(newPalette.accent)
        }
        gatePrimaryBackground?.setColor(newPalette.accent)
        gateCancelBackground?.let { bg ->
            bg.setColor(newPalette.background)
            bg.setStroke(dp(1), newPalette.border)
        }
        gateTimerBar?.let { bar ->
            bar.progressTintList = ColorStateList.valueOf(newPalette.accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(newPalette.background)
        }
        gateTitleView?.setTextColor(newPalette.textPrimary)
        gateBodyView?.setTextColor(newPalette.textSecondary)
        gateSectionBackground?.let { bg ->
            bg.setColor(newPalette.progressTrack)
            bg.setStroke(dp(1), newPalette.border)
        }
        // v1.8.0 additions follow the palette too (#59).
        miniPillBackground?.let { bg ->
            // v2.0.1: the pill follows the same panel bg opacity.
            bg.setColor(panelSurfaceColor())
            bg.setStroke(dp(1), newPalette.border)
        }
        miniPillText?.setTextColor(newPalette.textPrimary)
        miniPillPercent?.setTextColor(newPalette.accent)
        miniPillSpinner?.indeterminateTintList =
            ColorStateList.valueOf(newPalette.accent)
        waveformView?.setBarColor(newPalette.accent)
        historyHeaderView?.setTextColor(newPalette.textSecondary)
        historyTitleViews.forEach { it.setTextColor(newPalette.textPrimary) }
        historyResultViews.forEach { it.setTextColor(newPalette.textSecondary) }
        panelPauseBackground?.let { bg ->
            bg.setColor(newPalette.progressTrack)
            bg.setStroke(dp(1), newPalette.border)
        }
        panelPauseBtn?.setTextColor(newPalette.textPrimary)
        gateCancelBtn?.setTextColor(newPalette.textPrimary)
        // #59 light-theme readability: text ON the accent fill must
        // contrast with the accent itself — white on the dark theme's
        // pale-lavender accent (and on light accents generally) is
        // unreadable, so pick dark ink whenever the accent is light.
        val onAccent = onAccentColor(newPalette.accent)
        hudOpenAppView?.setTextColor(onAccent)
        gatePrimaryBtn?.setTextColor(onAccent)
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

    // ── F8 (#88): accessibility watchdog ─────────────────────────────
    //
    // Vivo/iQOO silently kill sideloaded accessibility services. A
    // ~60 s Handler heartbeat (this service is persistent while the
    // overlay is on — no WorkManager, no new infra) checks whether
    // JarvisAccessibilityService is still connected; when it dies,
    // ONE high-priority notification offers the one-tap trip back to
    // Accessibility Settings, re-posted at most once per 6 h, and
    // cleared the moment the service reconnects.

    private var watchdogRunnable: Runnable? = null

    @Volatile
    private var a11yWasAlive = true

    @Volatile
    private var lastWatchdogNotifyMs = 0L

    private fun createWatchdogChannel() {
        val channel = NotificationChannel(
            WATCHDOG_CHANNEL_ID,
            "Jarvis hands alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startAccessibilityWatchdog() {
        a11yWasAlive = JarvisAccessibilityService.instance != null
        val runnable = object : Runnable {
            override fun run() {
                checkAccessibilityWatchdog()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, WATCHDOG_INTERVAL_MS)
    }

    private fun checkAccessibilityWatchdog() {
        val alive = JarvisAccessibilityService.instance != null
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (alive) {
            if (!a11yWasAlive) {
                a11yWasAlive = true
                com.nshd.jarvis2.core.AppLog.i(
                    "Hands", "watchdog: accessibility service reconnected — alert cleared"
                )
                runCatching { nm.cancel(WATCHDOG_NOTIFICATION_ID) }
            }
            return
        }
        // Dead: log the transition once, (re-)post at most once per 6h.
        if (a11yWasAlive) {
            a11yWasAlive = false
            com.nshd.jarvis2.core.AppLog.w(
                "Hands", "watchdog: accessibility service is DEAD — hands are off"
            )
        }
        val now = System.currentTimeMillis()
        if (now - lastWatchdogNotifyMs < WATCHDOG_RENOTIFY_MS) return
        lastWatchdogNotifyMs = now
        val openSettings = PendingIntent.getActivity(
            this,
            3,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, WATCHDOG_CHANNEL_ID)
            .setContentTitle("Jarvis hands are off")
            .setContentText("Accessibility was switched off — tap to re-enable Jarvis's hands.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(WATCHDOG_NOTIFICATION_ID, notification) }
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
        /** F8 (#88): watchdog alert channel + notification. */
        private const val WATCHDOG_CHANNEL_ID = "jarvis_watchdog"
        private const val WATCHDOG_NOTIFICATION_ID = 1005
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val WATCHDOG_RENOTIFY_MS = 6 * 60 * 60_000L
        private const val PREFS_NAME = "jarvis_settings"
        private const val KEY_AVATAR_ID = "avatar_id"
        private const val KEY_ASSISTANT_NAME = "assistant_name"
        private const val KEY_THEME = "theme"
        private const val KEY_CUSTOM_BG = "custom_bg"
        private const val MAX_HUD_TASKS = 6
        private const val MAX_HUD_STEPS = 3
        /** v1.8.0 (#54): history rows rendered in the panel. */
        private const val HISTORY_ROWS = 5
        /** v1.8.0 (#55): waveform sampler period. */
        private const val WAVEFORM_TICK_MS = 80L
        /** v1.8.0 (#57): foreground-package poll period + position prefs. */
        private const val FOREGROUND_POLL_MS = 1_000L
        private const val PANEL_POS_PREFS = "panel_pos"
        private const val POS_Y_SUFFIX = "_y"
        private const val POS_EDGE_SUFFIX = "_edge"
        private const val KEY_PANEL_HAPTICS = "panel_haptics"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        /** v2.0.1: overlay appearance prefs (mirror MainActivity keys). */
        private const val KEY_OVERLAY_AVATAR_OPACITY = "overlay_avatar_opacity"
        private const val KEY_PANEL_SIZE_PERCENT = "panel_size_percent"
        private const val KEY_PANEL_BG_OPACITY = "panel_bg_opacity"
        private const val GLYPH_DONE = 0xFF43A047.toInt()
        private const val GLYPH_FAILED = 0xFFE53935.toInt()
        /** F7: how long the finished panel lingers before auto-collapse. */
        private const val PANEL_AUTO_COLLAPSE_MS = 6_000L
        /** F7: mirrors ConfirmGate's 30 s prompt timeout (timer bar only). */
        private const val GATE_TIMEOUT_MS = 30_000L
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

/**
 * F7: the panel header's drag grip — six dots in two columns, drawn
 * directly (plain Views have no vector-icon story, and a font glyph
 * for "⋮⋮" is not guaranteed to exist on every device font).
 */
private class GripDotsView(context: Context, dotColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotColor
        style = Paint.Style.FILL
    }

    fun setDotColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val radius = minOf(width, height) / 7f
        val columns = floatArrayOf(width * 0.32f, width * 0.68f)
        val rows = floatArrayOf(height * 0.22f, height * 0.5f, height * 0.78f)
        for (y in rows) {
            for (x in columns) {
                canvas.drawCircle(x, y, radius, paint)
            }
        }
    }
}

/**
 * v1.8.0 (#55): the panel's waveform strip — 16 vertical bars fed by a
 * ring buffer of voice-amplitude samples (pushed ~every 80 ms by the
 * service while a session is listening). Bar heights are centre-based
 * so the strip reads as a symmetric mini waveform.
 */
private class WaveformView(context: Context, barColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val levels = FloatArray(BAR_COUNT)
    private var head = 0

    fun setBarColor(color: Int) {
        paint.color = color
        invalidate()
    }

    /** Append one amplitude sample (0..1), dropping the oldest. */
    fun push(amplitude: Float) {
        levels[head] = amplitude.coerceIn(0f, 1f)
        head = (head + 1) % BAR_COUNT
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val slot = width.toFloat() / BAR_COUNT
        paint.strokeWidth = slot * 0.45f
        val midY = height / 2f
        val maxHalf = height / 2f - 1f
        for (i in 0 until BAR_COUNT) {
            val level = levels[(head + i) % BAR_COUNT]
            val half = maxOf(1.5f, level * maxHalf)
            val x = slot * i + slot / 2f
            canvas.drawLine(x, midY - half, x, midY + half, paint)
        }
    }

    private companion object {
        const val BAR_COUNT = 16
    }
}

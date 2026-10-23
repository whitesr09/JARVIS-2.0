package com.nshd.jarvis2.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.core.UpdateChecker
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import com.nshd.jarvis2.voice.VoiceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tab { HOME, CHAT, SETTINGS, UPDATES }

/** Full-screen destinations layered over the tabs (L2: API + Memory, L3: Voice, L5: Avatars, F4: Hands-Free, F5: Logs). */
private enum class SubScreen { NONE, PERMISSIONS, API, MEMORY, VOICE, AVATARS, HANDSFREE, LOGS }

/** What is on screen right now — the AnimatedContent target in JarvisApp. */
private data class ScreenTarget(val tab: Tab, val sub: SubScreen)

@Composable
fun JarvisApp(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit
) {
    JarvisTheme(theme = settings.theme, customBgHex = settings.customBgHex) {
        // F4.1: a crash from the previous run is reported BEFORE any
        // normal UI — the report screen is deliberately plain (no orb,
        // no session state) so it renders even when voice is broken.
        val appContext = androidx.compose.ui.platform.LocalContext.current
        // Plan #98: relaunch (crash or not) returns to the last screen
        // the user was on — the crash card, when pending, still shows
        // FIRST (early return below) and this restore applies after it.
        val restoredScreen = remember {
            com.nshd.jarvis2.CrashLogger.lastScreen(appContext)
        }
        var tab by remember {
            mutableStateOf(
                restoredScreen?.substringBefore(':')
                    ?.let { name -> Tab.values().firstOrNull { it.name == name } }
                    ?: Tab.HOME
            )
        }
        var subScreen by remember {
            mutableStateOf(
                restoredScreen?.substringAfter(':', "")
                    ?.let { name -> SubScreen.values().firstOrNull { it.name == name } }
                    ?: SubScreen.NONE
            )
        }
        LaunchedEffect(tab, subScreen) {
            com.nshd.jarvis2.CrashLogger.saveLastScreen(
                appContext, "${tab.name}:${subScreen.name}"
            )
        }
        // Plan #94: keep the process-wide motion mirror in sync.
        LaunchedEffect(settings.reduceMotion) {
            MotionSettings.reduceMotion = settings.reduceMotion
        }
        // Plan #104: one update check per process, off the main thread.
        val updateAvailable by UpdateChecker.updateAvailable.collectAsState()
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { UpdateChecker.check(appContext) }
        }
        // Plan #102: safe-mode banner state (CrashLogger is the store).
        var safeMode by remember {
            mutableStateOf(com.nshd.jarvis2.CrashLogger.isSafeMode(appContext))
        }
        // Plan #105: "What's new" once per update.
        val currentVersionCode = remember {
            runCatching {
                val info = appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.P
                ) {
                    info.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode
                }
            }.getOrDefault(0)
        }
        var showWhatsNew by remember {
            mutableStateOf(
                settings.lastSeenVersionCode in 1 until
                    currentVersionCode.coerceAtLeast(1)
            )
        }
        var showCrashReport by remember {
            mutableStateOf(com.nshd.jarvis2.CrashLogger.hasPendingCrash(appContext))
        }
        if (showCrashReport) {
            val report = remember {
                com.nshd.jarvis2.CrashLogger.readCrashReport(appContext)
            }
            CrashReportScreen(
                report = report,
                onDismissAndClear = {
                    com.nshd.jarvis2.CrashLogger.clearCrash(appContext)
                    showCrashReport = false
                },
                onOpenLogs = {
                    // Keep the report pending — the user may come back
                    // to it; just drop into the normal UI on the Logs
                    // sub-screen (F5).
                    showCrashReport = false
                    subScreen = SubScreen.LOGS
                }
            )
            return@JarvisTheme
        }
        // The center mic reflects the live voice session (F2): it must
        // not sit there red + MicOff during normal operation.
        val voiceState by VoiceSessionManager.uiState.collectAsState()

        // Overlay bubble tap (and other external requests) land here.
        val voiceRequested by AppNavRequests.voiceRequested.collectAsState()
        LaunchedEffect(voiceRequested) {
            if (AppNavRequests.consumeVoiceRequest()) {
                subScreen = SubScreen.VOICE
            }
        }

        // F5.1: while the keyboard is up, the floating nav is hidden
        // (plain conditional — no animation state to glitch while
        // typing) so it can never overlap a composer; the Scaffold
        // then also grants zero bottom clearance, leaving each
        // screen's own imePadding as the only bottom inset. The nav
        // returns the moment the keyboard closes.
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        Scaffold(
            bottomBar = {
                if (!imeVisible) {
                    JarvisFloatingNav(
                        tab = tab,
                        onVoiceScreen = subScreen == SubScreen.VOICE,
                        voiceState = voiceState,
                        updatesBadge = updateAvailable,
                        onTab = {
                            tab = it
                            subScreen = SubScreen.NONE
                        },
                        onMic = { subScreen = SubScreen.VOICE }
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Plan #102: safe-mode banner (wake word + overlay stayed
                // off this boot after repeated startup crashes).
                if (safeMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 14.dp, vertical = 8.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Safe mode — Jarvis crashed 3× " +
                                    "while starting, so the wake word " +
                                    "and overlay stayed off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme
                                    .colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    com.nshd.jarvis2.CrashLogger
                                        .setSafeMode(appContext, false)
                                    safeMode = false
                                }
                            ) { Text("Exit safe mode") }
                        }
                    }
                }
                val reducedMotion = rememberReducedMotion()
                // D2 transitions: fade + slight horizontal slide between
                // tabs and sub-screens (transform/opacity only). State
                // handling is unchanged — AnimatedContent only animates
                // the container swap. Reduced motion → plain crossfade.
                AnimatedContent(
                    targetState = ScreenTarget(tab, subScreen),
                    transitionSpec = {
                        if (reducedMotion) {
                            fadeIn(JarvisMotion.tweenFast()) togetherWith
                                fadeOut(JarvisMotion.tweenFast())
                        } else {
                            val dir = when {
                                initialState.sub == SubScreen.NONE &&
                                    targetState.sub != SubScreen.NONE -> 1
                                initialState.sub != SubScreen.NONE &&
                                    targetState.sub == SubScreen.NONE -> -1
                                initialState.sub != SubScreen.NONE &&
                                    targetState.sub != SubScreen.NONE -> 1
                                else -> {
                                    val d = targetState.tab.ordinal - initialState.tab.ordinal
                                    if (d < 0) -1 else 1
                                }
                            }
                            (fadeIn(JarvisMotion.tweenSlow()) +
                                slideInHorizontally(JarvisMotion.tweenSlow()) { w -> dir * w / 6 })
                                .togetherWith(
                                    fadeOut(JarvisMotion.tweenFast()) +
                                        slideOutHorizontally(JarvisMotion.tweenSlow()) { w -> -dir * w / 6 }
                                )
                        }
                    },
                    label = "screenTransition",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { target ->
                    when (target.sub) {
                        SubScreen.PERMISSIONS -> PermissionsScreen(
                            onBack = { subScreen = SubScreen.NONE }
                        )
                        SubScreen.API -> ApiConnectionScreen(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onBack = { subScreen = SubScreen.NONE }
                        )
                        SubScreen.MEMORY -> MemoryScreen(
                            onBack = { subScreen = SubScreen.NONE }
                        )
                        SubScreen.VOICE -> VoiceScreen(
                            settings = settings,
                            onBack = { subScreen = SubScreen.NONE },
                            onOpenChat = {
                                subScreen = SubScreen.NONE
                                tab = Tab.CHAT
                            },
                            onOpenSettings = {
                                subScreen = SubScreen.NONE
                                tab = Tab.SETTINGS
                            }
                        )
                        SubScreen.AVATARS -> AvatarsScreen(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onBack = { subScreen = SubScreen.NONE },
                            onOpenPermissions = { subScreen = SubScreen.PERMISSIONS }
                        )
                        SubScreen.HANDSFREE -> HandsFreeScreen(
                            settings = settings,
                            onBack = { subScreen = SubScreen.NONE },
                            onOpenPermissions = { subScreen = SubScreen.PERMISSIONS }
                        )
                        SubScreen.LOGS -> LogsScreen(
                            onBack = { subScreen = SubScreen.NONE }
                        )
                        SubScreen.NONE -> when (target.tab) {
                            Tab.HOME -> HomeScreen(
                                settings = settings,
                                listening = false,
                                onOpenPermissions = { subScreen = SubScreen.PERMISSIONS },
                                onOpenMemory = { subScreen = SubScreen.MEMORY },
                                onOpenSettings = { tab = Tab.SETTINGS },
                                onOpenVoice = { subScreen = SubScreen.VOICE },
                                onSettingsChange = onSettingsChange,
                                onOpenChat = { tab = Tab.CHAT }
                            )
                            Tab.CHAT -> ChatScreen(
                                settings = settings,
                                onOpenSettings = { tab = Tab.SETTINGS },
                                onOpenVoice = { subScreen = SubScreen.VOICE }
                            )
                            Tab.SETTINGS -> SettingsScreen(
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                                onOpenPermissions = { subScreen = SubScreen.PERMISSIONS },
                                onOpenApi = { subScreen = SubScreen.API },
                                onOpenMemory = { subScreen = SubScreen.MEMORY },
                                onOpenAvatars = { subScreen = SubScreen.AVATARS },
                                onOpenHandsFree = { subScreen = SubScreen.HANDSFREE },
                                onOpenLogs = { subScreen = SubScreen.LOGS }
                            )
                            Tab.UPDATES -> UpdatesScreen()
                        }
                    }
                }
                // Plan #105: post-update "What's new" card (dialog form),
                // shown once per version, dismissed into
                // settings.lastSeenVersionCode.
                if (showWhatsNew) {
                    AlertDialog(
                        onDismissRequest = { showWhatsNew = false },
                        title = { Text("What's new in v1.8.0") },
                        text = {
                            Text(
                                "Panel, Orb & Feel: the floating panel " +
                                    "gets a mini progress pill, task " +
                                    "history, quick Stop/Pause, haptics " +
                                    "and per-app positions — and Stop " +
                                    "now truly stops a task mid-flight. " +
                                    "The orb pulses to Jarvis's own " +
                                    "voice, adapts its quality to your " +
                                    "GPU and battery, and warms up for " +
                                    "Girlfriend Mode. Home shows live " +
                                    "weather, your next calendar event " +
                                    "and the gold price. Plus chat " +
                                    "message actions, search, dictation " +
                                    "in the composer, \"forget …\" " +
                                    "commands, a permission health " +
                                    "dashboard, full backup/restore " +
                                    "and an update checker."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showWhatsNew = false
                                    onSettingsChange(
                                        settings.copy(
                                            lastSeenVersionCode =
                                                currentVersionCode
                                        )
                                    )
                                }
                            ) { Text("Got it") }
                        }
                    )
                }
            }
        }
    }
}

/**
 * F4 floating pill navigation: a rounded glass bar floating over the
 * content (surface, 32dp radius, hairline outlineVariant border, soft
 * 6dp shadow) with a raised center mic. Selected tab = icon inside a
 * 40dp ring (1.5dp primary border) + bold primary 10sp label; the
 * Updates tab carries a small red dot badge. Mic states (F2 language,
 * kept): idle = flat surface + outline ring + Mic; a live session =
 * primary→secondary gradient + white Mic (GraphicEq while speaking);
 * ERROR only = error container + MicOff.
 */
@Composable
private fun JarvisFloatingNav(
    tab: Tab,
    onVoiceScreen: Boolean,
    voiceState: VoiceUiState,
    updatesBadge: Boolean = false,
    onTab: (Tab) -> Unit,
    onMic: () -> Unit
) {
    val sessionActive = onVoiceScreen || voiceState.status != VoiceStatus.IDLE
    val isError = voiceState.status == VoiceStatus.ERROR
    val isSpeaking = voiceState.status == VoiceStatus.SPEAKING
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingNavItem(
                    icon = Icons.Filled.Home,
                    label = "Home",
                    selected = tab == Tab.HOME,
                    modifier = Modifier.weight(1f)
                ) { onTab(Tab.HOME) }
                FloatingNavItem(
                    icon = Icons.Filled.Chat,
                    label = "Chat",
                    selected = tab == Tab.CHAT,
                    modifier = Modifier.weight(1f)
                ) { onTab(Tab.CHAT) }
                // Center slot — the raised mic overlaps this space.
                Spacer(modifier = Modifier.weight(1f))
                FloatingNavItem(
                    icon = Icons.Filled.Settings,
                    label = "Settings",
                    selected = tab == Tab.SETTINGS,
                    modifier = Modifier.weight(1f)
                ) { onTab(Tab.SETTINGS) }
                FloatingNavItem(
                    icon = Icons.Filled.Notifications,
                    label = "Updates",
                    selected = tab == Tab.UPDATES,
                    // Plan #104: the dot now means "a newer build is
                    // actually waiting on GitHub" (was decorative).
                    badge = updatesBadge,
                    modifier = Modifier.weight(1f)
                ) { onTab(Tab.UPDATES) }
            }
        }
        // Subtle scale-up while a voice session is live (D2 motion).
        val micScale by animateFloatAsState(
            targetValue = if (sessionActive && !isError) 1.08f else 1f,
            animationSpec = JarvisMotion.tweenMed(),
            label = "micScale"
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-14).dp)
                .size(56.dp)
                .scale(micScale)
                .then(
                    when {
                        isError -> Modifier.background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = CircleShape
                        )
                        sessionActive -> Modifier.background(
                            brush = Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            ),
                            shape = CircleShape
                        )
                        else -> Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = CircleShape
                            )
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                    }
                )
                .clickable(onClick = onMic),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isError -> Icons.Filled.MicOff
                    isSpeaking -> Icons.Filled.GraphicEq
                    else -> Icons.Filled.Mic
                },
                contentDescription = when {
                    isError -> "Voice session error"
                    sessionActive -> "Voice session active"
                    else -> "Start listening"
                },
                tint = when {
                    isError -> MaterialTheme.colorScheme.onErrorContainer
                    sessionActive -> Color.White
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun FloatingNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(23.dp)
            )
            if (badge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(7.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = tint
        )
    }
}

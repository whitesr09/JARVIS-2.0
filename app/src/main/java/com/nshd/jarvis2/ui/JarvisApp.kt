package com.nshd.jarvis2.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import com.nshd.jarvis2.voice.VoiceUiState

private enum class Tab { HOME, CHAT, SETTINGS, UPDATES }

/** Full-screen destinations layered over the tabs (L2: API + Memory, L3: Voice, L5: Avatars). */
private enum class SubScreen { NONE, PERMISSIONS, API, MEMORY, VOICE, AVATARS }

/** What is on screen right now — the AnimatedContent target in JarvisApp. */
private data class ScreenTarget(val tab: Tab, val sub: SubScreen)

@Composable
fun JarvisApp(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit
) {
    JarvisTheme(theme = settings.theme, customBgHex = settings.customBgHex) {
        var tab by remember { mutableStateOf(Tab.HOME) }
        var subScreen by remember { mutableStateOf(SubScreen.NONE) }
        // The center FAB reflects the live voice session (F2): it must
        // not sit there red + MicOff during normal operation.
        val voiceState by VoiceSessionManager.uiState.collectAsState()

        // Overlay bubble tap (and other external requests) land here.
        val voiceRequested by AppNavRequests.voiceRequested.collectAsState()
        LaunchedEffect(voiceRequested) {
            if (AppNavRequests.consumeVoiceRequest()) {
                subScreen = SubScreen.VOICE
            }
        }

        Scaffold(
            bottomBar = {
                JarvisBottomBar(
                    tab = tab,
                    onVoiceScreen = subScreen == SubScreen.VOICE,
                    voiceState = voiceState,
                    onTab = {
                        tab = it
                        subScreen = SubScreen.NONE
                    },
                    onMic = { subScreen = SubScreen.VOICE }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
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
                    modifier = Modifier.fillMaxSize()
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
                        SubScreen.NONE -> when (target.tab) {
                            Tab.HOME -> HomeScreen(
                                settings = settings,
                                listening = false,
                                onOpenPermissions = { subScreen = SubScreen.PERMISSIONS },
                                onOpenMemory = { subScreen = SubScreen.MEMORY },
                                onOpenSettings = { tab = Tab.SETTINGS },
                                onOpenVoice = { subScreen = SubScreen.VOICE }
                            )
                            Tab.CHAT -> ChatScreen(
                                settings = settings,
                                onOpenSettings = { tab = Tab.SETTINGS }
                            )
                            Tab.SETTINGS -> SettingsScreen(
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                                onOpenPermissions = { subScreen = SubScreen.PERMISSIONS },
                                onOpenApi = { subScreen = SubScreen.API },
                                onOpenMemory = { subScreen = SubScreen.MEMORY },
                                onOpenAvatars = { subScreen = SubScreen.AVATARS }
                            )
                            Tab.UPDATES -> UpdatesScreen()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom bar with a raised center mic button, like the reference (Page 1).
 * D1 Warm Minimal: FLAT bar surface (white in the light themes) with a
 * hairline top border and a soft purple-tinted shadow; the selected tab
 * sits in a lavender pill behind its icon; the mic FAB is flat purple.
 */
@Composable
private fun JarvisBottomBar(
    tab: Tab,
    onVoiceScreen: Boolean,
    voiceState: VoiceUiState,
    onTab: (Tab) -> Unit,
    onMic: () -> Unit
) {
    // FAB state language (F2): primary + Mic while a session runs
    // (GraphicEq while Jarvis is speaking); the error treatment —
    // error container + MicOff — appears ONLY on a real ERROR state.
    // Red during normal listening made the app look broken/muted.
    val sessionActive = onVoiceScreen || voiceState.status != VoiceStatus.IDLE
    val isError = voiceState.status == VoiceStatus.ERROR
    val isSpeaking = voiceState.status == VoiceStatus.SPEAKING
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .shadow(
                    elevation = JarvisDimens.navElevation,
                    shape = RectangleShape,
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                )
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem(Icons.Filled.Home, "Home", tab == Tab.HOME) { onTab(Tab.HOME) }
                NavItem(Icons.Filled.Chat, "Chat", tab == Tab.CHAT) { onTab(Tab.CHAT) }
                Spacer(modifier = Modifier.width(72.dp))
                NavItem(Icons.Filled.Settings, "Settings", tab == Tab.SETTINGS) { onTab(Tab.SETTINGS) }
                NavItem(Icons.Filled.Update, "Updates", tab == Tab.UPDATES) { onTab(Tab.UPDATES) }
            }
        }
        // Subtle scale-up while a voice session is live (D2 motion).
        val fabScale by animateFloatAsState(
            targetValue = if (sessionActive && !isError) 1.08f else 1f,
            animationSpec = JarvisMotion.tweenMed(),
            label = "fabScale"
        )
        FloatingActionButton(
            onClick = onMic,
            shape = CircleShape,
            containerColor = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            contentColor = if (isError) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = JarvisDimens.fabElevation,
                pressedElevation = JarvisDimens.fabElevation
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(60.dp)
                .scale(fabScale)
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
                }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.width(72.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = label, tint = tint)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
        }
    }
}

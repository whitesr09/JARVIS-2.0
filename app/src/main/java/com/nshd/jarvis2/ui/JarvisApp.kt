package com.nshd.jarvis2.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings

private enum class Tab { HOME, CHAT, SETTINGS, UPDATES }

/** Full-screen destinations layered over the tabs (L2: API + Memory, L3: Voice, L5: Avatars). */
private enum class SubScreen { NONE, PERMISSIONS, API, MEMORY, VOICE, AVATARS }

@Composable
fun JarvisApp(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit
) {
    JarvisTheme(theme = settings.theme, customBgHex = settings.customBgHex) {
        var tab by remember { mutableStateOf(Tab.HOME) }
        var subScreen by remember { mutableStateOf(SubScreen.NONE) }

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
                    listening = subScreen == SubScreen.VOICE,
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
                when (subScreen) {
                    SubScreen.PERMISSIONS -> PermissionsScreen(
                        onBack = { subScreen = SubScreen.NONE }
                    )
                    SubScreen.API -> ApiConnectionScreen(
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
                    SubScreen.NONE -> when (tab) {
                        Tab.HOME -> HomeScreen(
                            settings = settings,
                            listening = false,
                            onOpenPermissions = { subScreen = SubScreen.PERMISSIONS },
                            onOpenMemory = { subScreen = SubScreen.MEMORY },
                            onOpenSettings = { tab = Tab.SETTINGS },
                            onOpenVoice = { subScreen = SubScreen.VOICE }
                        )
                        Tab.CHAT -> ChatScreen(settings = settings)
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

/** Bottom bar with a raised center mic button, like the reference (Page 1). */
@Composable
private fun JarvisBottomBar(
    tab: Tab,
    listening: Boolean,
    onTab: (Tab) -> Unit,
    onMic: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
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
        FloatingActionButton(
            onClick = onMic,
            shape = CircleShape,
            containerColor = if (listening) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(60.dp)
        ) {
            Icon(
                imageVector = if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (listening) "Stop listening" else "Start listening"
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
            Icon(imageVector = icon, contentDescription = label, tint = tint)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
        }
    }
}

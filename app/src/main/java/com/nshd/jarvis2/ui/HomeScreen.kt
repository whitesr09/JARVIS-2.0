package com.nshd.jarvis2.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonalVideo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import java.util.Calendar

@Composable
fun HomeScreen(
    settings: JarvisSettings,
    listening: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoice: () -> Unit,
    onSettingsChange: (JarvisSettings) -> Unit = {},
    onOpenChat: () -> Unit = {}
) {
    // The Home orb + status pill mirror the live voice session state.
    val voiceState by VoiceSessionManager.uiState.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

        // ── Header: chat shortcut · gradient wordmark + greeting · settings ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenChat) {
                Icon(
                    imageVector = Icons.Filled.Chat,
                    contentDescription = "Open chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = settings.assistantName,
                    style = TextStyle(
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                )
                val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
                val dayPart = when {
                    hour < 12 -> "morning"
                    hour < 17 -> "afternoon"
                    else -> "evening"
                }
                Text(
                    text = "Good $dayPart, ${settings.callsign}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Open settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
        A11yWarningBanner(onFix = onOpenSettings)
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        // F4: the Canvas orb (orb.html system) at hero size — state
        // mapped from the live voice session via toOrbState()
        // (CONNECTING renders as THINKING, UNIVERSAL_ACTIVE as
        // LISTENING); the legacy `listening` flag still forces LISTENING.
        JarvisOrb(
            state = if (listening) OrbState.LISTENING else voiceState.status.toOrbState(),
            theme = settings.theme,
            customHex = settings.customBgHex,
            modifier = Modifier
                .size(330.dp)
                .clickable(onClick = onOpenVoice)
        )

        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
        StatusPill(
            status = voiceState.status,
            assistantName = settings.assistantName,
            onClick = onOpenVoice
        )
        if (voiceState.status == VoiceStatus.IDLE) {
            Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
            Text(
                text = "Tap mic or orb to power on",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(JarvisDimens.space2xl))

        WeatherCard()
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(JarvisDimens.spaceMd)
        ) {
            QuickTile(
                icon = Icons.Filled.PersonalVideo,
                title = "Screen",
                subtitle = "Share screen",
                modifier = Modifier.weight(1f)
            ) { onOpenVoice() }
            QuickTile(
                icon = Icons.Filled.Layers,
                title = "Memories",
                subtitle = "Recall Core",
                modifier = Modifier.weight(1f)
            ) { onOpenMemory() }
            QuickTile(
                icon = Icons.Filled.Language,
                title = "Language",
                subtitle = settings.language,
                modifier = Modifier.weight(1f)
            ) { showLanguageDialog = true }
        }
        // Bottom clearance so content never ends behind the floating nav (F2/F4).
        Spacer(modifier = Modifier.height(120.dp))
    }

    if (showLanguageDialog) {
        // The dialog reports picks immediately and stays open until
        // Done / outside tap (its onDismiss) — the tile subtitle and
        // the radio selection update live from the new settings.
        LanguagePickerDialog(
            current = settings.language,
            onDismiss = { showLanguageDialog = false },
            onPick = { picked ->
                onSettingsChange(settings.copy(language = picked))
            }
        )
    }
}

/**
 * F4 status pill: glass surface + hairline border, a state-coloured
 * dot (pulsing while a session is live) and a letterspaced 11sp bold
 * label. Tap = open Voice (start when off, retry on error).
 */
@Composable
private fun StatusPill(
    status: VoiceStatus,
    assistantName: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dotColor: Color
    val label: String
    val pulsing: Boolean
    when (status) {
        VoiceStatus.IDLE -> {
            dotColor = scheme.onSurfaceVariant
            label = "${assistantName.uppercase()} OFF • TAP TO START"
            pulsing = false
        }
        VoiceStatus.CONNECTING -> {
            dotColor = scheme.primary
            label = "CONNECTING…"
            pulsing = true
        }
        VoiceStatus.LISTENING, VoiceStatus.UNIVERSAL_ACTIVE -> {
            dotColor = scheme.primary
            label = "LISTENING"
            pulsing = true
        }
        VoiceStatus.THINKING -> {
            dotColor = scheme.tertiary
            label = "THINKING…"
            pulsing = false
        }
        VoiceStatus.SPEAKING -> {
            dotColor = scheme.secondary
            label = "SPEAKING"
            pulsing = true
        }
        VoiceStatus.ERROR -> {
            dotColor = scheme.error
            label = "SOMETHING'S WRONG — TAP TO RETRY"
            pulsing = false
        }
    }
    val pulse = rememberInfiniteTransition(label = "statusDotPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusDotAlpha"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = dotColor.copy(alpha = if (pulsing) dotAlpha else 1f),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Reference weather data (Page 1) — live weather arrives in a later level. */
@Composable
private fun WeatherCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(JarvisDimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "26°",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(JarvisDimens.spaceLg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Overcast",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Kizhuparamba • feels like 31°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "H 30°  L 25° • Humidity 95%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

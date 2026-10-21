package com.nshd.jarvis2.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus

@Composable
fun HomeScreen(
    settings: JarvisSettings,
    listening: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoice: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
        A11yWarningBanner(onFix = onOpenSettings)
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
        Text(
            text = settings.assistantName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
        // Greeting hierarchy (D2): display line + the operator's name
        // emphasised in primary on its own line.
        Text(
            text = "Welcome back,",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = settings.callsign,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(JarvisDimens.spaceXl))

        // The Home orb mirrors the live voice session state (D3 tints):
        // thinking swirl while the brain works, error colour on failure.
        val voiceState by VoiceSessionManager.uiState.collectAsState()
        ParticleOrb(
            listening = listening,
            speaking = voiceState.status == VoiceStatus.SPEAKING,
            thinking = voiceState.status == VoiceStatus.THINKING ||
                voiceState.status == VoiceStatus.CONNECTING,
            error = voiceState.status == VoiceStatus.ERROR,
            modifier = Modifier
                .size(230.dp)
                .clickable(onClick = onOpenVoice)
        )

        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
        StatusPill(listening = listening)
        Spacer(modifier = Modifier.height(JarvisDimens.space2xl))

        WeatherCard()
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(JarvisDimens.spaceMd)
        ) {
            QuickTile(
                icon = Icons.Filled.Smartphone,
                label = "Screen",
                modifier = Modifier.weight(1f)
            ) { onOpenPermissions() }
            QuickTile(
                icon = Icons.Filled.Memory,
                label = "Memories",
                modifier = Modifier.weight(1f)
            ) { onOpenMemory() }
            QuickTile(
                icon = Icons.Filled.Language,
                label = "Language",
                modifier = Modifier.weight(1f)
            ) { onOpenSettings() }
        }
        Spacer(modifier = Modifier.height(JarvisDimens.space3xl))
    }
}

@Composable
private fun StatusPill(listening: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (listening) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(8.dp)
            ) {}
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = if (listening) "Listening…" else "Jarvis is off — tap the mic to start",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Reference weather data (Page 1) — live weather arrives in a later level. */
@Composable
private fun WeatherCard() {
    Card(
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.heroElevation),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(JarvisDimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(JarvisDimens.radiusField),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.size(JarvisDimens.spaceLg))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "26°",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(JarvisDimens.spaceSm))
                    Text(
                        text = "Overcast",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
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
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "H 30°  L 25°",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Humidity 95%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = JarvisDimens.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}


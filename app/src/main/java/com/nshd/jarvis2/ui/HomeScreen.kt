package com.nshd.jarvis2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun HomeScreen(
    settings: JarvisSettings,
    listening: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = settings.assistantName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Welcome back, ${settings.callsign}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(18.dp))

        ParticleOrb(listening = listening)

        Spacer(modifier = Modifier.height(14.dp))
        StatusPill(listening = listening)
        Spacer(modifier = Modifier.height(22.dp))

        WeatherCard()
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
        Spacer(modifier = Modifier.height(28.dp))
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
                text = if (listening) "LISTENING…" else "JARVIS OFF • TAP THE MIC TO START",
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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "26°C  Overcast",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Particle orb — Compose Canvas port of the simulator's state machine:
 * idle = slow breathing, listening = faster, larger pulse. (Amplitude-driven
 * speaking/thinking states arrive with the voice loop in Level 3.)
 */
@Composable
private fun ParticleOrb(listening: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(listening) {
        while (true) {
            withFrameMillis {
                phase += if (listening) 0.045f else 0.014f
            }
        }
    }

    val points = remember {
        val goldenAngle = 2.399963f
        List(140) { i ->
            val frac = (i + 0.5f) / 140f
            Triple(i * goldenAngle, sqrt(frac), 1.4f + (i % 5) * 0.35f)
        }
    }

    Canvas(modifier = Modifier.size(230.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = size.minDimension / 2f * 0.92f
        val breathe = 1f + 0.035f * sin(phase * 1.6f)
        val pulse = if (listening) 1f + 0.05f * sin(phase * 4.2f) else 1f

        // Soft core glow
        drawCircle(
            color = primary.copy(alpha = 0.10f),
            radius = baseR * 0.62f * breathe,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = primary.copy(alpha = 0.14f),
            radius = baseR * 0.38f * breathe * pulse,
            center = Offset(cx, cy)
        )

        points.forEachIndexed { index, (angle, frac, dotSize) ->
            val wobble = 1f + 0.045f * sin(phase * 2.2f + angle * 3f)
            val r = baseR * frac * breathe * pulse * wobble
            val x = cx + (r * cos(angle + phase * 0.12f))
            val y = cy + (r * sin(angle + phase * 0.12f))
            val color = if (index % 4 == 0) secondary else primary
            val alpha = 0.25f + 0.65f * (1f - frac)
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dotSize * (if (listening) 1.25f else 1f),
                center = Offset(x, y)
            )
        }
    }
}

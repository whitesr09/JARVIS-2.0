package com.nshd.jarvis2.ui

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.service.FloatingOverlayService

/**
 * Avatar picker (Level 5): the 12 companions in a 3-column grid.
 * Avatars sit directly on the plain card surface — the drawables are
 * transparent with NO colored disc behind them; selection is shown by a
 * primary border + check badge only.
 */
@Composable
fun AvatarsScreen(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    val context = LocalContext.current
    var overlayRunning by remember { mutableStateOf(FloatingOverlayService.isRunning()) }
    val canOverlay = Settings.canDrawOverlays(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = JarvisDimens.screenPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = JarvisDimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(
                    text = "Floating Avatar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Pick the companion shown while ${settings.assistantName} works",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        Card(
            shape = RoundedCornerShape(JarvisDimens.radiusCard),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.heroElevation),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(JarvisDimens.cardPadding)) {
                Text(
                    text = when {
                        !canOverlay -> "Overlay permission is not granted yet — the avatar " +
                            "can be selected now and shown once permission is on."
                        overlayRunning -> "Floating avatar is ON — it floats over other apps."
                        else -> "Floating avatar is OFF."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row {
                    Button(
                        onClick = {
                            if (!canOverlay) {
                                onOpenPermissions()
                            } else {
                                FloatingOverlayService.start(context)
                                overlayRunning = true
                            }
                        },
                        enabled = !overlayRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text("Start floating") }
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = {
                            FloatingOverlayService.stop(context)
                            overlayRunning = false
                        },
                        enabled = overlayRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text("Stop") }
                    if (!canOverlay) {
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = onOpenPermissions,
                            modifier = Modifier.weight(1f)
                        ) { Text("Permission") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap the avatar: abort a running task or open voice. " +
                        "Double-tap: task HUD. Drag: it snaps to the nearest edge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(JarvisDimens.spaceXl))
        SectionLabel("Choose an avatar")
        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = JarvisDimens.spaceXl),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(JarvisDimens.spaceMd),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(JarvisDimens.spaceMd),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(AvatarCatalog.all) { avatar ->
                val selected = settings.avatarId == avatar.id
                // Selected cell swells a touch (spring back on deselect).
                val cellScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (selected) 1.03f else 1f,
                    animationSpec = JarvisMotion.tweenMed(),
                    label = "avatarCellScale"
                )
                Card(
                    onClick = {
                        onSettingsChange(settings.copy(avatarId = avatar.id))
                        // Refresh the live bubble with the new figure.
                        if (FloatingOverlayService.isRunning()) {
                            FloatingOverlayService.start(context)
                        }
                    },
                    modifier = Modifier.graphicsLayer {
                        scaleX = cellScale
                        scaleY = cellScale
                    },
                    shape = RoundedCornerShape(JarvisDimens.radiusCard),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = if (selected) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    },
                    elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = JarvisDimens.spaceMd)
                    ) {
                        // Plain surface behind the avatar — no disc, no plate.
                        Icon(
                            painter = painterResource(avatar.resId),
                            contentDescription = avatar.label,
                            tint = androidx.compose.ui.graphics.Color.Unspecified,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = avatar.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (selected) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

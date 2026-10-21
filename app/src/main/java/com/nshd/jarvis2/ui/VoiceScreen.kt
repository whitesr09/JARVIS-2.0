package com.nshd.jarvis2.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.voice.VoiceMode
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus

/**
 * Level 3 voice screen: the particle orb driven by the live session's
 * state + amplitude, streaming captions, mic mute and end-session
 * controls. Reached from the center mic FAB and the Home orb. The
 * session itself lives in [VoiceSessionManager] — leaving this screen
 * ends it.
 */
@Composable
fun VoiceScreen(
    settings: JarvisSettings,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val manager = VoiceSessionManager
    val providerStore = remember { ProviderStore(context) }
    val db = remember { MemoryDb(context) }

    val uiState by manager.uiState.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var requestedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission && !requestedOnce) {
            requestedOnce = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            manager.start(context, settings, providerStore, db)
        }
    }
    DisposableEffect(Unit) {
        onDispose { manager.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // ── Header ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                manager.stop()
                onBack()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${settings.assistantName} Voice",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = settings.persona,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ModeBadge(mode = uiState.mode, voiceName = uiState.voiceName)
        }

        if (!hasPermission) {
            PermissionDenied(
                assistantName = settings.assistantName,
                onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenSettings = onOpenSettings
            )
        } else {
            VoiceSessionBody(
                settings = settings,
                manager = manager,
                onBack = onBack,
                onOpenChat = onOpenChat,
                modifier = Modifier.weight(1f)
            )
        }
    }
    ConfirmGateDialog()
}

/** Orb + status + captions + controls, shown once the mic is granted. */
@Composable
private fun VoiceSessionBody(
    settings: JarvisSettings,
    manager: VoiceSessionManager,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by manager.uiState.collectAsState()
    val amplitude by manager.amplitude.collectAsState()
    val captions by manager.captions.collectAsState()

  Column(modifier = modifier) {
    Spacer(modifier = Modifier.height(10.dp))

    // ── Orb (tap toggles the mic) ──────────────────────────────────────
    ParticleOrb(
        listening = uiState.status == VoiceStatus.LISTENING ||
            uiState.status == VoiceStatus.FALLBACK_ACTIVE,
        speaking = uiState.status == VoiceStatus.SPEAKING,
        amplitude = amplitude,
        modifier = Modifier
            .size(250.dp)
            .align(Alignment.CenterHorizontally)
            .clickable { manager.setMicMuted(!uiState.micMuted) }
    )

    Text(
        text = statusText(uiState.status, settings.assistantName, uiState.micMuted),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )

    uiState.notice?.let { notice ->
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
    }

    Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

    // ── Captions (flat surface card) ───────────────────────────────────
    val listState = rememberLazyListState()
    LaunchedEffect(captions) {
        if (captions.isNotEmpty()) {
            listState.animateScrollToItem(captions.lastIndex)
        }
    }
    Card(
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(JarvisDimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(JarvisDimens.spaceSm)
        ) {
            items(captions) { caption ->
                AppearOnEntry {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (caption.fromUser) {
                            Alignment.End
                        } else {
                            Alignment.Start
                        }
                    ) {
                        Text(
                            text = if (caption.fromUser) "You" else settings.assistantName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = caption.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

    // ── Screen share (LIVE sessions): frames stream to Gemini Live ────
    if (uiState.mode == VoiceMode.LIVE) {
        val shareControl = rememberScreenShareControl()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            androidx.compose.material3.FilterChip(
                selected = shareControl.active,
                onClick = { shareControl.toggle() },
                label = {
                    Text(
                        if (shareControl.active) "Sharing screen — tap to stop"
                        else "Share screen"
                    )
                },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                shape = RoundedCornerShape(50)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
    }

    // ── Controls / error actions ───────────────────────────────────────
    if (uiState.status == VoiceStatus.ERROR) {
        Card(
            shape = RoundedCornerShape(JarvisDimens.radiusCard),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = uiState.errorMessage ?: "The voice session hit a problem.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { manager.retry() },
                modifier = Modifier.weight(1f)
            ) { Text("Retry") }
            OutlinedButton(
                onClick = {
                    manager.stop()
                    onOpenChat()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Use text chat") }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = JarvisDimens.spaceLg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { manager.setMicMuted(!uiState.micMuted) },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = if (uiState.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (uiState.micMuted) "Unmute microphone" else "Mute microphone"
                )
            }
            Spacer(modifier = Modifier.width(JarvisDimens.spaceXl))
            FilledIconButton(
                onClick = {
                    manager.stop()
                    onBack()
                },
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CallEnd,
                    contentDescription = "End session"
                )
            }
        }
    }
  }
}

@Composable
private fun ModeBadge(mode: VoiceMode, voiceName: String) {
    // Shared pill language: LIVE reads as a success state, the on-device
    // fallback and the idle badge stay neutral.
    when (mode) {
        VoiceMode.LIVE -> JarvisPill(text = "LIVE • $voiceName", tone = PillTone.SUCCESS)
        VoiceMode.FALLBACK -> JarvisPill(text = "FALLBACK", tone = PillTone.NEUTRAL)
        VoiceMode.NONE -> JarvisPill(text = "VOICE", tone = PillTone.NEUTRAL)
    }
}

@Composable
private fun PermissionDenied(
    assistantName: String,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.MicOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "$assistantName needs the microphone to hear you.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Voice is processed by the provider you configured; nothing is " +
                "recorded or stored on this device beyond your chat history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(onClick = onGrant) { Text("Grant microphone access") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
    }
}

private fun statusText(status: VoiceStatus, assistantName: String, micMuted: Boolean): String =
    when (status) {
        VoiceStatus.IDLE -> "Tap the mic to talk"
        VoiceStatus.CONNECTING -> "Connecting…"
        VoiceStatus.LISTENING -> if (micMuted) "Microphone muted — tap the mic to resume" else "Listening…"
        VoiceStatus.SPEAKING -> "$assistantName is speaking…"
        VoiceStatus.THINKING -> "$assistantName is thinking…"
        VoiceStatus.FALLBACK_ACTIVE -> "Listening (on-device)…"
        VoiceStatus.ERROR -> "Something went wrong"
    }

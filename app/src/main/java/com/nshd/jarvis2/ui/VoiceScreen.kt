package com.nshd.jarvis2.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.voice.CaptionKind
import com.nshd.jarvis2.voice.VoiceCaption
import com.nshd.jarvis2.voice.VoiceMode
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import com.nshd.jarvis2.voice.VoiceUiState

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
                // F5: one line, ellipsized — a wide engine badge used to
                // squeeze this into "Jarvis / Voice" on two lines.
                Text(
                    text = "${settings.assistantName} Voice",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = settings.persona,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            ModeBadge(uiState = uiState)
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
    // F7: same orb-state helper as Home (task activity → THINKING).
    val queueTasks by com.nshd.jarvis2.brain.TaskQueue.tasks.collectAsState()
    val taskRunning = queueTasks.any {
        it.status == com.nshd.jarvis2.brain.TaskStatus.RUNNING
    }

    // F4 crash hardening: every remember in this body sits at the top
    // level, unconditionally. showDetails used to be remembered inside
    // the ERROR branch and the screen-share control inside the LIVE
    // branch — when status/mode flipped mid-session the remember call
    // order changed, corrupting the slot table (the voice crash). Both
    // are hoisted here; the branches below only read them.
    var showDetails by remember { mutableStateOf(false) }
    val shareControl = rememberScreenShareControl()

  Column(modifier = modifier) {
    Spacer(modifier = Modifier.height(10.dp))

    // ── Orb (tap toggles the mic) ──────────────────────────────────────
    // F4: Canvas JarvisOrb (orb.html port) fed with the live amplitude.
    // F7: 300dp → 340dp, state via the shared orbStateFor helper.
    JarvisOrb(
        state = orbStateFor(uiState.status, taskRunning),
        amplitude = amplitude,
        liveAudio = true,
        theme = settings.theme,
        customHex = settings.customBgHex,
        modifier = Modifier
            .size(340.dp)
            .align(Alignment.CenterHorizontally)
            .clickable { manager.setMicMuted(!uiState.micMuted) }
    )

    Text(
        text = statusText(uiState.status, settings.assistantName, uiState.micMuted),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )

    uiState.sttHint?.let { hint ->
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
    }

    // F7/I: the EFFECTIVE listening language, always visible during a
    // session — if transcripts ever land in the wrong script again,
    // this line shows which language the recognizer is pinned to.
    if (uiState.status != VoiceStatus.IDLE) {
        Text(
            text = "Listening language: " +
                com.nshd.jarvis2.voice.UniversalVoice.sttDisplayName(settings.language),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
    }

    // F5: in Universal mode, name the voice actually speaking — the
    // DEVICE TTS voice — and make clear Gemini voices are Live-only.
    if (uiState.mode == VoiceMode.UNIVERSAL && uiState.ttsVoiceName.isNotBlank()) {
        Text(
            text = "Voice: ${uiState.ttsVoiceName} (device TTS)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
        Text(
            text = "Gemini voices work in Native Live; Universal uses the device voice.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp)
        )
    }

    Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

    // ── Captions (flat surface card) — hidden entirely while empty,
    // so an error before the first turn doesn't leave a blank white
    // box floating on the screen (F2). ────────────────────────────────
    val listState = rememberLazyListState()
    LaunchedEffect(captions) {
        if (captions.isNotEmpty()) {
            listState.animateScrollToItem(captions.lastIndex)
        }
    }
    if (captions.isNotEmpty()) {
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
                        CaptionRow(caption = caption, assistantName = settings.assistantName)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
    } else {
        Spacer(modifier = Modifier.weight(1f))
    }

    // ── Screen share (LIVE sessions): frames stream to Gemini Live ────
    if (uiState.mode == VoiceMode.LIVE) {
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
        // Friendly headline first; the raw provider string lives behind
        // a small "Details" expander, never as the primary UI (F2).
        val mapped = FriendlyErrors.map(uiState.errorMessage)
        Card(
            shape = RoundedCornerShape(JarvisDimens.radiusCard),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = mapped.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                // F4.1: the expander always exists now — besides the
                // raw provider string it shows the last CrashLogger
                // stage lines, so a screenshot of this card tells us
                // exactly how far the connect sequence got before it
                // died. Plain reads in the branch (no remember — the
                // F4 conditional-remember lesson).
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(if (showDetails) "Hide details" else "Details")
                }
                if (showDetails) {
                    if (mapped.rawDetail != null) {
                        Text(
                            text = mapped.rawDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val stageTail = com.nshd.jarvis2.CrashLogger
                        .sessionLogTail(
                            androidx.compose.ui.platform.LocalContext.current,
                            15
                        )
                    if (stageTail.isNotEmpty()) {
                        Text(
                            text = stageTail.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
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
private fun ModeBadge(uiState: VoiceUiState) {
    // The badge names the ENGINE (F2): "LIVE • Kore" for native audio,
    // "UNIVERSAL • <chat model>" for the any-model engine. LIVE reads
    // as a success state, Universal stays neutral.
    // F5: local pill (not JarvisPill) so the text is 10sp, single-line,
    // and the model part is truncated to 18 chars — the full label
    // used to push the header title into a second line.
    val raw = uiState.engineLabel.ifBlank {
        when (uiState.mode) {
            VoiceMode.LIVE -> "LIVE • ${uiState.voiceName}"
            VoiceMode.UNIVERSAL -> "UNIVERSAL"
            VoiceMode.NONE -> "VOICE"
        }
    }
    val parts = raw.split(" • ", limit = 2)
    val label = if (parts.size == 2) {
        val model = parts[1]
        val short = if (model.length > 18) model.take(17) + "…" else model
        "${parts[0]} • $short"
    } else {
        raw
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.35f
    val colors = if (uiState.mode == VoiceMode.LIVE) {
        if (dark) {
            JarvisSemanticColors.successBright.copy(alpha = 0.18f) to
                JarvisSemanticColors.successBright
        } else {
            JarvisSemanticColors.successContainer to JarvisSemanticColors.success
        }
    } else {
        MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = colors.first
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = colors.second,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** One caption line: user/assistant text, or a centered status chip for tool/system events. */
@Composable
private fun CaptionRow(caption: VoiceCaption, assistantName: String) {
    when (caption.kind) {
        CaptionKind.TOOL, CaptionKind.SYSTEM -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (caption.kind == CaptionKind.SYSTEM) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (caption.kind == CaptionKind.SYSTEM) {
                                Icons.Filled.Info
                            } else {
                                Icons.Filled.Build
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = caption.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        CaptionKind.USER, CaptionKind.ASSISTANT -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (caption.fromUser) {
                    Alignment.End
                } else {
                    Alignment.Start
                }
            ) {
                Text(
                    text = if (caption.fromUser) "You" else assistantName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = caption.text,
                    style = MaterialTheme.typography.bodyMedium,
                    // Partial (still-streaming) text renders dimmed.
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (caption.partial) 0.55f else 1f
                    )
                )
            }
        }
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
        VoiceStatus.UNIVERSAL_ACTIVE -> "Listening…"
        VoiceStatus.ERROR -> "Something went wrong"
    }

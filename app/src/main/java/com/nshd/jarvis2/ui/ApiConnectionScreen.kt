package com.nshd.jarvis2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.ModelDiscovery
import com.nshd.jarvis2.brain.ModelProbeResult
import com.nshd.jarvis2.brain.ProbeStatus
import com.nshd.jarvis2.brain.ProviderPresets
import com.nshd.jarvis2.brain.ProviderStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Level 2: real API & Connection screen. Per-provider base URL / key /
 * models, persisted encrypted; an active provider (tried first) with
 * automatic failover to the next configured provider.
 *
 * F2 adds live model discovery: "Fetch available models" lists what the
 * provider actually offers (native-audio models badged LIVE AUDIO) so
 * chat/Live models can be picked instead of typed from memory.
 *
 * F3 Connection Test v2: Stage 1 checks the endpoint (GET /models with
 * auth) and stops on failure; Stage 2 sequentially probes chat models
 * (configured model first, then discovered, max 7) with a 1-token
 * "ping" completion, skipping native-audio models. Results are cached
 * per provider in [ProviderStore] and shown instantly on reopen.
 */
@Composable
fun ApiConnectionScreen(
    settings: JarvisSettings? = null,
    onSettingsChange: (JarvisSettings) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = remember { ProviderStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var activeId by remember { mutableStateOf(store.activeProviderId()) }
    var editId by remember { mutableStateOf(store.activeProviderId()) }
    var config by remember(editId) { mutableStateOf(store.loadConfig(editId)) }
    var showKey by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) } // re-read "key set" dots after save

    // ── Connection Test v2 (F3) ────────────────────────────────────────
    var probing by remember { mutableStateOf(false) }
    var probeStageError by remember { mutableStateOf<String?>(null) }
    // Cached results load instantly per provider (keyed on editId).
    var probeResults by remember(editId) {
        mutableStateOf(store.cachedProbeResults(editId))
    }
    var showProbeDialog by remember { mutableStateOf(false) }

    fun runConnectionTestV2() {
        if (probing) return
        store.saveConfig(config)
        config = store.loadConfig(editId)
        refreshTick++
        probing = true
        probeStageError = null
        val targetId = editId
        scope.launch {
            val outcome = ModelDiscovery.runConnectionTestV2(store.loadConfig(targetId))
            probing = false
            if (!outcome.endpointOk) {
                // Stage 1 failed — friendly headline, no Stage 2 noise.
                probeStageError = FriendlyErrors.map(outcome.endpointError).headline
            } else {
                probeResults = outcome.probes
                store.saveProbeResults(targetId, outcome.probes)
                store.saveModelIds(targetId, outcome.models.map { it.id })
                refreshTick++
                showProbeDialog = true
            }
        }
    }

    // If the configured chat model failed but another probed model
    // works, offer the one-tap switch to the first working alternative.
    val configuredProbe = probeResults.firstOrNull { it.modelId == config.chatModel }
    val workingAlternative = if (configuredProbe?.status == ProbeStatus.FAILED) {
        probeResults.firstOrNull {
            it.status == ProbeStatus.WORKING && it.modelId != config.chatModel
        }
    } else {
        null
    }

    fun useAlternative(modelId: String) {
        config = config.copy(chatModel = modelId)
        store.saveConfig(config)
        config = store.loadConfig(editId)
        refreshTick++
        showProbeDialog = false
        scope.launch { snackbar.showSnackbar("Chat model set to $modelId") }
    }

    // ── Model discovery (F2) ───────────────────────────────────────────
    var fetchingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var modelsDialog by remember { mutableStateOf<List<ModelDiscovery.ModelInfo>?>(null) }

    fun fetchModels(openDialogWhenDone: Boolean) {
        if (fetchingModels) return
        fetchingModels = true
        modelsError = null
        val toFetch = config
        val targetId = editId
        scope.launch {
            val result = ModelDiscovery.fetchModels(toFetch)
            fetchingModels = false
            result.fold(
                onSuccess = { models ->
                    store.saveModelIds(targetId, models.map { it.id })
                    refreshTick++
                    if (openDialogWhenDone) modelsDialog = models
                },
                onFailure = { error ->
                    // F6 audit: the raw exception text used to be
                    // appended to this user-visible error verbatim —
                    // route it to AppLog and show the friendly reason.
                    com.nshd.jarvis2.core.AppLog.e(
                        "API",
                        "fetch models failed: ${error.message ?: "unknown"}",
                        error
                    )
                    modelsError = "Couldn't fetch models — check the key " +
                        "and connection. (" +
                        com.nshd.jarvis2.ui.FriendlyErrors.shortReason(error.message) +
                        ")"
                }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(
                    text = "API & Connection",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Providers, keys & models",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // F5.1: the focused key/model field + Test button stay
                // reachable above the keyboard.
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            SectionLabel("Active provider — tried first")
            Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
            Card(
                shape = RoundedCornerShape(JarvisDimens.radiusCard),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = JarvisDimens.spaceSm)) {
                    ProviderPresets.ALL.forEach { preset ->
                        val cfg = remember(preset.id, refreshTick) { store.loadConfig(preset.id) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeId = preset.id
                                    store.setActiveProviderId(preset.id)
                                    editId = preset.id
                                }
                                .padding(
                                    horizontal = JarvisDimens.spaceLg,
                                    vertical = JarvisDimens.spaceSm
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = activeId == preset.id,
                                onClick = {
                                    activeId = preset.id
                                    store.setActiveProviderId(preset.id)
                                    editId = preset.id
                                }
                            )
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            JarvisPill(
                                text = if (cfg.apiKey.isNotBlank()) "Key set" else "No key",
                                tone = if (cfg.apiKey.isNotBlank()) PillTone.SUCCESS else PillTone.WARNING,
                                icon = if (cfg.apiKey.isNotBlank()) Icons.Filled.Check else Icons.Filled.Warning
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(JarvisDimens.spaceXl))
            SectionLabel("Edit provider")
            Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
            Row(modifier = Modifier.fillMaxWidth()) {
                ProviderPresets.ALL.forEach { preset ->
                    FilterChip(
                        selected = editId == preset.id,
                        onClick = { editId = preset.id; probeStageError = null },
                        label = { Text(preset.label) },
                        leadingIcon = if (editId == preset.id) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.padding(end = 6.dp),
                        shape = RoundedCornerShape(50)
                    )
                }
            }
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

            Card(
                shape = RoundedCornerShape(JarvisDimens.radiusCard),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.heroElevation),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = config.baseUrl,
                        onValueChange = { config = config.copy(baseUrl = it) },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = config.apiKey,
                        onValueChange = { config = config.copy(apiKey = it) },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = {
                                    clipboard.getText()?.text
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { config = config.copy(apiKey = it.trim()) }
                                }) {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste key")
                                }
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        imageVector = if (showKey) Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                        contentDescription = if (showKey) "Hide key" else "Show key"
                                    )
                                }
                                IconButton(onClick = { config = config.copy(apiKey = "") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear key")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = config.chatModel,
                        onValueChange = { config = config.copy(chatModel = it) },
                        label = { Text("Chat model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // F5: a live-only model in the CHAT field can never
                    // answer chats (REST completions bounce off it with
                    // HTTP 400) — warn inline and, for Gemini, offer the
                    // one-tap move into the Live model field.
                    if (config.chatModel.isNotBlank() &&
                        ModelDiscovery.classify(config.chatModel) ==
                        ModelDiscovery.Kind.NATIVE_AUDIO
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(JarvisDimens.radiusField),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(JarvisDimens.spaceMd)) {
                                Text(
                                    text = "Live-only model — it can't answer chats. " +
                                        "Pick a text model for Chat/Universal, keep " +
                                        "this one as the Live model.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (editId == ProviderPresets.GEMINI.id && settings != null) {
                                    TextButton(
                                        onClick = {
                                            onSettingsChange(
                                                settings.copy(liveModel = config.chatModel)
                                            )
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    "Live model set to ${config.chatModel} — " +
                                                        "now pick a text chat model"
                                                )
                                            }
                                        }
                                    ) { Text("Move to Live model") }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = config.reasoningModel,
                        onValueChange = { config = config.copy(reasoningModel = it) },
                        label = { Text("Reasoning model (Code mode)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // Model discovery: list what this provider actually
                    // offers instead of typing model ids from memory.
                    // A cached list opens the picker instantly while a
                    // fresh fetch runs in the background.
                    val cachedModels = remember(editId, refreshTick) {
                        store.cachedModelIds(editId).map { ModelDiscovery.info(it) }
                    }
                    OutlinedButton(
                        onClick = {
                            if (cachedModels.isNotEmpty()) {
                                modelsDialog = cachedModels
                                fetchModels(openDialogWhenDone = false)
                            } else {
                                fetchModels(openDialogWhenDone = true)
                            }
                        },
                        enabled = !fetchingModels,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (fetchingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                fetchingModels -> "Fetching models…"
                                cachedModels.isNotEmpty() ->
                                    "Available models (${cachedModels.size})"
                                else -> "Fetch available models"
                            }
                        )
                    }
                    if (modelsError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = modelsError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row {
                        Button(
                            onClick = {
                                store.saveConfig(config)
                                config = store.loadConfig(editId)
                                refreshTick++
                                scope.launch { snackbar.showSnackbar("Saved") }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Save") }
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = { runConnectionTestV2() },
                            enabled = !probing,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (probing) "Testing…" else "Test Connection") }
                    }
                    if (probing) {
                        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Checking endpoint, then probing models one by one…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (probeStageError != null) {
                        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
                        Surface(
                            shape = RoundedCornerShape(JarvisDimens.radiusField),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(JarvisDimens.spaceMd),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(JarvisDimens.spaceSm))
                                Text(
                                    text = probeStageError!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    if (probeResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
                        // Cached / fresh probe results card (F3): shown
                        // instantly from ProviderStore on reopen.
                        Surface(
                            shape = RoundedCornerShape(JarvisDimens.radiusField),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(JarvisDimens.spaceMd)) {
                                val lastTs = probeResults.maxOfOrNull { it.timestamp } ?: 0L
                                Text(
                                    text = if (lastTs > 0L) {
                                        "Model test results • " +
                                            SimpleDateFormat(
                                                "d MMM, h:mm a", Locale.ENGLISH
                                            ).format(Date(lastTs))
                                    } else {
                                        "Model test results"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                probeResults.forEach { result ->
                                    ProbeResultRow(result)
                                }
                                if (workingAlternative != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { useAlternative(workingAlternative.modelId) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Use ${workingAlternative.modelId}") }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    TextButton(
                                        onClick = { showProbeDialog = true }
                                    ) { Text("Details") }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    TextButton(
                                        onClick = { runConnectionTestV2() },
                                        enabled = !probing
                                    ) { Text(if (probing) "Testing…" else "Re-test") }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Keys stay on this device (encrypted). Failover: if the active " +
                    "provider fails, Jarvis tries the next configured one. The Gemini " +
                    "key also powers Gemini Live voice (Level 3) — no separate key needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(120.dp))
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.padding(12.dp))
    }

    // ── Model picker dialog ────────────────────────────────────────────
    val dialogModels = modelsDialog
    if (dialogModels != null) {
        AlertDialog(
            onDismissRequest = { modelsDialog = null },
            title = { Text("Available models — ${ProviderPresets.byId(editId).label}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Tap a model to use it as the chat model. " +
                            "LIVE AUDIO models can also power Native Live voice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    dialogModels.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val isNative = model.kind ==
                                        ModelDiscovery.Kind.NATIVE_AUDIO
                                    config = config.copy(chatModel = model.id)
                                    var note = "Chat model: ${model.id} — tap Save"
                                    if (isNative && editId == ProviderPresets.GEMINI.id &&
                                        settings != null
                                    ) {
                                        onSettingsChange(settings.copy(liveModel = model.id))
                                        note = "Chat + Live model set: ${model.id}"
                                    }
                                    modelsDialog = null
                                    scope.launch { snackbar.showSnackbar(note) }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model.id,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (model.kind == ModelDiscovery.Kind.NATIVE_AUDIO) {
                                JarvisPill(text = "LIVE AUDIO", tone = PillTone.PRIMARY)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelsDialog = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(
                    onClick = { fetchModels(openDialogWhenDone = true) },
                    enabled = !fetchingModels
                ) { Text(if (fetchingModels) "Fetching…" else "Re-fetch") }
            }
        )
    }

    // ── Connection Test v2 results dialog (F3) ─────────────────────────
    if (showProbeDialog && probeResults.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showProbeDialog = false },
            title = {
                Text("Connection test — ${ProviderPresets.byId(editId).label}")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Endpoint OK. Each chat model was probed with a " +
                            "1-token ping; live-audio models are not chat-tested.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    probeResults.forEach { result -> ProbeResultRow(result) }
                    if (workingAlternative != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Your chat model failed, but " +
                                "${workingAlternative.modelId} works.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { useAlternative(workingAlternative.modelId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Use ${workingAlternative.modelId}") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProbeDialog = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(
                    onClick = { runConnectionTestV2() },
                    enabled = !probing
                ) { Text(if (probing) "Testing…" else "Re-test") }
            }
        )
    }
}

/** One probe-result line: ✓ Working (N ms) / ✕ Failed (reason) / ◐ skipped. */
@Composable
private fun ProbeResultRow(result: ModelProbeResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (result.status) {
                ProbeStatus.WORKING -> "✓"
                ProbeStatus.FAILED -> "✕"
                ProbeStatus.SKIPPED_AUDIO -> "◐"
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = when (result.status) {
                ProbeStatus.WORKING -> JarvisSemanticColors.success
                ProbeStatus.FAILED -> MaterialTheme.colorScheme.error
                ProbeStatus.SKIPPED_AUDIO -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.width(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.modelId,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = when (result.status) {
                    ProbeStatus.WORKING -> "Working (${result.latencyMs} ms)"
                    ProbeStatus.FAILED ->
                        "Failed (${result.error.ifBlank { "unknown error" }})"
                    ProbeStatus.SKIPPED_AUDIO ->
                        "Live-audio model — not chat-tested"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

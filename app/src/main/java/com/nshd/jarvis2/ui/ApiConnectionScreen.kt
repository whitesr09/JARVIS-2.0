package com.nshd.jarvis2.ui

import androidx.compose.foundation.clickable
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
import com.nshd.jarvis2.brain.ProviderConfig
import com.nshd.jarvis2.brain.ProviderPresets
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.brain.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Level 2: real API & Connection screen. Per-provider base URL / key /
 * models, persisted encrypted; an active provider (tried first) with
 * automatic failover to the next configured provider.
 *
 * F2 adds live model discovery: "Fetch available models" lists what the
 * provider actually offers (native-audio models badged LIVE AUDIO) so
 * chat/Live models can be picked instead of typed from memory.
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
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var refreshTick by remember { mutableStateOf(0) } // re-read "key set" dots after save

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
                    modelsError = "Couldn't fetch models — check key/connection. " +
                        (error.message ?: "")
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
                        onClick = { editId = preset.id; testResult = null },
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
                            onClick = {
                                store.saveConfig(config)
                                refreshTick++
                                testing = true
                                testResult = null
                                val toTest: ProviderConfig = store.loadConfig(editId)
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        toTest.toProvider().testConnection()
                                    }
                                    testing = false
                                    testResult = result
                                }
                            },
                            enabled = !testing,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (testing) "Testing…" else "Test Connection") }
                    }
                    if (testResult != null) {
                        Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
                        val r = testResult!!
                        // Inline status banner: semantic container + icon +
                        // latency — never colour (or emoji) alone.
                        Surface(
                            shape = RoundedCornerShape(JarvisDimens.radiusField),
                            color = if (r.ok) JarvisSemanticColors.successContainer
                            else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(JarvisDimens.spaceMd),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (r.ok) Icons.Filled.Check else Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = if (r.ok) JarvisSemanticColors.success
                                    else MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(JarvisDimens.spaceSm))
                                Text(
                                    text = if (r.ok) {
                                        "Connected • ${r.latencyMs} ms — ${r.detail}"
                                    } else {
                                        r.detail
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (r.ok) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onErrorContainer
                                )
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
}

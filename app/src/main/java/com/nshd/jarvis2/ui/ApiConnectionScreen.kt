package com.nshd.jarvis2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
 */
@Composable
fun ApiConnectionScreen(onBack: () -> Unit) {
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
            Text(
                text = "Active provider — tried first",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
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
                                .padding(horizontal = 10.dp, vertical = 2.dp),
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
                            Text(
                                text = if (cfg.apiKey.isNotBlank()) "key set" else "no key",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (cfg.apiKey.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Edit provider",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                ProviderPresets.ALL.forEach { preset ->
                    FilterChip(
                        selected = editId == preset.id,
                        onClick = { editId = preset.id; testResult = null },
                        label = { Text(preset.label) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                        Spacer(modifier = Modifier.height(12.dp))
                        val r = testResult!!
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (r.ok) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                tint = if (r.ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (r.ok) {
                                    "✅ ${r.latencyMs} ms — ${r.detail}"
                                } else {
                                    "❌ ${r.detail}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (r.ok) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Keys stay on this device (encrypted). Failover: if the active " +
                    "provider fails, Jarvis tries the next configured one. Gemini Live " +
                    "voice arrives in Level 3 — this screen powers REST chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.padding(12.dp))
    }
}

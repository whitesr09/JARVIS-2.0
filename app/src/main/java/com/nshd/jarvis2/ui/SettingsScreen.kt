package com.nshd.jarvis2.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val JARVIS_LANGUAGES = listOf(
    "Auto", "English", "Hindi", "Hinglish", "Telugu", "Tamil", "Kannada",
    "Malayalam", "Bengali", "Marathi", "Gujarati", "Bhojpuri"
)

/** The 5 Gemini Live voices (locked set). A custom voice name overrides these. */
val GEMINI_VOICES = listOf("Aoede", "Kore", "Fenrir", "Puck", "Charon")

private data class InfoDialog(val title: String, val body: String)

@Composable
fun SettingsScreen(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenMemory: () -> Unit
) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<InfoDialog?>(null) }
    var showPersona by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var screenPreview by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = "Settings & Intelligence",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ── Operator card ──────────────────────────────────────────────
        GroupCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = settings.callsign,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "OPERATOR • JARVIS PRO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = settings.assistantName,
                onValueChange = { onSettingsChange(settings.copy(assistantName = it)) },
                label = { Text("Assistant name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.callsign,
                onValueChange = { onSettingsChange(settings.copy(callsign = it)) },
                label = { Text("${settings.assistantName} should call you") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionTitle("Voice & Neural Intelligence")
        GroupCard {
            SettingsRow(Icons.Filled.Favorite, "AI Persona & Voice", settings.persona) {
                showPersona = true
            }
            SettingsRow(Icons.Filled.Language, "Conversation Language", settings.language) {
                showLanguage = true
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Hearing, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wake Word", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (settings.wakeAlwaysOn) "Always-on (engine arrives in Level 6)"
                        else "Tap the mic / orb to talk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    onSettingsChange(settings.copy(wakeAlwaysOn = false))
                }) { Text("Tap") }
                TextButton(onClick = {
                    onSettingsChange(settings.copy(wakeAlwaysOn = true))
                }) { Text("Always-on") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.VolumeOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Silent Execution Mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Mute voice narration; a subtle chime still plays when each turn finishes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.silentExecution,
                    onCheckedChange = { onSettingsChange(settings.copy(silentExecution = it)) }
                )
            }
        }

        SectionTitle("Voice")
        GroupCard {
            SettingsRow(
                Icons.Filled.RecordVoiceOver, "Gemini Live Voice",
                settings.customVoiceName.ifBlank { settings.voiceName } +
                    if (settings.customVoiceName.isNotBlank()) " (custom)" else ""
            ) { showVoice = true }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.customVoiceName,
                onValueChange = { onSettingsChange(settings.copy(customVoiceName = it)) },
                label = { Text("Custom voice name (optional)") },
                supportingText = {
                    Text("Overrides the picker above — any voice name the Live model accepts.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.liveModel,
                onValueChange = { onSettingsChange(settings.copy(liveModel = it)) },
                label = { Text("Gemini Live model") },
                supportingText = {
                    Text("Editable — use a live-capable model your key can access.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionTitle("System & Data Engine")
        GroupCard {
            SettingsRow(Icons.Filled.Api, "API & Connection", "Providers, keys & models") {
                onOpenApi()
            }
            SettingsRow(Icons.Filled.Memory, "Neural Memory Bank", "Facts, preferences, contacts") {
                onOpenMemory()
            }
            SettingsRow(Icons.Filled.Security, "System Permissions", "11 authorizations") {
                onOpenPermissions()
            }
            SettingsRow(Icons.Filled.Layers, "Overlay Avatars", "12 companions + Task HUD") {
                info = InfoDialog(
                    "Overlay Avatars",
                    "The floating avatar set and the 4-step Task HUD arrive in Level 5."
                )
            }
        }

        SectionTitle("Digital Hands")
        GroupCard {
            val handsEnabled = JarvisAccessibilityService.isEnabled()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Accessibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Accessibility Service", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (handsEnabled) "Enabled — Jarvis can read and operate the current screen"
                        else "Not enabled — intent and hardware tools still work",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }) { Text("Open") }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Guardrails: payments, unknown-number calls and similar risky actions always ask first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        screenPreview = JarvisAccessibilityService.instance
                            ?.screenReader()
                            ?.serialize()
                            ?: "Accessibility is not enabled. Enable Jarvis Digital Hands first, then try Read screen again."
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Visibility, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Read screen")
                }
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        val target = !torchOn
                        settingsScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DeepIntents(context).toggleTorch(target)
                            }
                            if (result.success) torchOn = target
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.FlashOn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (torchOn) "Torch off" else "Torch")
                }
            }
        }

        SectionTitle("Appearance")
        GroupCard {
            SettingsRow(
                Icons.Filled.Palette, "Theme & Orb Colors",
                settings.theme.name.lowercase().replaceFirstChar { it.uppercase() } +
                    (settings.customBgHex?.let { " • custom $it" } ?: "")
            ) { showTheme = true }
        }

        SectionTitle("Developer")
        GroupCard {
            Text(
                text = "Follow me",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            LinkRow(Icons.Filled.AlternateEmail, "Instagram", "@__nshd.__") {
                openUrl("https://www.instagram.com/__nshd.__")
            }
            LinkRow(Icons.Filled.Chat, "WhatsApp", "+91 8590455801") {
                openUrl("https://wa.me/+918590455801")
            }
            LinkRow(Icons.Filled.Send, "Telegram", "@nshd_0") {
                openUrl("https://t.me/nshd_0")
            }
            LinkRow(Icons.Filled.Email, "Email", "nishadahammed081@gmail.com") {
                openUrl("mailto:nishadahammed081@gmail.com")
            }
            LinkRow(Icons.Filled.Code, "GitHub", "@whitesr09") {
                openUrl("https://github.com/whitesr09")
            }
        }

        SectionTitle("Credits")
        GroupCard {
            Text(
                text = "Developed by N S H D",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Reference design & tutorial: Tech GPTx — " +
                    "github.com/techgptx/Playlist. Credit to Tech GPTx for the JARVIS " +
                    "AI Android source code / tutorial that inspired this design.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "JARVIS • v0.4.0-L4",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // ── Dialogs ────────────────────────────────────────────────────────
    if (info != null) {
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(info?.title ?: "") },
            text = { Text(info?.body ?: "") },
            confirmButton = { TextButton(onClick = { info = null }) { Text("OK") } }
        )
    }

    if (screenPreview != null) {
        AlertDialog(
            onDismissRequest = { screenPreview = null },
            title = { Text("Current screen") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(screenPreview ?: "")
                }
            },
            confirmButton = {
                TextButton(onClick = { screenPreview = null }) { Text("Close") }
            }
        )
    }

    if (showPersona) {
        AlertDialog(
            onDismissRequest = { showPersona = false },
            title = { Text("AI Persona & Voice") },
            text = {
                Column {
                    PersonaOption(
                        name = "Girlfriend Mode",
                        desc = "Jealous, clingy, flirting, attached, loving, sarcastic — mood swings included.",
                        selected = settings.persona == "Girlfriend Mode"
                    ) { onSettingsChange(settings.copy(persona = "Girlfriend Mode")) }
                    PersonaOption(
                        name = "Jarvis Pro",
                        desc = "Witty, strategic, calm under pressure. The default operator mode.",
                        selected = settings.persona == "Jarvis Pro"
                    ) { onSettingsChange(settings.copy(persona = "Jarvis Pro")) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Persona prompts drive both Chat and the Gemini Live " +
                            "voice sessions (Level 3). Pick the voice itself in the " +
                            "Voice section of this screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showPersona = false }) { Text("Done") } }
        )
    }

    if (showLanguage) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text("Conversation Language") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    JARVIS_LANGUAGES.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSettingsChange(settings.copy(language = lang))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.language == lang,
                                onClick = { onSettingsChange(settings.copy(language = lang)) }
                            )
                            Text(lang, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguage = false }) { Text("Done") } }
        )
    }

    if (showVoice) {
        AlertDialog(
            onDismissRequest = { showVoice = false },
            title = { Text("Gemini Live Voice") },
            text = {
                Column {
                    GEMINI_VOICES.forEach { voice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSettingsChange(settings.copy(voiceName = voice))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.voiceName == voice,
                                onClick = { onSettingsChange(settings.copy(voiceName = voice)) }
                            )
                            Text(voice, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "These are Gemini Live voices. If you type a custom " +
                            "voice name in the Voice section, it overrides this picker. " +
                            "Voice changes apply to the next voice session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showVoice = false }) { Text("Done") } }
        )
    }

    if (showTheme) {
        ThemeDialog(
            settings = settings,
            onSettingsChange = onSettingsChange,
            onDismiss = { showTheme = false }
        )
    }
}

@Composable
private fun ThemeDialog(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var hex by remember { mutableStateOf(settings.customBgHex ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme & Orb Colors") },
        text = {
            Column {
                AppTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSettingsChange(settings.copy(theme = theme)) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.theme == theme,
                            onClick = { onSettingsChange(settings.copy(theme = theme)) }
                        )
                        Text(
                            theme.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("Custom background (#RRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row {
                    TextButton(onClick = {
                        if (parseHexColor(hex) != null) {
                            onSettingsChange(settings.copy(customBgHex = hex.trim()))
                        }
                    }) { Text("Apply custom") }
                    TextButton(onClick = {
                        hex = ""
                        onSettingsChange(settings.copy(customBgHex = null))
                    }) { Text("Clear") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun PersonaOption(
    name: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun GroupCard(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onClick)
}

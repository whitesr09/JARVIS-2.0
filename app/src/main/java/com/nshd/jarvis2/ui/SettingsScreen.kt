package com.nshd.jarvis2.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings

val JARVIS_LANGUAGES = listOf(
    "Auto", "English", "Hindi", "Hinglish", "Telugu", "Tamil", "Kannada",
    "Malayalam", "Bengali", "Marathi", "Gujarati", "Bhojpuri"
)

private data class InfoDialog(val title: String, val body: String)

@Composable
fun SettingsScreen(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit,
    onOpenPermissions: () -> Unit
) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<InfoDialog?>(null) }
    var showPersona by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }

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
                        text = "Mute task narration; a chime still plays when a task finishes (Level 3)",
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

        SectionTitle("System & Data Engine")
        GroupCard {
            SettingsRow(Icons.Filled.Api, "API & Connection", "Providers, keys & models") {
                info = InfoDialog(
                    "API & Connection",
                    "Provider setup — Gemini Live + Groq / FreeLLMAPI / OpenRouter with " +
                        "automatic failover, your own API keys and a Test Connection button — " +
                        "arrives in Level 2 with the brain."
                )
            }
            SettingsRow(Icons.Filled.Memory, "Neural Memory Bank", "Facts, preferences, contacts") {
                info = InfoDialog(
                    "Neural Memory Bank",
                    "The Memory Core (auto + manual facts, synced with your contacts) " +
                        "arrives in Level 2."
                )
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
            text = "JARVIS • v0.1.0-L1",
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
                        text = "Persona prompts and the 5 Gemini voices (+ custom voice) are " +
                            "wired into the brain in Level 3. Your selection is saved now.",
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

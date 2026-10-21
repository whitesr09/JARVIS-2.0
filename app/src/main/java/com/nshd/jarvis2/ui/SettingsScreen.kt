package com.nshd.jarvis2.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.R
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.service.A11yStatus
import com.nshd.jarvis2.service.FloatingOverlayService
import com.nshd.jarvis2.service.JarvisAccessibilityService
import com.nshd.jarvis2.service.WakeState
import com.nshd.jarvis2.service.WakeWordControl
import com.nshd.jarvis2.voice.BiometricsRecorder
import com.nshd.jarvis2.voice.VoskModelManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    onOpenMemory: () -> Unit,
    onOpenAvatars: () -> Unit = {}
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
    val screenShare = rememberScreenShareControl()
    val overlayRunningState = remember { mutableStateOf(FloatingOverlayService.isRunning()) }

    // ── Voice biometrics enrollment (Level 6) ──────────────────────────
    var showBiometrics by remember { mutableStateOf(false) }
    var bioRecordingIndex by remember { mutableStateOf<Int?>(null) }
    var bioCount by remember { mutableIntStateOf(BiometricsRecorder.countSamples(context)) }
    var bioError by remember { mutableStateOf<String?>(null) }
    var bioMicGranted by remember { mutableStateOf(hasMicPermission(context)) }
    val bioMicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bioMicGranted = granted
        if (granted) showBiometrics = true
    }

    fun recordBioSample(index: Int) {
        settingsScope.launch {
            bioRecordingIndex = index
            bioError = null
            val ok = BiometricsRecorder.recordSample(context, index)
            bioRecordingIndex = null
            bioCount = BiometricsRecorder.countSamples(context)
            if (!ok) {
                bioError = "Recording failed — check the microphone and try again."
            } else if (bioCount >= BiometricsRecorder.TOTAL_SAMPLES) {
                val date = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date())
                onSettingsChange(
                    settings.copy(
                        biometricsEnrolled = true,
                        biometricsSampleCount = bioCount,
                        biometricsEnrolledDate = date
                    )
                )
            } else {
                onSettingsChange(settings.copy(biometricsSampleCount = bioCount))
            }
        }
    }

    fun clearBioSamples() {
        BiometricsRecorder.deleteSamples(context)
        bioCount = 0
        bioError = null
        onSettingsChange(
            settings.copy(
                biometricsEnrolled = false,
                biometricsSampleCount = 0,
                biometricsEnrolledDate = ""
            )
        )
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(JarvisDimens.screenPadding)
    ) {
        Text(
            text = "Settings & Intelligence",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

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
            SettingsRow(
                Icons.Filled.Favorite, "AI Persona & Voice", settings.persona,
                showDivider = true
            ) {
                showPersona = true
            }
            SettingsRow(Icons.Filled.Language, "Conversation Language", settings.language) {
                showLanguage = true
            }
            WakeWordControls(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onOpenPermissions = onOpenPermissions
            )
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
                    onCheckedChange = { onSettingsChange(settings.copy(silentExecution = it)) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline
                    )
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

        SectionTitle("Voice Biometrics")
        GroupCard {
            SettingsRow(
                Icons.Filled.Fingerprint, "Voice Biometrics",
                when {
                    settings.biometricsEnrolled ->
                        "Enrolled — ${settings.biometricsSampleCount} samples" +
                            if (settings.biometricsEnrolledDate.isNotBlank()) {
                                " • ${settings.biometricsEnrolledDate}"
                            } else {
                                ""
                            }
                    bioCount > 0 -> "$bioCount of ${BiometricsRecorder.TOTAL_SAMPLES} samples recorded"
                    else -> "Not enrolled — record 3 samples of your wake phrase"
                }
            ) {
                if (bioMicGranted) {
                    showBiometrics = true
                } else {
                    bioMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Samples are stored only on this device. Voice verification " +
                    "matching arrives in a later update — enrollment only for now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionTitle("System & Data Engine")
        GroupCard {
            SettingsRow(
                Icons.Filled.Api, "API & Connection", "Providers, keys & models",
                showDivider = true
            ) {
                onOpenApi()
            }
            SettingsRow(
                Icons.Filled.Memory, "Neural Memory Bank", "Facts, preferences, contacts",
                showDivider = true
            ) {
                onOpenMemory()
            }
            SettingsRow(
                Icons.Filled.Security, "System Permissions", "11 authorizations",
                showDivider = true
            ) {
                onOpenPermissions()
            }
            SettingsRow(
                Icons.Filled.Layers, "Floating Avatar",
                AvatarCatalog.byId(settings.avatarId).label + " • 12 companions + Task HUD"
            ) {
                onOpenAvatars()
            }
        }

        SectionTitle("Floating Avatar & Vision")
        GroupCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(AvatarCatalog.byId(settings.avatarId).resId),
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = AvatarCatalog.byId(settings.avatarId).label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (overlayRunningState.value) {
                            "Floating over other apps • double-tap it for the Task HUD"
                        } else {
                            "Not floating right now"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenAvatars) { Text("Change") }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row {
                Button(
                    onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            onOpenPermissions()
                        } else {
                            FloatingOverlayService.start(context)
                            overlayRunningState.value = true
                        }
                    },
                    enabled = !overlayRunningState.value,
                    modifier = Modifier.weight(1f)
                ) { Text("Start floating") }
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        FloatingOverlayService.stop(context)
                        overlayRunningState.value = false
                    },
                    enabled = overlayRunningState.value,
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Screen Share (Vision)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (screenShare.active) {
                            "Sharing ~1 frame/sec — the voice session and vision tools can see the screen"
                        } else {
                            "Off — start it and Jarvis can watch the screen for vision targeting"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { screenShare.toggle() }) {
                    Text(if (screenShare.active) "Stop" else "Start")
                }
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

        SectionTitle("Keep Accessibility Alive (Vivo/iQOO)")
        GroupCard {
            val a11yOn = A11yStatus.isEnabled(context)
            val batteryFree = A11yStatus.isIgnoringBatteryOptimizations(context)
            Text(
                text = "Status: Accessibility ${if (a11yOn) "ON" else "OFF"} • " +
                    "Battery ${if (batteryFree) "unrestricted" else "optimized"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (a11yOn && batteryFree) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Vivo/iQOO phones silently switch off accessibility for " +
                    "sideloaded apps. These three steps keep Jarvis alive — " +
                    "do them once, and re-check them if accessibility turns off again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            KeepAliveStep(
                step = "1",
                title = "Allow restricted settings",
                caption = "On the App info page that opens, tap ⋮ (top right) → " +
                    "\"Allow restricted settings\", then enable Jarvis under " +
                    "Accessibility. Android 13+ blocks accessibility for " +
                    "sideloaded apps until this is allowed.",
                buttonLabel = "Open App info"
            ) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
            KeepAliveStep(
                step = "2",
                title = "Autostart ON",
                caption = "In iManager → App manager → Autostart, turn Jarvis ON " +
                    "so the phone doesn't kill it in the background.",
                buttonLabel = "Open Autostart"
            ) { openAutostartSettings(context) }
            KeepAliveStep(
                step = "3",
                title = "Battery: Unrestricted",
                caption = if (batteryFree) {
                    "Done — Jarvis already runs unrestricted. ✓"
                } else {
                    "Choose \"Unrestricted\" on the page that opens so the " +
                        "system doesn't freeze Jarvis to save battery."
                },
                buttonLabel = "Open Battery settings"
            ) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
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
                text = "Developed by",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "N S H D",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Icon links ONLY — no addresses, numbers or URLs displayed.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeveloperBrandButton(R.drawable.brand_instagram, "Instagram") {
                    openUrl("https://www.instagram.com/__nshd.__")
                }
                DeveloperBrandButton(R.drawable.brand_whatsapp, "WhatsApp") {
                    openUrl("https://wa.me/+918590455801")
                }
                DeveloperBrandButton(R.drawable.brand_telegram, "Telegram") {
                    openUrl("https://t.me/nshd_0")
                }
                DeveloperBrandButton(R.drawable.brand_github, "GitHub") {
                    openUrl("https://github.com/whitesr09")
                }
                DeveloperIconButton(Icons.Filled.Email, "Email") {
                    openUrl("mailto:nishadahammed081@gmail.com")
                }
                DeveloperIconButton(Icons.Filled.Call, "Call") {
                    openUrl("tel:+918590455801")
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "JARVIS • v1.0.0",
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

    if (showBiometrics) {
        val recording = bioRecordingIndex != null
        AlertDialog(
            onDismissRequest = { if (!recording) showBiometrics = false },
            title = { Text("Voice Biometrics") },
            text = {
                Column {
                    Text(
                        text = if (settings.biometricsEnrolled) {
                            "Enrolled — ${settings.biometricsSampleCount} samples" +
                                if (settings.biometricsEnrolledDate.isNotBlank()) {
                                    " • ${settings.biometricsEnrolledDate}"
                                } else {
                                    ""
                                }
                        } else {
                            "$bioCount of ${BiometricsRecorder.TOTAL_SAMPLES} samples recorded"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Samples are stored only on this device. Voice verification " +
                            "matching arrives in a later update — enrollment only for now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (recording) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Recording sample ${bioRecordingIndex} of " +
                                    "${BiometricsRecorder.TOTAL_SAMPLES} — say: " +
                                    "\"${BiometricsRecorder.WAKE_PHRASE}\"",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (!settings.biometricsEnrolled) {
                        Text(
                            text = "When you tap record, say the wake phrase clearly: " +
                                "\"${BiometricsRecorder.WAKE_PHRASE}\" " +
                                "(${BiometricsRecorder.SAMPLE_SECONDS} seconds per sample).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                recordBioSample((bioCount + 1).coerceAtMost(BiometricsRecorder.TOTAL_SAMPLES))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Record sample ${(bioCount + 1).coerceAtMost(BiometricsRecorder.TOTAL_SAMPLES)} " +
                                    "of ${BiometricsRecorder.TOTAL_SAMPLES}"
                            )
                        }
                    }
                    bioError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (bioCount > 0 && !recording) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row {
                            TextButton(onClick = { clearBioSamples() }) { Text("Re-enroll") }
                            TextButton(onClick = { clearBioSamples() }) { Text("Delete samples") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showBiometrics = false },
                    enabled = !recording
                ) { Text("Done") }
            }
        )
    }
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun isBatteryWhitelisted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Wake Word card content (Level 6): Tap vs Always-on mode toggle, and —
 * in Always-on mode — the live requirements checklist (offline model
 * download with progress, microphone permission, battery whitelist),
 * Start/Stop listening and the service's live status.
 */
@Composable
private fun WakeWordControls(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit,
    onOpenPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wakeStatus by WakeState.status.collectAsState()
    val downloadState by VoskModelManager.downloadState.collectAsState()
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val modelReady = VoskModelManager.status(context) ==
        VoskModelManager.ModelStatus.READY ||
        downloadState is VoskModelManager.DownloadState.Done
    val batteryWhitelisted = isBatteryWhitelisted(context)

    fun startListening() {
        when {
            !modelReady -> Unit // checklist below shows what's missing
            !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> runCatching { WakeWordControl.start(context) }
        }
    }

    Column {
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
                    text = when {
                        !settings.wakeAlwaysOn -> "Tap the mic / orb to talk"
                        wakeStatus.listening -> "Always-on • Listening for \"Hey Jarvis\""
                        wakeStatus.enabled -> "Always-on • ${wakeStatus.detail}"
                        else -> "Always-on • Not running yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                onSettingsChange(settings.copy(wakeAlwaysOn = false))
                WakeWordControl.stop(context)
            }) { Text("Tap") }
            TextButton(onClick = {
                onSettingsChange(settings.copy(wakeAlwaysOn = true))
                if (modelReady && micGranted) {
                    runCatching { WakeWordControl.start(context) }
                }
            }) { Text("Always-on") }
        }

        if (settings.wakeAlwaysOn) {
            // ── Offline model ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (modelReady) "✓" else "○",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (modelReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Offline model (English, ~41 MB)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when (val state = downloadState) {
                            is VoskModelManager.DownloadState.Downloading ->
                                "Downloading… ${state.percent}%"
                            is VoskModelManager.DownloadState.Extracting -> "Extracting…"
                            is VoskModelManager.DownloadState.Error ->
                                "Download failed: ${state.message}"
                            else -> if (modelReady) "Ready on this device" else "Needed for offline listening"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (downloadState is VoskModelManager.DownloadState.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (!modelReady) {
                    val busy = downloadState is VoskModelManager.DownloadState.Downloading ||
                        downloadState is VoskModelManager.DownloadState.Extracting
                    TextButton(
                        onClick = { scope.launch { VoskModelManager.downloadModel(context) } },
                        enabled = !busy
                    ) { Text(if (downloadState is VoskModelManager.DownloadState.Error) "Retry" else "Download") }
                }
            }
            if (downloadState is VoskModelManager.DownloadState.Downloading) {
                LinearProgressIndicator(
                    progress = {
                        (downloadState as VoskModelManager.DownloadState.Downloading).percent / 100f
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }

            // ── Microphone permission ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (micGranted) "✓" else "○",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (micGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Microphone permission",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (!micGranted) {
                    TextButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text("Grant")
                    }
                }
            }

            // ── Battery whitelist ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (batteryWhitelisted) "✓" else "○",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (batteryWhitelisted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Battery whitelist", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Lets the listener survive in the background (Vivo/iQOO)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenPermissions) { Text("Open") }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { startListening() },
                    enabled = modelReady && micGranted && !wakeStatus.enabled,
                    modifier = Modifier.weight(1f)
                ) { Text("Start listening") }
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { WakeWordControl.stop(context) },
                    enabled = wakeStatus.enabled,
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when {
                    !modelReady -> "Download the offline model to enable always-on listening."
                    !micGranted -> "Grant the microphone permission to enable always-on listening."
                    else -> "Status: ${wakeStatus.detail}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
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
                            theme.name.lowercase().replaceFirstChar { it.uppercase() } +
                                if (theme == AppTheme.PURPLE) " (default)" else "",
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
    // D2 section style: letterspaced label (see SectionLabel), one
    // rhythm of space above / small gap below for every group.
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.9.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            top = JarvisDimens.sectionGap,
            bottom = JarvisDimens.spaceSm
        )
    )
}

@Composable
private fun GroupCard(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(JarvisDimens.cardPadding), content = content)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    showDivider: Boolean = false,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = JarvisDimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(JarvisDimens.spaceLg))
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
        if (showDivider) {
            // Hairline aligned with the row text (icon 24 + gap 16).
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(start = 40.dp)
            )
        }
    }
}

/** One numbered step of the Vivo/iQOO accessibility keep-alive guide. */
@Composable
private fun KeepAliveStep(
    step: String,
    title: String,
    caption: String,
    buttonLabel: String,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(onClick = onOpen) { Text(buttonLabel) }
        }
    }
}

/** Circular tonal button with a brand vector drawable (Developer section). */
@Composable
private fun DeveloperBrandButton(
    drawableRes: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(drawableRes),
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** Circular tonal button with a Material icon (Developer section). */
@Composable
private fun DeveloperIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Opens the OEM autostart manager. Vivo/iQOO ship it inside their
 * permission manager; other builds fall back to this app's App info
 * page, from where autostart can usually be reached.
 */
private fun openAutostartSettings(context: Context) {
    val vivoIntent = Intent().setComponent(
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
    )
    if (runCatching { context.startActivity(vivoIntent) }.isSuccess) return
    val iqooIntent = Intent().setComponent(
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        )
    )
    if (runCatching { context.startActivity(iqooIntent) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

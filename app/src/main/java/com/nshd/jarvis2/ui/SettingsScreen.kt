package com.nshd.jarvis2.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.service.FloatingOverlayService
import com.nshd.jarvis2.service.WakeState
import com.nshd.jarvis2.service.WakeWordControl
import com.nshd.jarvis2.voice.BiometricsRecorder
import com.nshd.jarvis2.voice.VoskModelManager
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backwards-compatible alias — the canonical language list now lives in
 * LanguagePickerDialog.kt ([LANGUAGES]); Home uses that dialog too.
 */
val JARVIS_LANGUAGES: List<String> = LANGUAGES

/** The 5 Gemini Live voices (locked set). A custom voice name overrides these. */
val GEMINI_VOICES = listOf("Aoede", "Kore", "Fenrir", "Puck", "Charon")

private data class InfoDialog(val title: String, val body: String)

/** Real package/signing data for the App Identity & Integrity dialog. */
private data class IdentityInfo(
    val appLabel: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val certSha256Short: String
)

@Composable
fun SettingsScreen(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAvatars: () -> Unit = {},
    onOpenHandsFree: () -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    // ── All dialog / expansion state is hoisted to this top level (F4):
    // nothing is remembered inside a conditional branch, so expanding a
    // section can never reshuffle the slot table mid-composition.
    var info by remember { mutableStateOf<InfoDialog?>(null) }
    var showPersona by remember { mutableStateOf(false) }
    // v1.9.0 (#77): custom-persona builder test state (hoisted, F4 rule).
    var personaTestReply by remember { mutableStateOf<String?>(null) }
    var personaTesting by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showVoiceEngine by remember { mutableStateOf(false) }
    // Plan v1.8.0 dialogs: barge-in (#4), TTS voice picker (#7),
    // chat text size (#93), backup/restore (#101), transparency (#79).
    var showBargeIn by remember { mutableStateOf(false) }
    var showTtsVoices by remember { mutableStateOf(false) }
    var showChatFont by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showKnows by remember { mutableStateOf(false) }
    var restoreJsonText by remember { mutableStateOf("") }
    var ttsVoiceList by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var knowsText by remember { mutableStateOf("") }
    var showTheme by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showNameFields by remember { mutableStateOf(false) }
    var showEnroll by remember { mutableStateOf(false) }
    var wakeExpanded by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showIdentity by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showImportMemory by remember { mutableStateOf(false) }
    var confirmClearChat by remember { mutableStateOf(false) }
    var confirmClearMemories by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var privacyFactCount by remember { mutableIntStateOf(0) }
    var privacyChatCount by remember { mutableIntStateOf(0) }
    val settingsScope = rememberCoroutineScope()
    val screenShare = rememberScreenShareControl()
    val overlayRunningState = remember { mutableStateOf(FloatingOverlayService.isRunning()) }
    val wakeUiStatus by WakeState.status.collectAsState()
    val providerStore = remember { ProviderStore(context) }
    val activeProvider = remember {
        providerStore.loadConfig(providerStore.activeProviderId())
    }
    val memoryDb = remember { MemoryDb(context) }
    val identityInfo = remember { loadIdentityInfo(context) }

    // ── TTS voice preview (F5.1) ───────────────────────────────────────
    // Standalone throwaway engine for the Voice section's "Test voice"
    // button — created lazily on first tap, shut down when this screen
    // leaves composition. Hoisted here per the F4 fixed-call-site rule.
    var ttsPreview by remember { mutableStateOf<TextToSpeech?>(null) }
    val latestSettings by rememberUpdatedState(settings)
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ttsPreview?.stop() }
            runCatching { ttsPreview?.shutdown() }
        }
    }

    /** Speaks the preview line with the CURRENT speed/pitch sliders. */
    fun speakTtsPreview() {
        runCatching {
            val s = latestSettings
            val text = "Hi ${s.callsign}, this is how I sound now."
            val existing = ttsPreview
            if (existing != null) {
                existing.setSpeechRate(s.ttsRate)
                existing.setPitch(s.ttsPitch)
                existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts-preview")
            } else {
                ttsPreview = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        runCatching {
                            ttsPreview?.let { engine ->
                                engine.setSpeechRate(s.ttsRate)
                                engine.setPitch(s.ttsPitch)
                                engine.speak(
                                    text, TextToSpeech.QUEUE_FLUSH, null, "tts-preview"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Plan #7: installed device-TTS voices for the picker ────────────
    fun loadTtsVoices() {
        runCatching {
            val engine = ttsPreview ?: TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        ttsVoiceList = (ttsPreview?.voices ?: emptySet())
                            .sortedBy { it.name }
                    }
                }
            }.also { ttsPreview = it }
            val ready = runCatching { engine.voices }.getOrNull()
            if (!ready.isNullOrEmpty()) {
                ttsVoiceList = ready.sortedBy { it.name }
            }
        }
    }

    /** Plan #7: preview one named voice (or the automatic pick). */
    fun previewTtsVoice(voiceName: String) {
        runCatching {
            val s = latestSettings
            val text = "Hi ${s.callsign}, this is how I sound now."
            fun speakWith(engine: TextToSpeech) {
                runCatching {
                    if (voiceName.isNotBlank()) {
                        engine.voices?.firstOrNull { it.name == voiceName }
                            ?.let { engine.voice = it }
                    }
                    engine.setSpeechRate(s.ttsRate)
                    engine.setPitch(s.ttsPitch)
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts-voice-preview")
                }
            }
            val existing = ttsPreview
            if (existing != null) {
                speakWith(existing)
            } else {
                ttsPreview = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsPreview?.let { speakWith(it) }
                    }
                }
            }
        }
    }

    // ── Plan #101: full backup / restore ───────────────────────────────
    fun exportBackup() {
        settingsScope.launch {
            val json = withContext(Dispatchers.IO) {
                com.nshd.jarvis2.core.BackupManager.exportJson(context)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "Jarvis full backup")
                putExtra(Intent.EXTRA_TEXT, json)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "Export Jarvis backup"))
            }
        }
    }

    fun importBackup() {
        settingsScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching {
                    com.nshd.jarvis2.core.BackupManager.importJson(context, restoreJsonText)
                }
            }
            showBackup = false
            summary.onSuccess { s ->
                restoreJsonText = ""
                info = InfoDialog(
                    "Backup restored",
                    "Applied ${s.settingsApplied} settings and added " +
                        "${s.factsAdded} memories (${s.factsSkipped} duplicates " +
                        "skipped). ${s.providersListed} provider setups were in " +
                        "the file — provider KEYS are never exported, so paste " +
                        "them again under API & Model if this is a new phone. " +
                        "Restart Jarvis to apply the restored settings."
                )
            }.onFailure { e ->
                info = InfoDialog(
                    "Restore failed",
                    e.message ?: "That file could not be read as a Jarvis backup."
                )
            }
        }
    }

    // ── Plan #79: transparency — the exact prompt + active memories ───
    fun loadKnowsText() {
        settingsScope.launch {
            knowsText = withContext(Dispatchers.IO) {
                runCatching {
                    val facts = memoryDb.allFacts(null, null)
                    buildString {
                        appendLine("SYSTEM PROMPT (exactly what the model receives)")
                        appendLine("──────────────────────────────────────────")
                        append(com.nshd.jarvis2.brain.PersonaPrompts.build(latestSettings, facts, codeMode = false))
                        appendLine()
                        appendLine()
                        appendLine("ACTIVE MEMORIES (${facts.size})")
                        appendLine("──────────────────────────────────────────")
                        if (facts.isEmpty()) {
                            appendLine("(none yet)")
                        } else {
                            facts.forEach { appendLine("• [${it.category}] ${it.text}") }
                        }
                    }
                }.getOrDefault("Could not assemble the transparency view.")
            }
        }
    }

    // Privacy dialog storage usage — refreshed every time it opens.
    LaunchedEffect(showPrivacy) {
        if (showPrivacy) {
            val counts = withContext(Dispatchers.IO) {
                memoryDb.factCount() to memoryDb.lastMessages(MAX_CHAT_PROBE).size
            }
            privacyFactCount = counts.first
            privacyChatCount = counts.second
        }
    }

    // ── Voice biometrics enrollment (Level 6) ──────────────────────────
    var bioRecordingIndex by remember { mutableStateOf<Int?>(null) }
    var bioCount by remember { mutableIntStateOf(BiometricsRecorder.countSamples(context)) }
    var bioError by remember { mutableStateOf<String?>(null) }
    var bioMicGranted by remember { mutableStateOf(hasMicPermission(context)) }
    val bioMicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bioMicGranted = granted
        if (granted) showEnroll = true
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

    /** Enroll entry points (profile chip, SETUP pill): mic first, then expand. */
    fun requestEnroll() {
        if (bioMicGranted) {
            showEnroll = true
        } else {
            bioMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ── Privacy & Data Shield actions (wired to MemoryDb, F4) ──────────
    fun refreshPrivacyCounts() {
        settingsScope.launch {
            val counts = withContext(Dispatchers.IO) {
                memoryDb.factCount() to memoryDb.lastMessages(MAX_CHAT_PROBE).size
            }
            privacyFactCount = counts.first
            privacyChatCount = counts.second
        }
    }

    fun exportMemories() {
        settingsScope.launch {
            val json = withContext(Dispatchers.IO) {
                val arr = JSONArray()
                memoryDb.allFacts(null, null).forEach { f ->
                    arr.put(
                        JSONObject()
                            .put("category", f.category)
                            .put("text", f.text)
                    )
                }
                arr.toString(2)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Jarvis Memory backup")
                putExtra(Intent.EXTRA_TEXT, json)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(intent, "Export Jarvis memories")
                )
            }
        }
    }

    fun importMemories() {
        settingsScope.launch {
            val inserted = withContext(Dispatchers.IO) {
                runCatching {
                    val arr = JSONArray(importJsonText)
                    var n = 0
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val text = obj.optString("text").trim()
                        if (text.isNotEmpty()) {
                            val cat = obj.optString("category")
                                .takeIf { it in MemoryCategories.ALL }
                                ?: MemoryCategories.GENERAL
                            memoryDb.addFact(cat, text)
                            n++
                        }
                    }
                    n
                }.getOrElse { -1 }
            }
            showImportMemory = false
            if (inserted >= 0) {
                importJsonText = ""
                refreshPrivacyCounts()
                info = InfoDialog("Import complete", "Imported $inserted memories.")
            } else {
                info = InfoDialog(
                    "Import failed",
                    "That wasn't a valid memory JSON array."
                )
            }
        }
    }

    fun clearChatHistory() {
        settingsScope.launch {
            withContext(Dispatchers.IO) { memoryDb.clearChat() }
            confirmClearChat = false
            privacyChatCount = 0
        }
    }

    fun clearAllMemories() {
        settingsScope.launch {
            withContext(Dispatchers.IO) {
                // MemoryDb has no bulk-delete; clear via its own DAO.
                memoryDb.allFacts(null, null).forEach { memoryDb.deleteFact(it.id) }
            }
            confirmClearMemories = false
            privacyFactCount = 0
        }
    }

    val biometricsSubtitle = when {
        settings.biometricsEnrolled ->
            "Enrolled • ${settings.biometricsSampleCount} samples" +
                if (settings.biometricsEnrolledDate.isNotBlank()) {
                    " • ${settings.biometricsEnrolledDate}"
                } else {
                    ""
                }
        bioCount > 0 -> "$bioCount of ${BiometricsRecorder.TOTAL_SAMPLES} samples recorded"
        else -> "Not enrolled • Tap to record voice profile"
    }

    val wakeSubtitle = when {
        !settings.wakeAlwaysOn -> "Tap the mic / orb to talk"
        wakeUiStatus.listening -> "Always-on • Listening for \"Hey Jarvis\""
        wakeUiStatus.enabled -> "Always-on • Running"
        else -> "Always-on • Not running yet"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // F5.1: keep the focused inline field (names, custom
            // voice, live model) reachable above the keyboard.
            .imePadding()
            .padding(JarvisDimens.screenPadding)
    ) {
        // ── Header ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Settings & Intelligence",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "System preferences & neural configuration",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        // ── Accessibility warning (Fix → Permissions) ──────────────────
        A11yWarningBanner(onFix = onOpenPermissions)
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        // ── Profile card ───────────────────────────────────────────────
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(10.dp)
                            .background(
                                color = Color(0xFF22C55E),
                                shape = CircleShape
                            )
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.surface,
                                shape = CircleShape
                            )
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = settings.callsign,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "JARVIS OPERATOR • PRIMARY USER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showNameFields = !showNameFields }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit name and callsign",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileChip(icon = Icons.Filled.Mic, label = "Enroll Voice") {
                    requestEnroll()
                }
                ProfileChip(
                    icon = Icons.Filled.EmojiEmotions,
                    label = settings.persona
                ) {
                    showPersona = true
                }
            }
            if (showNameFields) {
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
        }

        // ── Voice biometrics enroll block (expanded from the profile
        // card / Voice group row — the pre-F4 enroll dialog's content,
        // moved inline; same recorder state and actions).
        if (showEnroll) {
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(icon = Icons.Filled.Fingerprint)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Voice Biometrics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = biometricsSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Samples are stored only on this device. Voice verification " +
                        "matching arrives in a later update — enrollment only for now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                val recording = bioRecordingIndex != null
                if (recording) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Recording sample $bioRecordingIndex of " +
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
                            recordBioSample(
                                (bioCount + 1).coerceAtMost(BiometricsRecorder.TOTAL_SAMPLES)
                            )
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
        }

        // ── Voice & Neural Intelligence ────────────────────────────────
        SectionLabel(
            text = "VOICE & NEURAL INTELLIGENCE",
            modifier = Modifier.padding(
                top = JarvisDimens.sectionGap,
                bottom = JarvisDimens.spaceSm
            )
        )
        GlassCard {
            GlassRow(
                icon = Icons.Filled.Mic,
                title = "Owner Voice Biometrics",
                subtitle = biometricsSubtitle,
                showDivider = true,
                onClick = { showEnroll = !showEnroll },
                trailing = if (!settings.biometricsEnrolled) {
                    {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.clickable { requestEnroll() }
                        ) {
                            Text(
                                text = "SETUP",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 6.dp
                                )
                            )
                        }
                    }
                } else {
                    null
                }
            )
            GlassRow(
                icon = Icons.Filled.Translate,
                title = "Conversation Language",
                subtitle = settings.language,
                showDivider = true,
                onClick = { showLanguage = true }
            )
            GlassRow(
                icon = Icons.Filled.Face,
                title = "AI Persona & Voice",
                subtitle = "${settings.persona} • Voice: " +
                    settings.customVoiceName.ifBlank { settings.voiceName },
                showDivider = true,
                onClick = { showPersona = true }
            )
            GlassRow(
                icon = Icons.Filled.GraphicEq,
                title = "Voice Engine",
                subtitle = when (settings.voiceEngine) {
                    "NATIVE" -> "Native Live"
                    "UNIVERSAL" -> "Universal"
                    else -> "Auto"
                },
                showDivider = true,
                onClick = { showVoiceEngine = true }
            )
            GlassRow(
                icon = Icons.Filled.Hearing,
                title = "Wake Word",
                subtitle = wakeSubtitle,
                showDivider = false,
                onClick = { wakeExpanded = !wakeExpanded }
            )
            // Always composed (its remembers/launchers stay at a fixed
            // call site); it renders nothing while collapsed.
            WakeWordControls(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onOpenPermissions = onOpenPermissions,
                expanded = wakeExpanded
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 58.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconSquare(icon = Icons.Filled.VolumeOff)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Silent Execution Mode",
                        style = MaterialTheme.typography.bodyLarge
                    )
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
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 58.dp)
            )
            // v1.9.0 (#87): DND respect toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconSquare(icon = Icons.Filled.VolumeOff)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Respect Do-Not-Disturb",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "While your phone is on DND, voice sessions stay silent (chime only)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.dndRespect,
                    onCheckedChange = { onSettingsChange(settings.copy(dndRespect = it)) },
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
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 58.dp)
            )
            // v1.9.0 (#14): push-to-talk toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconSquare(icon = Icons.Filled.Mic)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Hold-to-talk (push-to-talk)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "On the Voice screen, hold the orb to talk and release to send — best for noisy places",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.pttMode,
                    onCheckedChange = { onSettingsChange(settings.copy(pttMode = it)) },
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
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 58.dp)
            )
            GlassRow(
                icon = Icons.Filled.RecordVoiceOver,
                title = "Gemini Live Voice",
                subtitle = settings.customVoiceName.ifBlank { settings.voiceName } +
                    if (settings.customVoiceName.isNotBlank()) " (custom)" else "",
                onClick = { showVoice = true }
            )
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
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Universal voice — device TTS",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Speed and pitch for the Universal engine's spoken " +
                    "replies (Gemini Live uses its own voices). Changes " +
                    "apply from the next reply — no session restart needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TTS speed",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.US, "%.2f×", settings.ttsRate),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = settings.ttsRate,
                onValueChange = { onSettingsChange(settings.copy(ttsRate = it)) },
                valueRange = 0.80f..1.50f,
                steps = 34,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TTS pitch (girly ↔ deep)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.US, "%.2f×", settings.ttsPitch),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = settings.ttsPitch,
                onValueChange = { onSettingsChange(settings.copy(ttsPitch = it)) },
                valueRange = 0.80f..1.60f,
                steps = 39,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { speakTtsPreview() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Test voice")
            }
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Plan #7: pick the exact installed device voice.
            GlassRow(
                icon = Icons.Filled.RecordVoiceOver,
                title = "Device voice (Universal)",
                subtitle = settings.ttsVoiceName
                    .ifBlank { "Automatic — best installed voice" },
                showDivider = true,
                onClick = { loadTtsVoices(); showTtsVoices = true }
            )
            // Plan #4: how eagerly Jarvis may be interrupted.
            GlassRow(
                icon = Icons.Filled.Mic,
                title = "Interrupt sensitivity",
                subtitle = when (settings.bargeInSensitivity) {
                    "LOW" -> "Low — hard to interrupt"
                    "HIGH" -> "High — interrupt on any word"
                    else -> "Normal"
                },
                onClick = { showBargeIn = true }
            )
        }

        // ── System & Data Engine ───────────────────────────────────────
        SectionLabel(
            text = "SYSTEM & DATA ENGINE",
            modifier = Modifier.padding(
                top = JarvisDimens.sectionGap,
                bottom = JarvisDimens.spaceSm
            )
        )
        GlassCard {
            GlassRow(
                icon = Icons.Filled.Cable,
                title = "API & Model",
                subtitle = "${activeProvider.label} • " +
                    activeProvider.chatModel.ifBlank { "No chat model set" },
                showDivider = true,
                onClick = { onOpenApi() }
            )
            GlassRow(
                icon = Icons.Filled.Storage,
                title = "Neural Memory Bank",
                subtitle = "Search, review and back up learned facts",
                showDivider = true,
                onClick = { onOpenMemory() }
            )
            // Plan #79: transparency — the exact prompt + memories.
            GlassRow(
                icon = Icons.Filled.Visibility,
                title = "What does Jarvis know?",
                subtitle = "The exact system prompt and every active memory",
                showDivider = true,
                onClick = { loadKnowsText(); showKnows = true }
            )
            // Plan #101: one-file backup / restore.
            GlassRow(
                icon = Icons.Filled.Storage,
                title = "Backup & Restore",
                subtitle = "Settings + memories + provider setups in one file",
                showDivider = true,
                onClick = { showBackup = true }
            )
            GlassRow(
                icon = Icons.Filled.VerifiedUser,
                title = "System Permissions",
                subtitle = "Microphone, Accessibility, Notifications & System",
                showDivider = true,
                onClick = { onOpenPermissions() }
            )
            GlassRow(
                icon = Icons.Filled.Palette,
                title = "Overlay Avatars",
                subtitle = "Choose a floating avatar or turn the overlay off",
                showDivider = true,
                onClick = { onOpenAvatars() }
            )
            GlassRow(
                icon = Icons.Filled.Apps,
                title = "App Control & Hands-Free",
                subtitle = "WhatsApp, YouTube, Media & Phone Automation",
                showDivider = true,
                onClick = { onOpenHandsFree() }
            )
            GlassRow(
                icon = Icons.Filled.ReceiptLong,
                title = "App Logs & Diagnostics",
                subtitle = "Session logs, errors & crashes — view or export",
                onClick = { onOpenLogs() }
            )
        }

        // ── Floating Avatar & Vision (preserved from pre-F4) ───────────
        SectionLabel(
            text = "FLOATING AVATAR & VISION",
            modifier = Modifier.padding(
                top = JarvisDimens.sectionGap,
                bottom = JarvisDimens.spaceSm
            )
        )
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(AvatarCatalog.byId(settings.avatarId).resId),
                    contentDescription = null,
                    tint = Color.Unspecified,
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
                IconSquare(icon = Icons.Filled.Visibility)
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

        // ── Appearance & Security ──────────────────────────────────────
        SectionLabel(
            text = "APPEARANCE & SECURITY",
            modifier = Modifier.padding(
                top = JarvisDimens.sectionGap,
                bottom = JarvisDimens.spaceSm
            )
        )
        GlassCard {
            GlassRow(
                icon = Icons.Filled.Palette,
                title = "Theme & Orb Colors",
                subtitle = settings.theme.name.lowercase()
                    .replaceFirstChar { it.uppercase() } +
                    if (settings.theme == AppTheme.CUSTOM) {
                        settings.customBgHex?.let { " • custom $it" } ?: ""
                    } else {
                        ""
                    },
                showDivider = true,
                onClick = { showTheme = true }
            )
            // Plan #93: chat message text size.
            GlassRow(
                icon = Icons.Filled.Edit,
                title = "Chat text size",
                subtitle = when (settings.chatFontSize) {
                    "S" -> "Small"
                    "L" -> "Large"
                    else -> "Medium"
                },
                showDivider = true,
                onClick = { showChatFont = true }
            )
            // Plan #94: reduce motion switch.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconSquare(icon = Icons.Filled.Visibility)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Reduce motion",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Calms the orb and panel animations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.reduceMotion,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(reduceMotion = it))
                    },
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
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 58.dp)
            )
            // Plan #58: panel haptic tick switch.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconSquare(icon = Icons.Filled.Fingerprint)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Panel step haptics",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "A subtle tick in the floating panel as each plan step completes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.panelHaptics,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(panelHaptics = it))
                    },
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
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 58.dp)
            )
            GlassRow(
                icon = Icons.Filled.Lock,
                title = "Privacy & Data Shield",
                subtitle = "On-device processing & data controls",
                showDivider = true,
                onClick = { showPrivacy = true }
            )
            GlassRow(
                icon = Icons.Filled.VerifiedUser,
                title = "App Identity & Integrity",
                subtitle = "Publisher, app ID and signing certificate status",
                onClick = { showIdentity = true }
            )
        }

        // ── v2.0.0: Brain Upgrade (learned commands & routines) ──────
        Spacer(modifier = Modifier.height(JarvisDimens.sectionGap))
        BrainUpgradeSection(
            settings = settings,
            onSettingsChange = onSettingsChange
        )

        // ── Developer footer ───────────────────────────────────────────
        Spacer(modifier = Modifier.height(JarvisDimens.sectionGap))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon links ONLY — no addresses, numbers or URLs displayed.
            // SpaceBetween across the full width at 44dp (F2): six 48dp
            // buttons + gaps overflowed narrow screens and clipped the
            // Call button off the right edge.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DeveloperBrandButton(BrandIcons.Instagram, "Instagram") {
                    openUrl("https://www.instagram.com/__nshd.__")
                }
                DeveloperBrandButton(BrandIcons.WhatsApp, "WhatsApp") {
                    openUrl("https://wa.me/+918590455801")
                }
                DeveloperBrandButton(BrandIcons.Telegram, "Telegram") {
                    openUrl("https://t.me/nshd_0")
                }
                DeveloperBrandButton(BrandIcons.GitHub, "GitHub") {
                    openUrl("https://github.com/whitesr09")
                }
                DeveloperIconButton(Icons.Filled.Email, "Email") {
                    openUrl("mailto:nishadahammed081@gmail.com")
                }
                DeveloperIconButton(Icons.Filled.Call, "Call") {
                    openUrl("tel:+918590455801")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "JARVIS • v2.0.0",
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 1.2.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Developed by N S H D",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // ≥120dp clearance so the footer never hides behind the bottom
        // bar / raised mic FAB when scrolled to the end (F2).
        Spacer(modifier = Modifier.height(120.dp))
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
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                    // v1.9.0 (#77): his own persona.
                    PersonaOption(
                        name = if (settings.customPersonaName.isNotBlank()) {
                            "Custom — ${settings.customPersonaName}"
                        } else {
                            "Custom"
                        },
                        desc = if (settings.customPersonaPrompt.isNotBlank()) {
                            "Your own persona prompt (written below)."
                        } else {
                            "Write your own persona prompt below to enable this."
                        },
                        selected = settings.persona ==
                            com.nshd.jarvis2.brain.PersonaPrompts.PERSONA_CUSTOM
                    ) {
                        if (settings.customPersonaPrompt.isNotBlank()) {
                            onSettingsChange(
                                settings.copy(
                                    persona = com.nshd.jarvis2.brain
                                        .PersonaPrompts.PERSONA_CUSTOM
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Build your own persona",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = settings.customPersonaName,
                        onValueChange = {
                            onSettingsChange(settings.copy(customPersonaName = it))
                        },
                        label = { Text("Persona name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.customPersonaPrompt,
                        onValueChange = {
                            onSettingsChange(settings.copy(customPersonaPrompt = it))
                        },
                        label = { Text("Persona prompt — personality, tone, rules") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (personaTesting) return@OutlinedButton
                            personaTesting = true
                            personaTestReply = null
                            val snapshot = latestSettings.copy(
                                persona = com.nshd.jarvis2.brain
                                    .PersonaPrompts.PERSONA_CUSTOM
                            )
                            settingsScope.launch {
                                val outcome = runCatching {
                                    com.nshd.jarvis2.brain.FailoverRouter(
                                        configsInOrder = providerStore.orderedConfigs(),
                                        toolExecutor = null,
                                        store = providerStore
                                    ).chat(
                                        messages = listOf(
                                            com.nshd.jarvis2.brain.ChatMsg(
                                                "system",
                                                com.nshd.jarvis2.brain.PersonaPrompts
                                                    .build(snapshot, emptyList(), false)
                                            ),
                                            com.nshd.jarvis2.brain.ChatMsg(
                                                "user",
                                                "Say hello and introduce yourself in " +
                                                    "one or two lines."
                                            )
                                        ),
                                        preferReasoningModel = false
                                    )
                                }.getOrNull()
                                personaTestReply = when (outcome) {
                                    is com.nshd.jarvis2.brain.ChatOutcome.Success ->
                                        outcome.reply
                                    is com.nshd.jarvis2.brain.ChatOutcome.AllFailed ->
                                        "Test failed: ${outcome.summary}"
                                    else ->
                                        "Configure an API provider first (Settings → API & Connection)."
                                }
                                personaTesting = false
                            }
                        },
                        enabled = settings.customPersonaPrompt.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (personaTesting) "Testing…" else "Test this persona")
                    }
                    personaTestReply?.let { reply ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = reply,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
        LanguagePickerDialog(
            current = settings.language,
            onDismiss = { showLanguage = false },
            onPick = { lang -> onSettingsChange(settings.copy(language = lang)) }
        )
    }

    if (showVoiceEngine) {
        AlertDialog(
            onDismissRequest = { showVoiceEngine = false },
            title = { Text("Voice Engine") },
            text = {
                Column {
                    PersonaOption(
                        name = "Auto",
                        desc = "Native Live when the model supports it, otherwise Universal.",
                        selected = settings.voiceEngine == "AUTO"
                    ) { onSettingsChange(settings.copy(voiceEngine = "AUTO")) }
                    PersonaOption(
                        name = "Native Live",
                        desc = "Gemini real-time audio models only.",
                        selected = settings.voiceEngine == "NATIVE"
                    ) { onSettingsChange(settings.copy(voiceEngine = "NATIVE")) }
                    PersonaOption(
                        name = "Universal",
                        desc = "Mic → text → your chat model → voice. Works with any model.",
                        selected = settings.voiceEngine == "UNIVERSAL"
                    ) { onSettingsChange(settings.copy(voiceEngine = "UNIVERSAL")) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If Native Live fails to connect, the session " +
                            "switches to Universal automatically — you're never " +
                            "left on a dead error screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showVoiceEngine = false }) { Text("Done") } }
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

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy & Data Shield") },
            text = {
                Column {
                    Text(
                        text = "Jarvis keeps memory and chat history only in this " +
                            "app's private on-device database (jarvis_memory.db). " +
                            "Nothing below leaves the device except the export you " +
                            "share yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Memories stored: $privacyFactCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Chat messages stored: $privacyChatCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // v1.9.0 (#103): what Jarvis stores & where, in
                    // plain language, plus the log-redaction self-test.
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "What Jarvis stores & where",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "• Memories + chat history: the on-device " +
                            "database jarvis_memory.db (this app only).\n" +
                            "• API keys: encrypted on-device storage, " +
                            "never written to logs or exports.\n" +
                            "• App logs: files/logs inside this app's " +
                            "private folder, rotated at ~1 MB, with API " +
                            "keys and auth headers stripped before " +
                            "anything is written.\n" +
                            "• Screenshots you ask for: this app's " +
                            "private screenshots folder.\n" +
                            "• Voice: audio goes to your chosen AI " +
                            "provider to answer you; Jarvis itself keeps " +
                            "no audio recordings (wake-word samples you " +
                            "enrolled stay on-device).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val redactionProbe = "api_key=sk-test123456789 " +
                        "Authorization: Bearer abc.def-123 " +
                        "https://x.test/?key=AIzaTESTKEY"
                    val redacted = com.nshd.jarvis2.core.AppLog.sanitize(redactionProbe)
                    val redactionPass = !redacted.contains("sk-test123456789") &&
                        !redacted.contains("abc.def-123") &&
                        !redacted.contains("AIzaTESTKEY")
                    Text(
                        text = "Log redaction self-test: " +
                            if (redactionPass) {
                                "PASS — sample keys, tokens and URL keys are masked"
                            } else {
                                "FAIL — a sample secret survived redaction"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (redactionPass) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { exportMemories() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export memory JSON") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showImportMemory = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Import memory JSON") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmClearChat = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear chat history") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { confirmClearMemories = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear all memories") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text("Done") }
            }
        )
    }

    if (showImportMemory) {
        AlertDialog(
            onDismissRequest = { showImportMemory = false },
            title = { Text("Import memories") },
            text = {
                Column(modifier = Modifier.imePadding()) {
                    Text(
                        text = "Paste a memory JSON array ([{\"category\": \"…\", \"text\": \"…\"}]).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        label = { Text("JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { importMemories() },
                    enabled = importJsonText.isNotBlank()
                ) { Text("Import") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showImportMemory = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearChat) {
        AlertDialog(
            onDismissRequest = { confirmClearChat = false },
            title = { Text("Clear chat history?") },
            text = {
                Text("Every stored chat message on this device will be deleted. This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = { clearChatHistory() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearChat = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearMemories) {
        AlertDialog(
            onDismissRequest = { confirmClearMemories = false },
            title = { Text("Clear all memories?") },
            text = {
                Text("Every learned fact in the Neural Memory Bank will be deleted. This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = { clearAllMemories() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearMemories = false }) { Text("Cancel") }
            }
        )
    }

    if (showIdentity) {
        AlertDialog(
            onDismissRequest = { showIdentity = false },
            title = { Text("App Identity & Integrity") },
            text = {
                Column {
                    val idInfo = identityInfo
                    if (idInfo != null) {
                        IdentityRow("App", idInfo.appLabel)
                        IdentityRow("Package", idInfo.packageName)
                        IdentityRow(
                            "Version",
                            "${idInfo.versionName} (${idInfo.versionCode})"
                        )
                        IdentityRow("Signing SHA-256", idInfo.certSha256Short)
                        IdentityRow("Developer", "N S H D")
                        IdentityRow(
                            "Diagnostics",
                            "Session log & crash breadcrumbs — tap View"
                        )
                        TextButton(
                            onClick = {
                                showIdentity = false
                                showDiagnostics = true
                            }
                        ) { Text("View diagnostics") }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Certificate fingerprint truncated to its first " +
                                "16 hex characters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Package information is unavailable on this device.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIdentity = false }) { Text("Done") }
            }
        )
    }

    // Plan #4: barge-in sensitivity.
    if (showBargeIn) {
        AlertDialog(
            onDismissRequest = { showBargeIn = false },
            title = { Text("Interrupt sensitivity") },
            text = {
                Column {
                    PersonaOption(
                        name = "Low",
                        desc = "Jarvis keeps talking unless you speak clearly and at length.",
                        selected = settings.bargeInSensitivity == "LOW"
                    ) { onSettingsChange(settings.copy(bargeInSensitivity = "LOW")) }
                    PersonaOption(
                        name = "Normal",
                        desc = "Interrupts when you start speaking over a reply.",
                        selected = settings.bargeInSensitivity == "NORMAL"
                    ) { onSettingsChange(settings.copy(bargeInSensitivity = "NORMAL")) }
                    PersonaOption(
                        name = "High",
                        desc = "Stops at the first word you say.",
                        selected = settings.bargeInSensitivity == "HIGH"
                    ) { onSettingsChange(settings.copy(bargeInSensitivity = "HIGH")) }
                }
            },
            confirmButton = { TextButton(onClick = { showBargeIn = false }) { Text("Done") } }
        )
    }

    // Plan #7: device TTS voice picker with per-voice preview.
    if (showTtsVoices) {
        AlertDialog(
            onDismissRequest = { showTtsVoices = false },
            title = { Text("Device voice (Universal)") },
            text = {
                Column(
                    modifier = Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    PersonaOption(
                        name = "Automatic",
                        desc = "The best installed voice for your language.",
                        selected = settings.ttsVoiceName.isBlank()
                    ) {
                        onSettingsChange(settings.copy(ttsVoiceName = ""))
                        previewTtsVoice("")
                    }
                    if (ttsVoiceList.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Loading installed voices… if this stays " +
                                "empty, no TTS engine on this phone exposes " +
                                "a voice list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ttsVoiceList.forEach { voice ->
                        PersonaOption(
                            name = voice.name,
                            desc = voice.locale.displayName +
                                if (voice.isNetworkConnectionRequired) {
                                    " • needs network"
                                } else {
                                    " • on-device"
                                },
                            selected = settings.ttsVoiceName == voice.name
                        ) {
                            onSettingsChange(settings.copy(ttsVoiceName = voice.name))
                            previewTtsVoice(voice.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTtsVoices = false }) { Text("Done") } }
        )
    }

    // Plan #93: chat text size.
    if (showChatFont) {
        AlertDialog(
            onDismissRequest = { showChatFont = false },
            title = { Text("Chat text size") },
            text = {
                Column {
                    PersonaOption(
                        name = "Small",
                        desc = "Compact message text.",
                        selected = settings.chatFontSize == "S"
                    ) { onSettingsChange(settings.copy(chatFontSize = "S")) }
                    PersonaOption(
                        name = "Medium",
                        desc = "The default size.",
                        selected = settings.chatFontSize == "M"
                    ) { onSettingsChange(settings.copy(chatFontSize = "M")) }
                    PersonaOption(
                        name = "Large",
                        desc = "Bigger, easier to read at a glance.",
                        selected = settings.chatFontSize == "L"
                    ) { onSettingsChange(settings.copy(chatFontSize = "L")) }
                }
            },
            confirmButton = { TextButton(onClick = { showChatFont = false }) { Text("Done") } }
        )
    }

    // Plan #101: backup & restore.
    if (showBackup) {
        AlertDialog(
            onDismissRequest = { showBackup = false },
            title = { Text("Backup & Restore") },
            text = {
                Column(modifier = Modifier.imePadding()) {
                    Text(
                        text = "One JSON file with every setting, all " +
                            "memories, and your provider setups. API keys " +
                            "are never included — they stay encrypted on " +
                            "this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { exportBackup() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export full backup") }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Restore from a backup: paste the file's " +
                            "contents below. Memories are added (duplicates " +
                            "skipped); provider setups are never overwritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restoreJsonText,
                        onValueChange = { restoreJsonText = it },
                        label = { Text("Backup JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { importBackup() },
                    enabled = restoreJsonText.isNotBlank()
                ) { Text("Restore") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBackup = false }) { Text("Close") }
            }
        )
    }

    // Plan #79: transparency view.
    if (showKnows) {
        AlertDialog(
            onDismissRequest = { showKnows = false },
            title = { Text("What Jarvis knows") },
            text = {
                Column(
                    modifier = Modifier
                        .height(460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = knowsText.ifBlank { "Assembling…" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showKnows = false }) { Text("Done") } }
        )
    }

    // F4.1: session-log viewer (CrashLogger ring log).
    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }
}

private const val MAX_CHAT_PROBE = 100_000

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun isBatteryWhitelisted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** Reads the real label / version / signing cert for the Identity dialog. */
private fun loadIdentityInfo(context: Context): IdentityInfo? = runCatching {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        legacySigningFlags()
    }
    val pkgInfo = pm.getPackageInfo(context.packageName, flags)
    val signer: Signature? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            legacySignatures(pkgInfo)?.firstOrNull()
        }
    val sha = signer?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
            .uppercase()
            .take(16)
    } ?: "Unavailable"
    IdentityInfo(
        appLabel = pm.getApplicationLabel(context.applicationInfo).toString(),
        packageName = context.packageName,
        versionName = pkgInfo.versionName ?: "—",
        versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
        certSha256Short = sha
    )
}.getOrNull()

@Suppress("DEPRECATION")
private fun legacySigningFlags(): Int = PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun legacySignatures(info: PackageInfo): Array<Signature>? = info.signatures

@Composable
private fun IdentityRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** AssistChip-style pill used in the profile card (Enroll Voice / persona). */
@Composable
private fun ProfileChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Wake Word expanded block (Level 6): Tap vs Always-on mode toggle, and —
 * in Always-on mode — the live requirements checklist (offline model
 * download with progress, microphone permission, battery whitelist),
 * Start/Stop listening and the service's live status.
 *
 * F4: the Hearing icon + "Wake Word" title moved to the [GlassRow] above
 * this block in the Voice card; the full-width status line stays here
 * (F2 layout rule). The composable is ALWAYS called from the same site —
 * [expanded] only gates rendering, after every hook — so collapsing can
 * never disturb the caller's slot table.
 */
@Composable
private fun WakeWordControls(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit,
    onOpenPermissions: () -> Unit,
    expanded: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wakeStatus by WakeState.status.collectAsState()
    val downloadState by VoskModelManager.downloadState.collectAsState()
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    if (!expanded) return

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
        // Full-width status line on its own line (F2), then a full-width
        // segmented mode selector.
        Text(
            text = when {
                !settings.wakeAlwaysOn -> "Tap the mic / orb to talk"
                wakeStatus.listening -> "Always-on • Listening for \"Hey Jarvis\""
                wakeStatus.enabled -> "Always-on • ${wakeStatus.detail}"
                else -> "Always-on • Not running yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val selectTap: () -> Unit = {
                onSettingsChange(settings.copy(wakeAlwaysOn = false))
                WakeWordControl.stop(context)
            }
            val selectAlwaysOn: () -> Unit = {
                onSettingsChange(settings.copy(wakeAlwaysOn = true))
                if (modelReady && micGranted) {
                    runCatching { WakeWordControl.start(context) }
                }
            }
            if (settings.wakeAlwaysOn) {
                OutlinedButton(onClick = selectTap, modifier = Modifier.weight(1f)) {
                    Text("Tap")
                }
                Button(onClick = selectAlwaysOn, modifier = Modifier.weight(1f)) {
                    Text("Always-on")
                }
            } else {
                Button(onClick = selectTap, modifier = Modifier.weight(1f)) {
                    Text("Tap")
                }
                OutlinedButton(onClick = selectAlwaysOn, modifier = Modifier.weight(1f)) {
                    Text("Always-on")
                }
            }
        }

        if (settings.wakeAlwaysOn) {
            // ── v1.9.0 (#9): sensitivity + recent wake events ─────────
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Wake sensitivity",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Low = fewer false wakes (needs a very clear \"Hey Jarvis\"), " +
                    "High = wakes on almost anything close.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf("LOW", "NORMAL", "HIGH").forEach { level ->
                    val selected = settings.wakeSensitivity == level
                    val label = when (level) {
                        "LOW" -> "Low"
                        "HIGH" -> "High"
                        else -> "Normal"
                    }
                    if (selected) {
                        Button(
                            onClick = { onSettingsChange(settings.copy(wakeSensitivity = level)) },
                            modifier = Modifier.weight(1f)
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { onSettingsChange(settings.copy(wakeSensitivity = level)) },
                            modifier = Modifier.weight(1f)
                        ) { Text(label) }
                    }
                }
            }
            val wakeEvents = com.nshd.jarvis2.core.WakeEvents.recent(context)
            if (wakeEvents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Recent wake events",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                wakeEvents.forEach { event ->
                    Text(
                        text = "• $event",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                                "Downloading…"
                            is VoskModelManager.DownloadState.Extracting -> "Extracting…"
                            is VoskModelManager.DownloadState.Error ->
                                // F6: the state message is now a complete
                                // friendly sentence (see VoskModelManager).
                                state.message
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
                // Clean determinate bar + right-aligned percent (F2) —
                // at 1% the old full-width track read as a row of dots.
                val percent =
                    (downloadState as VoskModelManager.DownloadState.Downloading).percent
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            Column(modifier = Modifier.imePadding()) {
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
                                if (theme == AppTheme.DARK) " (default)" else "",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                if (settings.theme == AppTheme.CUSTOM) {
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
                                // F4 contract (Workstream B): the hex only
                                // takes effect while theme == CUSTOM, so
                                // applying MUST also select CUSTOM.
                                onSettingsChange(
                                    settings.copy(
                                        theme = AppTheme.CUSTOM,
                                        customBgHex = hex.trim()
                                    )
                                )
                            }
                        }) { Text("Apply custom") }
                        TextButton(onClick = {
                            hex = ""
                            onSettingsChange(settings.copy(customBgHex = null))
                        }) { Text("Clear") }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pick Custom to set your own background colour. " +
                            "Choosing a preset keeps any stored custom colour " +
                            "saved but inactive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

/**
 * Rounded-square outline button with a brand glyph (Developer footer).
 * F5.1: takes a Compose [ImageVector] from [BrandIcons] — the real
 * Simple Icons glyphs, rendered locally — instead of a drawable res.
 */
@Composable
private fun DeveloperBrandButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Rounded-square outline button with a Material icon (Developer footer). */
@Composable
private fun DeveloperIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

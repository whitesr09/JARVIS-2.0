package com.nshd.jarvis2.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.ProviderPresets
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.hands.DeepIntents
import com.nshd.jarvis2.service.A11yStatus
import com.nshd.jarvis2.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hands-Free & App Control (F4): the Digital Hands card and the
 * Vivo/iQOO keep-alive guide moved here from Settings, wrapped in a
 * hero + "how it works" explainer. Accessibility status re-checks on
 * every ON_RESUME (same pattern as [A11yWarningBanner]) because
 * Vivo/iQOO can switch the service off while the user is in the
 * system settings.
 */
@Composable
fun HandsFreeScreen(
    settings: JarvisSettings,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var a11yEnabled by remember { mutableStateOf(A11yStatus.isEnabled(context)) }
    var batteryFree by remember {
        mutableStateOf(A11yStatus.isIgnoringBatteryOptimizations(context))
    }
    var screenPreview by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = A11yStatus.isEnabled(context)
                batteryFree = A11yStatus.isIgnoringBatteryOptimizations(context)
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val providerStore = remember { ProviderStore(context) }
    val geminiConfigured = remember {
        providerStore.loadConfig(ProviderPresets.GEMINI.id).isConfigured
    }
    val geminiBrain = settings.voiceEngine == "NATIVE" ||
        (settings.voiceEngine == "AUTO" && geminiConfigured)

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(JarvisDimens.screenPadding)
    ) {
        // ── Header ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hands-Free & App Control",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Autonomous Voice Automation Guide",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        AvatarCatalog.byId(settings.avatarId).resId
                    ),
                    contentDescription = AvatarCatalog.byId(settings.avatarId).label,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        // ── Hero ───────────────────────────────────────────────────────
        GlassCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = settings.assistantName,
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Autonomous Device Control",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Control WhatsApp, YouTube, and phone settings " +
                        "completely hands-free with your voice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    onClick = { openAccessibilitySettings() },
                    shape = RoundedCornerShape(50),
                    color = if (a11yEnabled) {
                        JarvisSemanticColors.successContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 7.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (a11yEnabled) {
                                        Color(0xFF22C55E)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (a11yEnabled) {
                                "SERVICE ENABLED"
                            } else {
                                "SERVICE DISABLED • TAP TO ENABLE"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (a11yEnabled) {
                                JarvisSemanticColors.success
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (a11yEnabled) {
                    Button(onClick = {}, enabled = false) {
                        Text("Accessibility is ON ✓")
                    }
                    TextButton(onClick = { openAccessibilitySettings() }) {
                        Text("Open Accessibility Settings")
                    }
                } else {
                    Button(onClick = { openAccessibilitySettings() }) {
                        Text("Enable Accessibility Service")
                    }
                }
            }
        }

        // ── How it works ───────────────────────────────────────────────
        SectionLabel(
            text = "HOW IT WORKS",
            modifier = Modifier.padding(
                top = JarvisDimens.sectionGap,
                bottom = JarvisDimens.spaceSm
            )
        )
        GlassCard {
            SectionLabel("HOW IT WORKS (EASY GUIDE)")
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
            HowItWorksStep(
                icon = Icons.Filled.Mic,
                title = "Hold the Microphone Button",
                body = "Hold the center mic button or tap the orb to speak. " +
                    "You don't need to type or navigate manually."
            )
            Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
            HowItWorksStep(
                icon = Icons.Filled.Layers,
                title = if (geminiBrain) "Gemini Live Brain" else "Universal Brain",
                body = "Your speech is processed in real time. It understands " +
                    "context in English, Hindi, Malayalam, Hinglish and other " +
                    "regional languages."
            )
            Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
            HowItWorksStep(
                icon = Icons.Filled.Apps,
                title = "Digital Hands on Your Mobile",
                body = "Jarvis uses Android's accessibility service to type " +
                    "messages, tap send, search YouTube, and adjust settings " +
                    "just like your own hands."
            )
        }

        // ── Keep Accessibility Alive (moved from Settings) ─────────────
        SectionLabel(
            text = "PERMISSIONS",
            modifier = Modifier.padding(
                top = JarvisDimens.sectionGap,
                bottom = JarvisDimens.spaceSm
            )
        )
        GlassCard {
            SectionLabel("HOW TO ALLOW ALL PERMISSIONS")
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
            Text(
                text = "Status: Accessibility ${if (a11yEnabled) "ON" else "OFF"} • " +
                    "Battery ${if (batteryFree) "unrestricted" else "optimized"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (a11yEnabled && batteryFree) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
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
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "All other permissions",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Microphone, overlay, notifications and the rest — " +
                            "review them all in one place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onOpenPermissions) { Text("Open") }
            }
        }

        // ── Digital Hands tools (moved from Settings) ──────────────────
        SectionLabel(
            text = "DIGITAL HANDS",
            modifier = Modifier.padding(
                top = JarvisDimens.sectionGap,
                bottom = JarvisDimens.spaceSm
            )
        )
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconSquare(icon = Icons.Filled.Accessibility)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Accessibility Service", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (a11yEnabled) {
                            "Enabled — Jarvis can read and operate the current screen"
                        } else {
                            "Not enabled — intent and hardware tools still work"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { openAccessibilitySettings() }) {
                    Text("Open")
                }
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
                        scope.launch {
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
        // Bottom clearance so the last card never hides behind the bottom bar (F2).
        Spacer(modifier = Modifier.height(120.dp))
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
}

/** One numbered / icon step of the "How it works" guide. */
@Composable
private fun HowItWorksStep(
    icon: ImageVector,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

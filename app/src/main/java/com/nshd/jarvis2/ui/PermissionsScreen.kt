package com.nshd.jarvis2.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private val RUNTIME_PERMISSIONS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.CAMERA,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS
)

private data class PermissionItem(
    val title: String,
    val description: String,
    val granted: Boolean,
    val actionLabel: String,
    val onAction: () -> Unit
)

/** The 11 authorizations from the reference blueprint (Page 12), with live status. */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Increment to force status re-reads after returning from a settings screen / prompt.
    var refreshTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    fun has(permission: String): Boolean {
        // Reading refreshTick makes status recompute when it changes.
        if (refreshTick < 0) return false
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun openIntent(intent: Intent) {
        runCatching { context.startActivity(intent) }
        refreshTick++
    }

    val pkg = context.packageName
    val items = listOf(
        PermissionItem(
            title = "Microphone",
            description = "Voice commands and the live voice loop",
            granted = has(Manifest.permission.RECORD_AUDIO),
            actionLabel = "Grant",
            onAction = { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        ),
        PermissionItem(
            title = "Precise Location",
            description = "Weather and location-aware answers",
            granted = has(Manifest.permission.ACCESS_FINE_LOCATION),
            actionLabel = "Grant",
            onAction = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }
        ),
        PermissionItem(
            title = "Notifications",
            description = "Task updates and the foreground-service notice",
            granted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            actionLabel = "Manage",
            onAction = {
                openIntent(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                )
            }
        ),
        PermissionItem(
            title = "Display Over Other Apps",
            description = "Floating avatar and Task HUD above other apps",
            granted = Settings.canDrawOverlays(context),
            actionLabel = "Manage",
            onAction = {
                openIntent(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$pkg")
                    )
                )
            }
        ),
        PermissionItem(
            title = "Accessibility Service",
            description = "Digital Hands — see the screen, tap, type, scroll. " +
                "On Vivo/iQOO it may switch itself off — re-enable it here; " +
                "Settings → Digital Hands has a keep-alive guide.",
            granted = isAccessibilityEnabled(context),
            actionLabel = "Manage",
            onAction = { openIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ),
        PermissionItem(
            title = "Contacts",
            description = "Resolve names and aliases for calls and messages",
            granted = has(Manifest.permission.READ_CONTACTS),
            actionLabel = "Grant",
            onAction = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
        ),
        PermissionItem(
            title = "Phone Calls",
            description = "Place cellular calls on your command",
            granted = has(Manifest.permission.CALL_PHONE),
            actionLabel = "Grant",
            onAction = { permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE)) }
        ),
        PermissionItem(
            title = "Camera & Flashlight",
            description = "Torch control and camera-based vision",
            granted = has(Manifest.permission.CAMERA),
            actionLabel = "Grant",
            onAction = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }
        ),
        PermissionItem(
            title = "Nearby Bluetooth Devices",
            description = "Connect and toggle Bluetooth accessories",
            granted = has(Manifest.permission.BLUETOOTH_CONNECT),
            actionLabel = "Grant",
            onAction = { permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
        ),
        PermissionItem(
            title = "Background Battery Access",
            description = "Stay alive in the background (Vivo/iQOO whitelist)",
            granted = isIgnoringBatteryOptimizations(context),
            actionLabel = "Manage",
            onAction = { openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        ),
        PermissionItem(
            title = "Modify System Settings",
            description = "Brightness and other system toggles",
            granted = Settings.System.canWrite(context),
            actionLabel = "Manage",
            onAction = {
                openIntent(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$pkg")
                    )
                )
            }
        )
    )

    val grantedCount = items.count { it.granted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(JarvisDimens.screenPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "System Permissions",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
        Text(
            text = "Jarvis needs these 11 authorizations to see, hear and act. " +
                "Each one is only used when a task needs it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        // Header summary: how many are granted, with a progress bar.
        Card(
            shape = RoundedCornerShape(JarvisDimens.radiusCard),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.heroElevation),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(JarvisDimens.cardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$grantedCount of ${items.size} granted",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    JarvisPill(
                        text = if (grantedCount == items.size) "All set" else "Action needed",
                        tone = if (grantedCount == items.size) PillTone.SUCCESS else PillTone.WARNING,
                        icon = if (grantedCount == items.size) Icons.Filled.Check else Icons.Filled.Warning
                    )
                }
                Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
                LinearProgressIndicator(
                    progress = { grantedCount.toFloat() / items.size.coerceAtLeast(1) },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))

        items.forEachIndexed { index, item ->
            AppearOnEntry(index = index) {
                PermissionCard(item)
            }
        }

        Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
        Button(
            onClick = { permissionLauncher.launch(RUNTIME_PERMISSIONS) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("GRANT ALL PERMISSIONS")
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Grant All covers the runtime permissions; Overlay, Accessibility, " +
                "Battery and Write-Settings open their system pages from their own rows.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Bottom clearance so the last rows never hide behind the bottom bar (F2).
        Spacer(modifier = Modifier.height(120.dp))
    }
}

@Composable
private fun PermissionCard(item: PermissionItem) {
    Card(
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = JarvisDimens.spaceMd)
    ) {
        Column(modifier = Modifier.padding(JarvisDimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                JarvisPill(
                    text = if (item.granted) "Granted" else "Not granted",
                    tone = if (item.granted) PillTone.SUCCESS else PillTone.WARNING,
                    icon = if (item.granted) Icons.Filled.Check else Icons.Filled.Warning
                )
            }
            Spacer(modifier = Modifier.height(JarvisDimens.spaceXs))
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!item.granted) {
                Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
                OutlinedButton(
                    onClick = item.onAction,
                    modifier = Modifier.align(Alignment.End)
                ) { Text(item.actionLabel) }
            }
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val expected = "${context.packageName}/com.nshd.jarvis2.service.JarvisAccessibilityService"
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

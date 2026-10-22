package com.nshd.jarvis2.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nshd.jarvis2.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Level filter chips: Info covers D + I (debug lines are info-grade here). */
private enum class LogLevelFilter(val label: String) {
    ALL("All"), INFO("Info"), WARN("Warn"), ERROR("Error");

    fun matches(level: AppLog.Level): Boolean = when (this) {
        ALL -> true
        INFO -> level == AppLog.Level.D || level == AppLog.Level.I
        WARN -> level == AppLog.Level.W
        ERROR -> level == AppLog.Level.E
    }
}

private val LOG_TAGS = listOf(
    "All", "App", "Voice", "Live", "STT", "TTS", "API",
    "Agent", "Gate", "Hands", "Wake", "Overlay", "Crash"
)

/**
 * F5 — the in-app log viewer (Settings → App Logs & Diagnostics).
 * Live AppLog entries with level/tag/search filters, pause, copy,
 * share (FileProvider), save-to-Downloads (MediaStore) and clear.
 * The list is reverse-laid-out: newest at the bottom, always pinned
 * to the latest line while live.
 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val liveEntries by AppLog.entries.collectAsState()
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<AppLog.Entry>>(emptyList()) }
    val shown = if (paused) frozen else liveEntries

    var levelFilter by remember { mutableStateOf(LogLevelFilter.ALL) }
    var tagFilter by remember { mutableStateOf("All") }
    var tagMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var fileSizeBytes by remember { mutableStateOf(0L) }
    val expandedKeys = remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(liveEntries.size) {
        fileSizeBytes = withContext(Dispatchers.IO) { AppLog.totalFileSizeBytes() }
    }

    val filtered = remember(shown, levelFilter, tagFilter, query) {
        shown.filter { entry ->
            levelFilter.matches(entry.level) &&
                (tagFilter == "All" || entry.tag == tagFilter) &&
                (query.isBlank() ||
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.tag.contains(query, ignoreCase = true))
        }
    }

    fun shareLog() {
        val text = AppLog.formatAll(filtered)
        runCatching {
            val file = File(context.cacheDir, "jarvis-log.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Jarvis app log")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share app log"))
        }
    }

    fun saveToDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 26–28: no permissionless Downloads access — share instead.
            statusText = "Saving directly needs Android 10+ — use Share"
            shareLog()
            return
        }
        val text = AppLog.formatAll(filtered)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "jarvis-log-$stamp.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: return@runCatching false
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray())
                    }
                    true
                }.getOrDefault(false)
            }
            statusText = if (ok) {
                "Saved to Downloads as jarvis-log-$stamp.txt"
            } else {
                "Save failed — use Share instead"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // F5.1: the search field stays above the keyboard.
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        // ── Header ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "App Logs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${filtered.size} entries • ${formatBytes(fileSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Filters ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogLevelFilter.values().forEach { filter ->
                FilterChip(
                    selected = levelFilter == filter,
                    onClick = { levelFilter = filter },
                    label = { Text(filter.label) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { tagMenuOpen = true }) {
                    Text(tagFilter)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = tagMenuOpen,
                    onDismissRequest = { tagMenuOpen = false }
                ) {
                    LOG_TAGS.forEach { tag ->
                        DropdownMenuItem(
                            text = { Text(tag) },
                            onClick = {
                                tagFilter = tag
                                tagMenuOpen = false
                            }
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search logs") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        // ── Actions ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LogActionButton(
                icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                label = if (paused) "Resume" else "Pause",
                modifier = Modifier.weight(1f)
            ) {
                if (!paused) frozen = liveEntries
                paused = !paused
                statusText = if (paused) "Live updates paused" else "Live updates resumed"
            }
            LogActionButton(
                icon = Icons.Filled.ContentCopy,
                label = "Copy",
                modifier = Modifier.weight(1f)
            ) {
                clipboard.setText(AnnotatedString(AppLog.formatAll(filtered)))
                statusText = "Copied ${filtered.size} entries"
            }
            LogActionButton(
                icon = Icons.Filled.Share,
                label = "Share",
                modifier = Modifier.weight(1f)
            ) { shareLog() }
            LogActionButton(
                icon = Icons.Filled.FileDownload,
                label = "Save",
                modifier = Modifier.weight(1f)
            ) { saveToDownloads() }
            LogActionButton(
                icon = Icons.Filled.Delete,
                label = "Clear",
                modifier = Modifier.weight(1f)
            ) { confirmClear = true }
        }
        statusText?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // ── Entries (newest at the bottom, pinned live) ────────────────
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No log entries match — logs appear as the app runs " +
                        "(voice sessions, API calls, tools, errors).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Index keys: two entries can share a millisecond AND
                // message (stage bursts), and LazyColumn crashes on
                // duplicate keys.
                items(
                    count = filtered.size,
                    key = { index -> filtered.size - 1 - index }
                ) { index ->
                    val entry = filtered[filtered.size - 1 - index]
                    LogEntryRow(
                        entry = entry,
                        expanded = entry.timeMs in expandedKeys.value,
                        onToggle = {
                            if (entry.stack != null) {
                                expandedKeys.value =
                                    if (entry.timeMs in expandedKeys.value) {
                                        expandedKeys.value - entry.timeMs
                                    } else {
                                        expandedKeys.value + entry.timeMs
                                    }
                            }
                        }
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all logs?") },
            text = {
                Text(
                    "The in-app log history and log files will be deleted. " +
                        "The separate crash report is kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLog.clear()
                        frozen = emptyList()
                        confirmClear = false
                        statusText = "Logs cleared"
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LogActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LogEntryRow(
    entry: AppLog.Entry,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val levelColor = when (entry.level) {
        AppLog.Level.D -> Color(0xFF9E9E9E)
        AppLog.Level.I -> Color(0xFF42A5F5)
        AppLog.Level.W -> Color(0xFFFFA726)
        AppLog.Level.E -> Color(0xFFEF5350)
    }
    val stamp = remember(entry.timeMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timeMs))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.stack != null) { onToggle() }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = stamp,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                color = levelColor,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = entry.level.label,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                ),
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded && entry.stack != null) {
            Text(
                text = entry.stack,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

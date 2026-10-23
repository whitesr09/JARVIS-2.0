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
    // v1.9.0 insights (#81 usage, #99 slowest, #100 storage).
    var showInsights by remember { mutableStateOf(false) }
    var storageRows by remember { mutableStateOf<List<StorageRow>>(emptyList()) }
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
        // F8 (#97): one-tap diagnostics pack — version, device, last
        // crash, last 500 log lines, provider/model/probe summary and
        // settings summary in a single shareable .txt (NO keys).
        OutlinedButton(
            onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        com.nshd.jarvis2.core.DiagnosticsPack.share(context)
                    }
                    statusText = if (ok) {
                        "Diagnostics pack ready to share"
                    } else {
                        "Diagnostics pack failed — try Share instead"
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share diagnostics pack")
        }
        statusText?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // ── v1.9.0 insights: usage meter (#81), slowest commands
        // (#99), storage manager (#100) ───────────────────────────────
        OutlinedButton(
            onClick = {
                showInsights = !showInsights
                if (showInsights) {
                    scope.launch {
                        storageRows = withContext(Dispatchers.IO) {
                            computeStorageRows(context)
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(if (showInsights) "Hide insights" else "Insights — usage · slowest · storage")
        }
        if (showInsights) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = "Usage per provider (from API log lines; tokens are a rough estimate)",
                    style = MaterialTheme.typography.titleSmall
                )
                val usage = remember(liveEntries) {
                    com.nshd.jarvis2.core.UsageMeter.summarize(liveEntries)
                }
                if (usage.isEmpty()) {
                    Text(
                        text = "No provider calls in the current log yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    usage.forEach { row ->
                        Text(
                            text = "${row.providerId}: ${row.requestsToday} requests today · " +
                                "${row.requestsWeek} this week · " +
                                "≈${row.estTokensToday / 1000}k tokens today · " +
                                "≈${row.estTokensWeek / 1000}k this week",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Slowest commands (from turn timings)",
                    style = MaterialTheme.typography.titleSmall
                )
                val slowest = remember(liveEntries) { slowestCommands(liveEntries) }
                if (slowest.isEmpty()) {
                    Text(
                        text = "No completed turns in the current log yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    slowest.forEach { (title, ms) ->
                        Text(
                            text = "• $title — ${ms / 1000.0}s",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleSmall
                )
                storageRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${row.label}: ${formatBytes(row.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (row.action != null && row.bytes > 0) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runStorageCleanup(context, row.action)
                                        }
                                        storageRows = withContext(Dispatchers.IO) {
                                            computeStorageRows(context)
                                        }
                                        statusText = "Cleaned ${row.label}"
                                    }
                                }
                            ) { Text("Clean") }
                        }
                    }
                }
            }
        }

        // ── Entries (newest at the bottom, pinned live) ────────────────
        if (filtered.isEmpty()) {
            // #96: shared friendly empty state; with filters active the
            // action clears them, otherwise there is nothing to do yet.
            val filtersActive = levelFilter != LogLevelFilter.ALL ||
                tagFilter != "All" || query.isNotBlank()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                FriendlyEmptyState(
                    emoji = "📋",
                    title = if (filtersActive) {
                        "No log entries match"
                    } else {
                        "No log entries yet"
                    },
                    actionLabel = if (filtersActive) "Clear filters" else null,
                    onAction = if (filtersActive) {
                        {
                            levelFilter = LogLevelFilter.ALL
                            tagFilter = "All"
                            query = ""
                        }
                    } else {
                        null
                    }
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

/** v1.9.0 (#100): one storage bucket in the Storage manager. */
private data class StorageRow(
    val label: String,
    val bytes: Long,
    /** Cleanup action id, or null when the bucket isn't cleanable here. */
    val action: String?
)

private fun dirSize(dir: java.io.File?): Long {
    if (dir == null || !dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

private fun computeStorageRows(context: android.content.Context): List<StorageRow> =
    listOf(
        StorageRow(
            "App logs",
            com.nshd.jarvis2.core.AppLog.totalFileSizeBytes(),
            null
        ),
        StorageRow(
            "Memory + chat database",
            context.getDatabasePath("jarvis_memory.db")?.length() ?: 0L,
            null
        ),
        StorageRow(
            "Cache",
            dirSize(context.cacheDir),
            "cache"
        ),
        StorageRow(
            "Saved screenshots",
            dirSize(java.io.File(context.filesDir, "screenshots")),
            "screenshots"
        ),
        StorageRow(
            "Tap-failure screenshots",
            dirSize(java.io.File(context.filesDir, "tapshots")),
            "tapshots"
        )
    )

private fun runStorageCleanup(context: android.content.Context, action: String?) {
    when (action) {
        "cache" -> context.cacheDir?.listFiles()?.forEach {
            runCatching { it.deleteRecursively() }
        }
        "screenshots" -> java.io.File(context.filesDir, "screenshots")
            .listFiles()?.forEach { runCatching { it.delete() } }
        "tapshots" -> java.io.File(context.filesDir, "tapshots")
            .listFiles()?.forEach { runCatching { it.delete() } }
    }
}

/**
 * v1.9.0 (#99): slowest completed turns, from the pipeline's
 * "turn '…' finished in Nms" log lines (tag "Queue"). Grouped by
 * command title, worst average first, top 5.
 */
private fun slowestCommands(
    entries: List<com.nshd.jarvis2.core.AppLog.Entry>
): List<Pair<String, Long>> {
    val line = Regex("turn '(.+?)' finished in (\\d+)ms")
    val totals = linkedMapOf<String, Pair<Long, Int>>() // title → (totalMs, runs)
    entries.filter { it.tag == "Queue" }.forEach { entry ->
        val match = line.find(entry.message) ?: return@forEach
        val title = match.groupValues[1]
        val ms = match.groupValues[2].toLongOrNull() ?: return@forEach
        val (total, runs) = totals[title] ?: (0L to 0)
        totals[title] = (total + ms) to (runs + 1)
    }
    return totals.entries
        .map { (title, agg) -> title to (agg.first / agg.second.coerceAtLeast(1)) }
        .sortedByDescending { it.second }
        .take(5)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

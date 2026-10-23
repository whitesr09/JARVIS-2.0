package com.nshd.jarvis2.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.memory.Fact
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Level 2: the Memory Core — searchable, categorized facts with manual
 * CRUD and JSON backup/restore. Auto-save arrives via Chat ("remember …").
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { MemoryDb(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var facts by remember { mutableStateOf<List<Fact>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0) }
    // v1.9.0 (#73): review queue; (#74): duplicate-merge suggestions.
    var pending by remember { mutableStateOf<List<Fact>>(emptyList()) }
    var dupeGroups by remember { mutableStateOf<List<List<Fact>>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Fact?>(null) } // null + showEditor → new fact
    var showEditor by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val (list, count) = withContext(Dispatchers.IO) {
                db.allFacts(
                    query = search.ifBlank { null },
                    category = categoryFilter
                ) to db.factCount()
            }
            facts = list
            totalCount = count
            pending = withContext(Dispatchers.IO) { db.pendingFacts() }
        }
    }

    LaunchedEffect(search, categoryFilter) { reload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = null; showEditor = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add memory")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // F5.1: the search field stays above the keyboard.
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Neural Memory Bank",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$totalCount memories",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        val json = withContext(Dispatchers.IO) {
                            val arr = JSONArray()
                            db.allFacts(null, null).forEach { f ->
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
                        context.startActivity(
                            Intent.createChooser(intent, "Export Jarvis memories")
                        )
                    }
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Export memories as JSON")
                }
                TextButton(onClick = { showImport = true }) { Text("Import") }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search memories…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(JarvisDimens.radiusField),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = JarvisDimens.spaceLg)
            )
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

            Row(
                modifier = Modifier.padding(horizontal = JarvisDimens.spaceLg),
                horizontalArrangement = Arrangement.spacedBy(JarvisDimens.spaceSm)
            ) {
                MemoryFilterChip(
                    label = "All",
                    selected = categoryFilter == null,
                    onClick = { categoryFilter = null }
                )
                MemoryCategories.FILTERABLE.forEach { cat ->
                    MemoryFilterChip(
                        label = cat,
                        selected = categoryFilter == cat,
                        onClick = { categoryFilter = cat }
                    )
                }
            }
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))

            // ── v1.9.0 (#73): review queue — auto-extracted facts
            // wait here for one-tap approve / reject ──────────────────
            if (pending.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(JarvisDimens.radiusCard),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = JarvisDimens.spaceLg)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Review queue — ${pending.size} auto-saved " +
                                if (pending.size == 1) "fact" else "facts",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Jarvis noticed these in conversation. " +
                                "Approve to keep them in memory, reject to drop them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        pending.forEach { fact ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "[${fact.category}] ${fact.text}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.setFactPending(fact.id, false)
                                        }
                                        reload()
                                    }
                                }) { Text("Approve") }
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.deleteFact(fact.id)
                                        }
                                        reload()
                                    }
                                }) { Text("Reject") }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
            }

            // ── v1.9.0 (#74): memory tidy — duplicate merge suggestions ──
            Row(
                modifier = Modifier.padding(horizontal = JarvisDimens.spaceLg),
                horizontalArrangement = Arrangement.spacedBy(JarvisDimens.spaceSm)
            ) {
                OutlinedButton(onClick = {
                    scope.launch {
                        dupeGroups = withContext(Dispatchers.IO) {
                            findDuplicateGroups(db.allFacts(null, null))
                        }
                        if (dupeGroups.isEmpty()) {
                            snackbar.showSnackbar("No duplicates found — memory is tidy.")
                        }
                    }
                }) { Text("Find duplicates") }
            }
            if (dupeGroups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
                Card(
                    shape = RoundedCornerShape(JarvisDimens.radiusCard),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = JarvisDimens.spaceLg)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Possible duplicates",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        dupeGroups.forEach { group ->
                            Spacer(modifier = Modifier.height(8.dp))
                            group.forEach { fact ->
                                Text(
                                    text = "• [${fact.category}] ${fact.text}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            group.drop(1).forEach { db.deleteFact(it.id) }
                                        }
                                        dupeGroups = dupeGroups.filter { it != group }
                                        reload()
                                    }
                                }) { Text("Merge — keep first") }
                                TextButton(onClick = {
                                    dupeGroups = dupeGroups.filter { it != group }
                                }) { Text("Keep all") }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
            }

            if (facts.isEmpty()) {
                // #96: shared friendly empty state; the action reuses
                // this screen's existing add-fact editor entry point.
                FriendlyEmptyState(
                    emoji = "🧠",
                    title = "No memories yet",
                    actionLabel = "Add a memory",
                    onAction = { editing = null; showEditor = true }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = JarvisDimens.spaceLg),
                // Bottom content padding so the last fact never hides
                // behind the bottom bar / FAB (F2).
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(JarvisDimens.spaceMd)
            ) {
                items(facts, key = { it.id }) { fact ->
                    AppearOnEntry {
                        FactCard(
                            fact = fact,
                            onEdit = { editing = fact; showEditor = true },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.deleteFact(fact.id) }
                                    reload()
                                }
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(72.dp))
        }
    }

    if (showEditor) {
        FactEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { category, text ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val e = editing
                        if (e == null) db.addFact(category, text)
                        else db.updateFact(e.id, category, text)
                    }
                    showEditor = false
                    reload()
                }
            }
        )
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { raw ->
                scope.launch {
                    val inserted = withContext(Dispatchers.IO) {
                        runCatching {
                            val arr = JSONArray(raw)
                            var n = 0
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val text = obj.optString("text").trim()
                                if (text.isNotEmpty()) {
                                    val cat = obj.optString("category")
                                        .takeIf { it in MemoryCategories.ALL }
                                        ?: MemoryCategories.GENERAL
                                    db.addFact(cat, text)
                                    n++
                                }
                            }
                            n
                        }.getOrElse { -1 }
                    }
                    showImport = false
                    if (inserted >= 0) {
                        reload()
                        snackbar.showSnackbar("Imported $inserted memories")
                    } else {
                        snackbar.showSnackbar("Import failed — that wasn't a valid memory JSON")
                    }
                }
            }
        )
    }
}

/** Filter chip with the D2 selected treatment: lavender fill + check. */
/**
 * v1.9.0 (#74): group facts that say (nearly) the same thing —
 * identical after normalisation, or ≥70% shared word tokens
 * (Jaccard). Greedy clustering is fine at personal-memory scale.
 */
private fun findDuplicateGroups(facts: List<Fact>): List<List<Fact>> {
    fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun tokens(text: String): Set<String> =
        normalize(text).split(" ").filter { it.isNotBlank() }.toSet()

    fun similar(a: Fact, b: Fact): Boolean {
        if (normalize(a.text) == normalize(b.text)) return true
        val ta = tokens(a.text)
        val tb = tokens(b.text)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val union = (ta + tb).size
        if (union == 0) return false
        return (ta intersect tb).size.toDouble() / union >= 0.7
    }

    val used = mutableSetOf<Long>()
    val groups = mutableListOf<List<Fact>>()
    for (fact in facts) {
        if (fact.id in used) continue
        val group = mutableListOf(fact)
        for (other in facts) {
            if (other.id == fact.id || other.id in used) continue
            if (similar(fact, other)) group += other
        }
        if (group.size > 1) {
            group.forEach { used += it.id }
            groups += group
        }
    }
    return groups
}

@Composable
private fun MemoryFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
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
        shape = RoundedCornerShape(50)
    )
}

@Composable
private fun FactCard(fact: Fact, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(JarvisDimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JarvisPill(text = fact.category, tone = PillTone.PRIMARY)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit memory")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete memory",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(text = fact.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FactEditorDialog(
    initial: Fact?,
    onDismiss: () -> Unit,
    onSave: (category: String, text: String) -> Unit
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var category by remember {
        mutableStateOf(initial?.category ?: MemoryCategories.GENERAL)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add memory" else "Edit memory") },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Fact") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column {
                    MemoryCategories.ALL.chunked(3).forEach { rowCats ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowCats.forEach { cat ->
                                MemoryFilterChip(
                                    label = cat,
                                    selected = category == cat,
                                    onClick = { category = cat }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onSave(category, text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (raw.isNotBlank()) onImport(raw) },
                enabled = raw.isNotBlank()
            ) { Text("Import") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

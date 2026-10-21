package com.nshd.jarvis2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.memory.MemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class UiMessage(
    val fromUser: Boolean,
    val text: String,
    val caption: String? = null,
    val isTool: Boolean = false
)

private enum class ChatMode { JARVIS, CODE }

/**
 * Chat uses the shared [AgentPipeline]: local memory commands and
 * deterministic phone commands first, then the Level 4 tool-calling agent
 * loop. Tool activity is persisted as role="tool" history.
 */
@Composable
fun ChatScreen(settings: JarvisSettings) {
    val context = LocalContext.current
    val db = remember { MemoryDb(context) }
    val providerStore = remember { ProviderStore(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ChatMode.JARVIS) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<UiMessage>() }
    val listState = rememberLazyListState()

    fun greeting(): UiMessage = UiMessage(
        fromUser = false,
        text = "Hello ${settings.callsign}. I'm ${settings.assistantName} — my brain is " +
            "live now. Add an API key in Settings → API & Connection, or just tell me " +
            "\"remember …\" and I'll store it in my Memory Core."
    )

    LaunchedEffect(Unit) {
        val history = withContext(Dispatchers.IO) { db.lastMessages(30) }
        messages.clear()
        if (history.isEmpty()) {
            messages.add(greeting())
        } else {
            history.forEach { stored ->
                messages.add(
                    UiMessage(
                        fromUser = stored.role == "user",
                        text = stored.content,
                        isTool = stored.role == "tool"
                    )
                )
            }
        }
    }

    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex + if (sending) 1 else 0)
        }
    }

    fun appendAssistant(text: String, caption: String? = null, persist: Boolean = true) {
        messages.add(UiMessage(fromUser = false, text = text, caption = caption))
        if (persist) {
            scope.launch(Dispatchers.IO) { db.addMessage("assistant", text) }
        }
    }

    fun appendToolEvent(event: String) {
        messages.add(UiMessage(fromUser = false, text = event, isTool = true))
        scope.launch(Dispatchers.IO) { db.addMessage("tool", event) }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        input = ""
        messages.add(UiMessage(fromUser = true, text = text))
        scope.launch(Dispatchers.IO) { db.addMessage("user", text) }

        // Shared pipeline: memory commands → quick phone commands → agent loop.
        sending = true
        val codeMode = mode == ChatMode.CODE
        val appContext = context.applicationContext
        scope.launch {
            try {
                val reply = AgentPipeline.handleUserText(
                    context = appContext,
                    text = text,
                    settings = settings,
                    memoryDb = db,
                    providerStore = providerStore,
                    codeMode = codeMode,
                    onEvent = { event -> appendToolEvent(event) }
                )
                appendAssistant(reply)
            } catch (e: Exception) {
                appendAssistant(
                    "I hit a problem handling that: ${e.message ?: "unexpected error"}"
                )
            } finally {
                sending = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = settings.assistantName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (sending) "${settings.assistantName} is thinking…"
                    else "${settings.assistantName} • ${settings.persona} • Active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showClearConfirm = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Clear chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg -> MessageBubble(msg) }
            if (sending) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Card(
                            shape = RoundedCornerShape(JarvisDimens.radiusCard),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text(
                                text = "• • •",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mode pills
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == ChatMode.JARVIS,
                onClick = { mode = ChatMode.JARVIS },
                label = { Text(settings.assistantName) },
                shape = RoundedCornerShape(50)
            )
            FilterChip(
                selected = mode == ChatMode.CODE,
                onClick = { mode = ChatMode.CODE },
                label = { Text("<> Code") },
                shape = RoundedCornerShape(50)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message ${settings.assistantName}") },
                shape = RoundedCornerShape(JarvisDimens.radiusField),
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { send() }, enabled = !sending) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    ConfirmGateDialog()

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear chat?") },
            text = {
                Text("This deletes the conversation history on this device. Your saved memories are kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { db.clearChat() }
                        messages.clear()
                        messages.add(greeting())
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    if (msg.isTool) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Card(
                shape = RoundedCornerShape(JarvisDimens.radiusField),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.widthIn(max = 340.dp)
            ) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
        return
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(modifier = Modifier.widthIn(max = 320.dp)) {
            Card(
                shape = RoundedCornerShape(
                    topStart = JarvisDimens.radiusCard, topEnd = JarvisDimens.radiusCard,
                    bottomStart = if (msg.fromUser) JarvisDimens.radiusCard else 6.dp,
                    bottomEnd = if (msg.fromUser) 6.dp else JarvisDimens.radiusCard
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (msg.fromUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (msg.fromUser) {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        MarkdownText(
                            content = msg.text,
                            textColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (msg.caption != null) {
                Text(
                    text = msg.caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 3.dp)
                )
            }
        }
    }
}

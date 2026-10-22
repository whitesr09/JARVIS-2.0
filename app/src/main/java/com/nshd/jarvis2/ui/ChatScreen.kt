package com.nshd.jarvis2.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.brain.ToolEvent
import com.nshd.jarvis2.brain.ToolEventKind
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.voice.VoiceMode
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import java.util.Calendar
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
 * loop. Tool activity renders as small status chips (F2) and is NOT
 * persisted — raw tool output never becomes history text. (Pre-F2
 * role="tool" rows still load, cleaned into chip text.)
 */
@Composable
fun ChatScreen(
    settings: JarvisSettings,
    onOpenSettings: () -> Unit = {},
    onOpenVoice: () -> Unit = {}
) {
    val context = LocalContext.current
    val db = remember { MemoryDb(context) }
    val providerStore = remember { ProviderStore(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ChatMode.JARVIS) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var composerMenuOpen by remember { mutableStateOf(false) }
    // Header subtitle reflects the live voice session (F4).
    val voiceState by VoiceSessionManager.uiState.collectAsState()
    val messages = remember { mutableStateListOf<UiMessage>() }
    val listState = rememberLazyListState()

    // Live streaming bubble: deltas accumulate in a plain buffer (written
    // from the streaming thread); the Compose state is refreshed at most
    // every ~50 ms so recomposition stays cheap.
    val streamBuffer = remember { StringBuilder() }
    var streamingText by remember { mutableStateOf("") }
    var lastStreamUiUpdate by remember { mutableStateOf(0L) }

    fun onAssistantDelta(delta: String) {
        synchronized(streamBuffer) { streamBuffer.append(delta) }
        val now = System.currentTimeMillis()
        if (now - lastStreamUiUpdate >= 50L) {
            lastStreamUiUpdate = now
            streamingText = synchronized(streamBuffer) { streamBuffer.toString() }
        }
    }

    fun resetStream() {
        synchronized(streamBuffer) { streamBuffer.setLength(0) }
        streamingText = ""
        lastStreamUiUpdate = 0L
    }

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
                        text = if (stored.role == "tool") {
                            cleanLegacyToolLine(stored.content)
                        } else {
                            stored.content
                        },
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

    // Keep the live streaming bubble pinned to the bottom as it grows.
    LaunchedEffect(streamingText) {
        if (streamingText.isNotEmpty() && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex + 1)
        }
    }

    fun appendAssistant(text: String, caption: String? = null, persist: Boolean = true) {
        messages.add(UiMessage(fromUser = false, text = text, caption = caption))
        if (persist) {
            scope.launch(Dispatchers.IO) { db.addMessage("assistant", text) }
        }
    }

    fun appendToolEvent(event: ToolEvent) {
        // A new tool turn starts: the previous turn's streamed narration
        // is superseded by the tool activity + the upcoming final reply.
        // Chips are session-only UI — never written to chat history.
        if (event.kind == ToolEventKind.STARTED) resetStream()
        messages.add(UiMessage(fromUser = false, text = event.chipText, isTool = true))
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        input = ""
        messages.add(UiMessage(fromUser = true, text = text))
        scope.launch(Dispatchers.IO) { db.addMessage("user", text) }

        // Shared pipeline: memory commands → quick phone commands → agent loop.
        sending = true
        resetStream()
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
                    onEvent = { event -> appendToolEvent(event) },
                    onAssistantDelta = { delta -> onAssistantDelta(delta) }
                )
                // Flush any throttled tail, then persist the final text
                // once as the permanent bubble and retire the live one.
                streamingText = synchronized(streamBuffer) { streamBuffer.toString() }
                appendAssistant(reply)
            } catch (e: Exception) {
                // F5 error hygiene: the raw exception goes to AppLog;
                // the chat bubble is the friendly headline only.
                com.nshd.jarvis2.core.AppLog.e(
                    "Agent",
                    "chat send failed: ${e.message ?: "unexpected error"}",
                    e
                )
                appendAssistant(
                    "I couldn't reach the model (" +
                        FriendlyErrors.shortReason(e.message) +
                        "). The full error is in Settings → App Logs."
                )
            } finally {
                resetStream()
                sending = false
            }
        }
    }

    // F5.1: imePadding lifts the whole column — composer included —
    // above the keyboard. The floating-nav clearance is NOT baked in
    // here: it comes from the Scaffold padding in JarvisApp, which
    // drops to zero while the IME is visible (the nav hides), so the
    // IME inset is the only bottom padding while typing and the send
    // button sits fully visible directly above the keyboard.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        A11yWarningBanner(
            onFix = onOpenSettings,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)
        )
        // Header (F4): options menu · centered greeting + engine status · new chat.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(onClick = { headerMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Chat options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ChatOptionsMenu(
                    expanded = headerMenuOpen,
                    onDismiss = { headerMenuOpen = false },
                    onClear = { showClearConfirm = true },
                    onOpenVoice = onOpenVoice
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
                Text(
                    text = when {
                        hour < 12 -> "Good morning"
                        hour < 17 -> "Good afternoon"
                        else -> "Good evening"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                val sessionActive = voiceState.status != VoiceStatus.IDLE
                val engineLabel = when {
                    sessionActive && voiceState.mode == VoiceMode.UNIVERSAL ->
                        "Universal • ${voiceState.voiceName}"
                    sessionActive && voiceState.mode == VoiceMode.LIVE -> "Gemini Live"
                    settings.voiceEngine.uppercase() == "UNIVERSAL" -> "Universal"
                    else -> "Gemini Live"
                }
                val statusWord = if (sessionActive) "Active" else "Off"
                Text(
                    text = if (sending) "${settings.assistantName} is thinking…"
                    else "${settings.assistantName} • $engineLabel • $statusWord",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    // New chat = the clear flow, confirmed when there
                    // is a conversation to lose.
                    if (messages.isNotEmpty()) showClearConfirm = true
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New chat",
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
            itemsIndexed(messages) { _, msg ->
                AppearOnEntry {
                    MessageBubble(msg)
                }
            }
            if (sending) {
                item {
                    if (streamingText.isNotEmpty()) {
                        // Live bubble: the reply growing in real time.
                        MessageBubble(
                            UiMessage(fromUser = false, text = streamingText)
                        )
                    } else {
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
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Composer CARD (F4): glass surface holding the text field, the
        // mode pills, an options (+) button and a gradient send circle.
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message ${settings.assistantName}") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModePill(
                        selected = mode == ChatMode.JARVIS,
                        icon = Icons.Filled.Chat,
                        label = settings.assistantName
                    ) { mode = ChatMode.JARVIS }
                    Spacer(modifier = Modifier.width(8.dp))
                    ModePill(
                        selected = mode == ChatMode.CODE,
                        icon = Icons.Filled.Code,
                        label = "Code"
                    ) { mode = ChatMode.CODE }
                    Spacer(modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { composerMenuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "More actions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ChatOptionsMenu(
                            expanded = composerMenuOpen,
                            onDismiss = { composerMenuOpen = false },
                            onClear = { showClearConfirm = true },
                            onOpenVoice = onOpenVoice
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    val canSend = input.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .then(
                                if (canSend) {
                                    Modifier.background(
                                        brush = Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.secondary
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                                } else {
                                    Modifier.background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape
                                    )
                                }
                            )
                            .clickable { send() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (canSend) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
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

/** F4 mode pill inside the composer card: icon + label, selected = primaryContainer. */
@Composable
private fun ModePill(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content
            )
        }
    }
}

/** Shared chat-options dropdown (header Menu button + composer + button). */
@Composable
private fun ChatOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onOpenVoice: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Clear chat") },
            onClick = {
                onDismiss()
                onClear()
            },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
            }
        )
        DropdownMenuItem(
            text = { Text("Open Voice") },
            onClick = {
                onDismiss()
                onOpenVoice()
            },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Mic, contentDescription = null)
            }
        )
    }
}

/**
 * Pre-F2 history stored raw tool lines ("❌ open_app: No installed app
 * matched…"). Strip the emoji/tool plumbing for chip display.
 */
private fun cleanLegacyToolLine(raw: String): String {
    var text = raw.trim()
    for (prefix in listOf("🔧 ", "✅ ", "❌ ", "⚠️ ")) {
        if (text.startsWith(prefix)) {
            text = text.removePrefix(prefix)
            break
        }
    }
    return if (text.length <= 90) text else text.take(87).trimEnd() + "…"
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    if (msg.isTool) {
        // Tool activity = one small CENTERED status pill (F2), visually
        // distinct from conversation bubbles.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 340.dp)
            ) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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

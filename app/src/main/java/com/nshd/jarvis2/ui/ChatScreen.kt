package com.nshd.jarvis2.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.brain.ToolEvent
import com.nshd.jarvis2.brain.ToolEventKind
import com.nshd.jarvis2.hands.TaskState
import com.nshd.jarvis2.hands.TaskStoppedException
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import com.nshd.jarvis2.voice.UniversalVoice
import com.nshd.jarvis2.voice.VoiceMode
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class UiMessage(
    val fromUser: Boolean,
    val text: String,
    val caption: String? = null,
    val isTool: Boolean = false,
    /** MemoryDb chat_messages row id (0 = not persisted / not yet known). */
    val id: Long = 0L
)

/** #65/#66: actions offered by a chat bubble's long-press menu. */
private enum class BubbleAction { COPY, SPEAK, SAVE, SHARE, EDIT, REGENERATE }

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
    val clipboard = LocalClipboardManager.current

    // #65: lazy standalone TTS for the bubble "Speak" action.
    var bubbleTts by remember { mutableStateOf<TextToSpeech?>(null) }
    var pendingSpeak by remember { mutableStateOf<String?>(null) }
    // Transient hint line (save confirmations, dictation errors).
    var hint by remember { mutableStateOf<String?>(null) }
    // #66: list index of the user turn being edited (applied on send).
    var pendingEditIndex by remember { mutableStateOf(-1) }
    // #67: chat search state.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchPos by remember { mutableStateOf(0) }
    // #69: when the current stream started (tok/s label).
    var streamStartedAt by remember { mutableStateOf(0L) }
    // #72: inline dictation state (recognizer is main-thread only).
    var dictating by remember { mutableStateOf(false) }
    var dictRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var dictBase by remember { mutableStateOf("") }

    // #67: indices of messages matching the current search query.
    val trimmedQuery = searchQuery.trim()
    val searchMatches: List<Int> =
        if (searchOpen && trimmedQuery.isNotEmpty()) {
            messages.indices.filter { i ->
                !messages[i].isTool &&
                    messages[i].text.contains(trimmedQuery, ignoreCase = true)
            }
        } else {
            emptyList()
        }

    // #65/#72: release the standalone TTS + recognizer with the screen.
    DisposableEffect(Unit) {
        onDispose {
            bubbleTts?.let { tts ->
                runCatching { tts.stop() }
                runCatching { tts.shutdown() }
            }
            dictRecognizer?.let { rec -> runCatching { rec.destroy() } }
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(3_000)
            hint = null
        }
    }

    // #67: a new query jumps to its first match.
    LaunchedEffect(searchQuery, searchOpen) {
        if (searchOpen && searchMatches.isNotEmpty()) {
            matchPos = 0
            listState.animateScrollToItem(searchMatches[0])
        }
    }

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

    /** #69: rough tokens/sec for the live stream (≈4 chars per token). */
    fun currentTokPerSec(): Int {
        if (streamStartedAt <= 0L || streamingText.isEmpty()) return 0
        val elapsedSec = ((System.currentTimeMillis() - streamStartedAt) / 1000.0)
            .coerceAtLeast(0.5)
        return ((streamingText.length / 4.0) / elapsedSec).toInt()
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
                        isTool = stored.role == "tool",
                        id = stored.id
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
        val msg = UiMessage(fromUser = false, text = text, caption = caption)
        messages.add(msg)
        if (persist) {
            scope.launch(Dispatchers.IO) {
                val newId = db.addMessage("assistant", text)
                if (newId > 0) {
                    withContext(Dispatchers.Main) {
                        val idx = messages.indexOfFirst { it === msg }
                        if (idx >= 0) messages[idx] = messages[idx].copy(id = newId)
                    }
                }
            }
        }
    }

    fun appendToolEvent(event: ToolEvent) {
        // A new tool turn starts: the previous turn's streamed narration
        // is superseded by the tool activity + the upcoming final reply.
        // Chips are session-only UI — never written to chat history.
        if (event.kind == ToolEventKind.STARTED) resetStream()
        messages.add(UiMessage(fromUser = false, text = event.chipText, isTool = true))
    }

    /**
     * The one pipeline invocation path — used by send(), by #66
     * regenerate, and (via its text) by a pending edit's resend.
     * Shared pipeline: memory commands → quick phone commands → agent loop.
     */
    fun launchPipeline(text: String) {
        sending = true
        resetStream()
        streamStartedAt = System.currentTimeMillis()
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
            } catch (e: TaskStoppedException) {
                // #61/#69: a user stop is not an error — short reply only.
                appendAssistant("Stopped.")
            } catch (e: CancellationException) {
                // #69: Stop button / overlay abort cancels this job.
                appendAssistant("Stopped.")
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

    /**
     * v1.9.0 (#68): export the visible conversation as a .txt file
     * via the share sheet (FileProvider exposes cacheDir already).
     */
    fun exportConversation() {
        val exportable = messages.filter { !it.isTool && it.text.isNotBlank() }
        if (exportable.isEmpty()) {
            hint = "Nothing to export yet"
            return
        }
        val text = buildString {
            appendLine("Jarvis conversation — exported from the app")
            appendLine()
            exportable.forEach { msg ->
                append(if (msg.fromUser) "You: " else "${settings.assistantName}: ")
                appendLine(msg.text)
                appendLine()
            }
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val file = java.io.File(context.cacheDir, "jarvis-chat-export.txt")
                    file.writeText(text)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            putExtra(
                                android.content.Intent.EXTRA_SUBJECT,
                                "Jarvis conversation"
                            )
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(
                        android.content.Intent.createChooser(intent, "Export conversation")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }.getOrDefault(false)
            }
            hint = if (ok) "Conversation export opened" else "Export failed"
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        input = ""
        // #66 edit-and-resend: a pending edit REPLACES its turn now —
        // the edited user message and everything after it leave the UI
        // list AND MemoryDb history (real per-row deletes by id).
        if (pendingEditIndex in messages.indices) {
            val cut = pendingEditIndex
            pendingEditIndex = -1
            val removedIds = messages.drop(cut).map { it.id }.filter { it > 0 }
            while (messages.size > cut) messages.removeAt(messages.lastIndex)
            if (removedIds.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    removedIds.forEach { id -> db.deleteMessage(id) }
                }
            }
        }
        val userMsg = UiMessage(fromUser = true, text = text)
        messages.add(userMsg)
        scope.launch(Dispatchers.IO) {
            val newId = db.addMessage("user", text)
            if (newId > 0) {
                withContext(Dispatchers.Main) {
                    val idx = messages.indexOfFirst { it === userMsg }
                    if (idx >= 0) messages[idx] = messages[idx].copy(id = newId)
                }
            }
        }
        launchPipeline(text)
    }

    /** #66: re-run the pipeline on the last user text; the old reply stays. */
    fun regenerate() {
        if (sending) return
        val lastUser = messages.lastOrNull { it.fromUser && !it.isTool }?.text
            ?: return
        launchPipeline(lastUser)
    }

    /** #65: read a bubble aloud with a lazy standalone TTS (main thread). */
    fun speakText(text: String) {
        val existing = bubbleTts
        if (existing != null) {
            existing.stop()
            existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bubble-speak")
            return
        }
        pendingSpeak = text
        bubbleTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                pendingSpeak?.let { pending ->
                    bubbleTts?.speak(
                        pending, TextToSpeech.QUEUE_FLUSH, null, "bubble-speak"
                    )
                }
            }
            pendingSpeak = null
        }
    }

    /** #72: stop the inline dictation recognizer (main thread). */
    fun stopDictation() {
        dictating = false
        dictRecognizer?.let { rec -> runCatching { rec.stopListening() } }
    }

    /** #72: mic button — SpeechRecognizer writes into the composer. */
    fun toggleDictation() {
        if (dictating) {
            stopDictation()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            hint = FriendlyErrors.map(
                "Speech recognition is not available on this device"
            ).headline
            return
        }
        // A finished/wedged recognizer is replaced, never reused blindly.
        dictRecognizer?.let { old -> runCatching { old.destroy() } }
        dictRecognizer = null
        dictBase = input
        try {
            val rec = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            dictRecognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.let { heard -> input = joinDictation(dictBase, heard) }
                }

                override fun onResults(results: Bundle?) {
                    results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.let { heard -> input = joinDictation(dictBase, heard) }
                    dictating = false
                }

                override fun onError(error: Int) {
                    dictating = false
                    hint = FriendlyErrors.map(
                        "Speech recognition error ($error)"
                    ).headline
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    UniversalVoice.sttLanguageTag(settings.language)
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            dictating = true
            rec.startListening(intent)
        } catch (e: Exception) {
            dictating = false
            hint = FriendlyErrors.map("Speech recognition failed to start").headline
        }
    }

    /** #65/#66: one handler for every bubble-menu action. */
    fun onBubbleAction(action: BubbleAction, msg: UiMessage, index: Int) {
        when (action) {
            BubbleAction.COPY -> {
                clipboard.setText(AnnotatedString(msg.text))
                hint = "Copied"
            }

            BubbleAction.SPEAK -> speakText(msg.text)
            BubbleAction.SAVE -> {
                scope.launch(Dispatchers.IO) {
                    db.addFact(MemoryCategories.PERSONAL, msg.text)
                }
                hint = "Saved to Memory"
            }

            BubbleAction.SHARE -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, msg.text)
                }
                context.startActivity(
                    Intent.createChooser(shareIntent, "Share message")
                )
            }

            BubbleAction.EDIT -> {
                input = msg.text
                pendingEditIndex = index
                hint = "Editing — press send to replace that turn"
            }

            BubbleAction.REGENERATE -> regenerate()
        }
    }

    /** #67: jump to the next/previous search match (wraps around). */
    fun goToMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        val size = searchMatches.size
        val next = ((matchPos + delta) % size + size) % size
        matchPos = next
        scope.launch { listState.animateScrollToItem(searchMatches[next]) }
    }

    // #66: which bubbles may offer Edit / Regenerate.
    val lastUserIndex = messages.indexOfLast { it.fromUser && !it.isTool }
    val lastAssistantIndex = messages.indexOfLast { !it.fromUser && !it.isTool }

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
                    onOpenVoice = onOpenVoice,
                    onSearch = { searchOpen = true },
                    onExport = { exportConversation() }
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

        // #67: search bar — count + prev/next jump between matches;
        // matched bubbles render highlighted (see MessageBubble).
        if (searchOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search this chat") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (searchMatches.isEmpty()) {
                        "0/0"
                    } else {
                        "${matchPos + 1}/${searchMatches.size}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { goToMatch(-1) }) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Previous match"
                    )
                }
                IconButton(onClick = { goToMatch(1) }) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Next match"
                    )
                }
                IconButton(onClick = {
                    searchOpen = false
                    searchQuery = ""
                    matchPos = 0
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close search"
                    )
                }
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
            itemsIndexed(messages) { index, msg ->
                AppearOnEntry {
                    MessageBubble(
                        msg = msg,
                        highlight = index in searchMatches,
                        canEdit = index == lastUserIndex,
                        canRegenerate = index == lastAssistantIndex,
                        onAction = { action, m -> onBubbleAction(action, m, index) }
                    )
                }
            }
            if (sending) {
                item {
                    if (streamingText.isNotEmpty()) {
                        // Live bubble: the reply growing in real time,
                        // with the #69 tokens/sec mini-label under it.
                        Column {
                            MessageBubble(
                                msg = UiMessage(fromUser = false, text = streamingText),
                                menuEnabled = false
                            )
                            val tokPerSec = currentTokPerSec()
                            if (tokPerSec > 0) {
                                Text(
                                    text = "≈$tokPerSec tok/s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp, top = 2.dp)
                                )
                            }
                        }
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

        // #69: while a reply is running, an always-visible Stop row —
        // cooperative stop flag AND job cancellation together.
        if (sending) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    TaskState.requestStop()
                    TaskState.abort()
                }) {
                    Text(
                        text = "■ Stop",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                val tokPerSec = currentTokPerSec()
                if (tokPerSec > 0) {
                    Text(
                        text = "≈$tokPerSec tok/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Transient hint line (#65 save confirmations, #72 dictation errors).
        hint?.let { hintText ->
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

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
                            onOpenVoice = onOpenVoice,
                            onSearch = { searchOpen = true },
                            onExport = { exportConversation() }
                        )
                    }
                    // #72: inline dictation — fills the composer, never
                    // opens the Voice screen. Tap again to stop.
                    IconButton(onClick = { toggleDictation() }) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = if (dictating) {
                                "Stop dictation"
                            } else {
                                "Dictate"
                            },
                            tint = if (dictating) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
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
                    pendingEditIndex = -1
                    searchOpen = false
                    searchQuery = ""
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
    onOpenVoice: () -> Unit,
    onSearch: () -> Unit = {},
    onExport: () -> Unit = {}
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Search chat") },
            onClick = {
                onDismiss()
                onSearch()
            },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            }
        )
        DropdownMenuItem(
            text = { Text("Export conversation (.txt)") },
            onClick = {
                onDismiss()
                onExport()
            },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Share, contentDescription = null)
            }
        )
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

/** #72: append dictated text to what was already in the composer. */
private fun joinDictation(base: String, heard: String): String =
    if (base.isBlank()) heard else base.trimEnd() + " " + heard

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: UiMessage,
    highlight: Boolean = false,
    canEdit: Boolean = false,
    canRegenerate: Boolean = false,
    menuEnabled: Boolean = true,
    onAction: (BubbleAction, UiMessage) -> Unit = { _, _ -> }
) {
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
    // #65: long-press opens the bubble action menu; #67: a search
    // match renders with a highlighted container + tertiary border.
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(modifier = Modifier.widthIn(max = 320.dp)) {
            Box {
                Card(
                    modifier = if (menuEnabled) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true }
                        )
                    } else {
                        Modifier
                    },
                    shape = RoundedCornerShape(
                        topStart = JarvisDimens.radiusCard, topEnd = JarvisDimens.radiusCard,
                        bottomStart = if (msg.fromUser) JarvisDimens.radiusCard else 6.dp,
                        bottomEnd = if (msg.fromUser) 6.dp else JarvisDimens.radiusCard
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            highlight -> MaterialTheme.colorScheme.tertiaryContainer
                            msg.fromUser -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    border = if (highlight) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
                    } else {
                        null
                    }
                ) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (msg.fromUser) {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (highlight) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                }
                            )
                        } else {
                            MarkdownText(
                                content = msg.text,
                                textColor = if (highlight) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            menuOpen = false
                            onAction(BubbleAction.COPY, msg)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Speak") },
                        onClick = {
                            menuOpen = false
                            onAction(BubbleAction.SPEAK, msg)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Save to Memory") },
                        onClick = {
                            menuOpen = false
                            onAction(BubbleAction.SAVE, msg)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            onAction(BubbleAction.SHARE, msg)
                        }
                    )
                    if (canEdit) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onAction(BubbleAction.EDIT, msg)
                            }
                        )
                    }
                    if (canRegenerate) {
                        DropdownMenuItem(
                            text = { Text("Regenerate") },
                            onClick = {
                                menuOpen = false
                                onAction(BubbleAction.REGENERATE, msg)
                            }
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

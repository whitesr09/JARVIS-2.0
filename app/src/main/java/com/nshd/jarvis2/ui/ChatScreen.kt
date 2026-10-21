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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.JarvisSettings

private data class ChatMessage(val fromUser: Boolean, val text: String)
private enum class ChatMode { JARVIS, CODE }

/** Level 1 shell — the real provider brain (REST + failover, Code mode) lands in Level 2. */
@Composable
fun ChatScreen(settings: JarvisSettings) {
    var mode by remember { mutableStateOf(ChatMode.JARVIS) }
    var input by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                fromUser = false,
                text = "Hello ${settings.callsign}. This is the Level 1 chat shell — " +
                    "my provider brain, memory and Code mode arrive in Level 2. " +
                    "You can still type here to try the composer."
            )
        )
    }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                text = settings.assistantName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${settings.assistantName} • Brain arrives in Level 2",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                label = { Text(settings.assistantName) }
            )
            FilterChip(
                selected = mode == ChatMode.CODE,
                onClick = { mode = ChatMode.CODE },
                label = { Text("<> Code") }
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
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        messages.add(ChatMessage(fromUser = true, text = text))
                        val reply = if (mode == ChatMode.CODE) {
                            "Code mode is a shell in Level 1 — markdown rendering, syntax " +
                                "highlighting and the reasoning model land in Level 2."
                        } else {
                            "Brain lands in Level 2 — this is the Level 1 shell."
                        }
                        messages.add(ChatMessage(fromUser = false, text = reply))
                        input = ""
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (msg.fromUser) 18.dp else 4.dp,
                bottomEnd = if (msg.fromUser) 4.dp else 18.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (msg.fromUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (msg.fromUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

package com.nshd.jarvis2.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.nshd.jarvis2.brain.ChatMsg
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.core.BrainStore
import com.nshd.jarvis2.core.ScheduledAlarms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.0.0 "Brain Upgrade" settings section: everything Jarvis has
 * learned, in one manageable place — the custom wake phrase (#10),
 * the notification reader opt-in (#86/#50), learned macros
 * (#16/#17), custom quick commands, routines (#115) with their
 * triggers, scheduled message drafts (#51), and the model A/B test
 * (#80). All state is hoisted to the top of this composable (F4
 * rule); the lists re-read from [BrainStore] whenever [refresh]
 * ticks (any mutation below ticks it).
 */
@Composable
fun BrainUpgradeSection(
    settings: JarvisSettings,
    onSettingsChange: (JarvisSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }

    val macros = remember(refresh) { BrainStore.macros(context) }
    val customCommands = remember(refresh) { BrainStore.customCommands(context) }
    val routines = remember(refresh) { BrainStore.routines(context) }
    val scheduled = remember(refresh) { BrainStore.scheduled(context) }
    var notifEnabled by remember(refresh) {
        mutableStateOf(BrainStore.notifReaderEnabled(context))
    }

    // #80: model A/B state.
    val configs = remember {
        ProviderStore(context).orderedConfigs().filter { it.isConfigured }
    }
    var abPrompt by remember { mutableStateOf("") }
    var abIndexA by remember { mutableIntStateOf(0) }
    var abIndexB by remember { mutableIntStateOf(if (configs.size > 1) 1 else 0) }
    var abRunning by remember { mutableStateOf(false) }
    var abResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Brain Upgrade",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "What Jarvis has learned — commands, routines " +
                    "and schedules. Teach more by voice: \"when I say " +
                    "X, do Y\", \"save that as a command\", \"create " +
                    "routine …\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            // ── #10: custom wake phrase ──────────────────────────────
            OutlinedTextField(
                value = settings.customWakePhrase,
                onValueChange = { onSettingsChange(settings.copy(customWakePhrase = it)) },
                label = { Text("Custom wake phrase (optional)") },
                supportingText = {
                    Text(
                        "2–5 common English words, e.g. \"hey buddy\". " +
                            "It works alongside \"Hey Jarvis\" — the " +
                            "offline model can only hear words it knows, " +
                            "so common words work best."
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))

            // ── #86/#50: notification reader ─────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notification reader",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "\"Read my WhatsApp messages\" / \"who " +
                            "messaged me?\" — off by default; also " +
                            "needs Android's Notification Access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = notifEnabled,
                    onCheckedChange = { enabled ->
                        BrainStore.setNotifReaderEnabled(context, enabled)
                        notifEnabled = enabled
                    }
                )
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                AndroidSettings
                                    .ACTION_NOTIFICATION_LISTENER_SETTINGS
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Notification Access settings")
            }
            Spacer(modifier = Modifier.height(14.dp))

            // ── #16/#17: learned macros ──────────────────────────────
            BrainListTitle("Learned quick commands (macros)")
            if (macros.isEmpty()) {
                BrainEmptyLine(
                    "None yet — finish a task, then say \"save that " +
                        "as a command called …\"."
                )
            }
            macros.forEach { macro ->
                BrainRow(
                    title = "\"${macro.trigger}\"",
                    subtitle = "${macro.steps.size} recorded steps " +
                        "from \"${macro.sourceGoal}\" — replays with " +
                        "no model call; sends still confirm first.",
                    actionLabel = "Delete",
                    onAction = {
                        BrainStore.deleteMacro(context, macro.trigger)
                        refresh++
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            // ── Custom quick commands ────────────────────────────────
            BrainListTitle("Custom commands (\"when I say …\")")
            if (customCommands.isEmpty()) {
                BrainEmptyLine(
                    "None yet — say \"when I say movie time, open " +
                        "Netflix\" to teach one."
                )
            }
            customCommands.forEach { command ->
                BrainRow(
                    title = "\"${command.phrase}\"",
                    subtitle = "Runs: \"${command.target}\"",
                    actionLabel = "Delete",
                    onAction = {
                        BrainStore.deleteCustomCommand(context, command.phrase)
                        refresh++
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            // ── #115: routines ───────────────────────────────────────
            BrainListTitle("Routines")
            if (routines.isEmpty()) {
                BrainEmptyLine(
                    "None yet — say \"create routine morning: " +
                        "weather; read my calendar; play the news\"."
                )
            }
            routines.forEach { routine ->
                val nextTrigger = when (routine.trigger) {
                    "MANUAL" -> "ON_WAKE"
                    "ON_WAKE" -> "ON_APP_OPEN"
                    else -> "MANUAL"
                }
                val triggerLabel = when (routine.trigger) {
                    "ON_WAKE" -> "On wake"
                    "ON_APP_OPEN" -> "On app open"
                    else -> "Manual only"
                }
                BrainRow(
                    title = routine.name,
                    subtitle = "${routine.steps.size} steps • " +
                        "trigger: $triggerLabel (tap Trigger to change)",
                    actionLabel = "Delete",
                    onAction = {
                        BrainStore.deleteRoutine(context, routine.name)
                        refresh++
                    },
                    secondaryLabel = "Trigger",
                    onSecondary = {
                        BrainStore.saveRoutine(
                            context, routine.copy(trigger = nextTrigger)
                        )
                        refresh++
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            // ── #51: scheduled drafts ────────────────────────────────
            BrainListTitle("Scheduled messages")
            if (scheduled.isEmpty()) {
                BrainEmptyLine(
                    "None pending — say \"send a message to Mom " +
                        "saying I'll be late at 7 pm\"."
                )
            }
            scheduled.forEach { item ->
                val whenLabel = SimpleDateFormat(
                    "EEE d MMM, h:mm a", Locale.ENGLISH
                ).format(Date(item.fireAtMs))
                BrainRow(
                    title = "To ${item.contact} — $whenLabel" +
                        if (item.due) " (due)" else "",
                    subtitle = "\"${item.message}\"",
                    actionLabel = "Cancel",
                    onAction = {
                        BrainStore.removeScheduled(context, item.id)
                        ScheduledAlarms.cancel(context, item.id)
                        refresh++
                    }
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            // ── #80: model A/B test ──────────────────────────────────
            BrainListTitle("Model A/B test")
            if (configs.size < 2) {
                BrainEmptyLine(
                    "Configure two providers (Settings → API & " +
                        "Connection) to compare their chat models " +
                        "side by side."
                )
            } else {
                OutlinedTextField(
                    value = abPrompt,
                    onValueChange = { abPrompt = it },
                    label = { Text("Prompt to send to both models") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                BrainRow(
                    title = "Model A",
                    subtitle = "${configs[abIndexA].label} • " +
                        configs[abIndexA].chatModel,
                    actionLabel = "Change",
                    onAction = {
                        abIndexA = (abIndexA + 1) % configs.size
                    }
                )
                BrainRow(
                    title = "Model B",
                    subtitle = "${configs[abIndexB].label} • " +
                        configs[abIndexB].chatModel,
                    actionLabel = "Change",
                    onAction = {
                        abIndexB = (abIndexB + 1) % configs.size
                    }
                )
                Button(
                    onClick = {
                        val prompt = abPrompt.trim()
                        if (prompt.isBlank() || abRunning) {
                            // Nothing to run.
                        } else {
                        val configA = configs[abIndexA]
                        val configB = configs[abIndexB]
                        abRunning = true
                        abResults = emptyList()
                        scope.launch {
                            val results = withContext(Dispatchers.IO) {
                                val first = async {
                                    runCatching {
                                        configA.toProvider().chat(
                                            listOf(ChatMsg("user", prompt)),
                                            configA.chatModel
                                        )
                                    }
                                }
                                val second = async {
                                    runCatching {
                                        configB.toProvider().chat(
                                            listOf(ChatMsg("user", prompt)),
                                            configB.chatModel
                                        )
                                    }
                                }
                                listOf(
                                    "${configA.label} • ${configA.chatModel}" to
                                        first.await().getOrElse {
                                            "Failed: " +
                                                (it.message ?: "error")
                                        },
                                    "${configB.label} • ${configB.chatModel}" to
                                        second.await().getOrElse {
                                            "Failed: " +
                                                (it.message ?: "error")
                                        }
                                )
                            }
                            abResults = results
                            abRunning = false
                        }
                        }
                    },
                    enabled = !abRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (abRunning) "Asking both models…" else "Run A/B")
                }
                abResults.forEach { (label, reply) ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = reply,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun BrainListTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun BrainEmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun BrainRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            Spacer(modifier = Modifier.width(2.dp))
        }
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

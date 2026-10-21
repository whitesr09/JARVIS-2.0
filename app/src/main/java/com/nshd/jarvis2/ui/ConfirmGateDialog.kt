package com.nshd.jarvis2.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nshd.jarvis2.brain.tools.ConfirmGate

/**
 * Native UI for the Level 4 hard guardrails. Tool execution is suspended
 * in [ConfirmGate] until the user answers here; dismissing or timing out
 * (30 seconds) denies the action.
 */
@Composable
fun ConfirmGateDialog() {
    val pending by ConfirmGate.pending.collectAsState()
    val item = pending ?: return
    AlertDialog(
        onDismissRequest = { ConfirmGate.dismiss(item.id) },
        title = { Text("Confirmation needed") },
        text = { Text(item.description) },
        confirmButton = {
            TextButton(onClick = { ConfirmGate.complete(item.id, approved = true) }) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = { ConfirmGate.complete(item.id, approved = false) }) {
                Text("Deny")
            }
        }
    )
}

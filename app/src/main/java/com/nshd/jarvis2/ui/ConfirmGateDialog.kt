package com.nshd.jarvis2.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.brain.tools.ConfirmGate

/**
 * Native UI for the Level 4 hard guardrails. Tool execution is suspended
 * in [ConfirmGate] until the user answers here; dismissing or timing out
 * (30 seconds) denies the action.
 *
 * D2 treatment: warning icon in an error-tinted circle, the action in
 * bodyLarge, and a thin progress bar that drains over the same ~30 s
 * window the gate allows — when it empties, the gate has auto-denied.
 * (The bar is a visual mirror of the gate's timeout, driven locally
 * from when this prompt appeared.)
 */
@Composable
fun ConfirmGateDialog() {
    val pending by ConfirmGate.pending.collectAsState()
    val item = pending ?: return

    val remaining = remember(item.id) { Animatable(1f) }
    LaunchedEffect(item.id) {
        remaining.snapTo(1f)
        remaining.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 30_000, easing = LinearEasing)
        )
    }

    AlertDialog(
        onDismissRequest = { ConfirmGate.dismiss(item.id) },
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        title = { Text("Confirmation needed") },
        text = {
            Column {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(JarvisDimens.spaceLg))
                LinearProgressIndicator(
                    progress = { remaining.value },
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
                Text(
                    text = "No answer in 30 seconds counts as Deny.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { ConfirmGate.complete(item.id, approved = true) }) {
                Text(
                    text = "Allow",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { ConfirmGate.complete(item.id, approved = false) }) {
                Text("Deny")
            }
        }
    )
}

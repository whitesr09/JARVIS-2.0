package com.nshd.jarvis2.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.CrashLogger

/**
 * F4.1 diagnostics UI: the post-crash report screen and the session-log
 * dialog. Deliberately dependency-light (plain Material3 layout, no orb,
 * no session state) so the reporter itself can never crash, and every
 * load runs through CrashLogger's guarded readers.
 */

/**
 * Full-screen crash report shown on the first launch after a crash.
 * [onDismissAndClear] must delete the report + flag (CrashLogger.clearCrash)
 * and return the app to its normal UI.
 */
@Composable
fun CrashReportScreen(
    report: String,
    onDismissAndClear: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Jarvis crashed last time",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "The technical report below is what the developer needs — " +
                "a screenshot of it, or Copy / Share, is enough to find the bug.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(JarvisDimens.radiusCard),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            SelectionContainer {
                Text(
                    text = report,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { clipboard.setText(AnnotatedString(report)) },
                modifier = Modifier.weight(1f)
            ) { Text("Copy report") }
            OutlinedButton(
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Jarvis crash report")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(
                            Intent.createChooser(intent, "Share crash report")
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Share") }
        }
        TextButton(
            onClick = onDismissAndClear,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Dismiss & clear") }
    }
}

/**
 * Session-log viewer (Settings → App Identity & Integrity → Diagnostics):
 * the CrashLogger ring log — connect stage markers + non-fatal errors —
 * with a Copy button.
 */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val logText = remember {
        CrashLogger.sessionLogText(context).ifBlank {
            "No diagnostics yet — the log fills as voice sessions run."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics — session log") },
        text = {
            SelectionContainer {
                Text(
                    text = logText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(logText)) }
            ) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

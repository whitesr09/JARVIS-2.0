package com.nshd.jarvis2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The canonical conversation-language list (F4) — extracted from
 * SettingsScreen so Home and Settings share ONE list and ONE dialog.
 * "Auto" lets the session follow whatever language the user speaks.
 */
val LANGUAGES: List<String> = listOf(
    "Auto", "English", "Hindi", "Hinglish", "Telugu", "Tamil", "Kannada",
    "Malayalam", "Bengali", "Marathi", "Gujarati", "Bhojpuri"
)

/**
 * Language picker dialog (F4). Picking a row reports it via [onPick]
 * immediately but does NOT dismiss — the caller closes with Done /
 * outside tap, exactly like the pre-F4 Settings dialog behaved.
 */
@Composable
fun LanguagePickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversation Language") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LANGUAGES.forEach { lang ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(lang) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == lang,
                            onClick = { onPick(lang) }
                        )
                        Text(lang, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

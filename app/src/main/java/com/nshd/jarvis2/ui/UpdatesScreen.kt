package com.nshd.jarvis2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UpdatesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = "Updates & Architecture",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        InfoCard(
            title = "Level roadmap",
            body = "L1 Foundation — done: scaffold, theme engine, orb, " +
                "permissions dashboard.\n" +
                "L2 Brain — current build: provider layer + failover, encrypted API keys, " +
                "real chat + Code mode, Memory Core, permanent signing.\n" +
                "L3 Voice — Gemini Live loop, tap-to-talk, personas + 12 languages, silent execution + chime.\n" +
                "L4 Digital Hands — accessibility automation, deep intents, hardware controls, contact-aware calling & messaging.\n" +
                "L5 Vision + Overlay — MediaProjection targeting, floating avatar + 4-step Task HUD.\n" +
                "L6 Always-on — Vosk wake word, voice biometrics, battery-aware modes."
        )
        InfoCard(
            title = "Verified actions & recovery",
            body = "Every task follows one loop: plan → act → re-read the screen to verify → " +
                "recover or stop safely when the UI is unclear. Nothing is assumed to have worked."
        )
        InfoCard(
            title = "Dual-mode targeting",
            body = "Jarvis reads the Accessibility node tree first. When an app hides its UI " +
                "(Chrome, WebView, canvas), it falls back to MediaProjection screen frames at " +
                "1 FPS and targets by vision coordinates."
        )
        InfoCard(
            title = "Safety guardrails",
            body = "Confirmation is asked ONLY for hard guardrails: payments/purchases, " +
                "delete-for-everyone and file deletion, calls to numbers not in contacts, " +
                "ambiguous message recipients, and bulk actions (hard cap per run, stop if " +
                "the list stops moving). Everything else executes and verifies. Emergency " +
                "stop: tap the floating avatar, touch the screen, or say \"Jarvis stop\"."
        )
        InfoCard(
            title = "Privacy — zero backend",
            body = "No accounts, no external database, no tracking. Memory and voice " +
                "biometrics stay on this device. API keys live in encrypted storage and are " +
                "sent only to the provider you choose, over direct TLS."
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

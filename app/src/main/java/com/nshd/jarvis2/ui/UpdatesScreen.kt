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
                "L2 Brain — done: provider layer + failover, encrypted API keys, " +
                "real chat + Code mode, Memory Core, permanent signing.\n" +
                "L3 Voice — done: Gemini Live WebSocket loop (tap-to-talk orb, " +
                "barge-in, session resumption), fallback speech recognition + TTS via " +
                "the L2 brain, 5 Gemini voices + custom voice, silent execution + finish " +
                "chime, personas + 12 languages in the prompt.\n" +
                "L4 Digital Hands — done: accessibility screen reader and verified " +
                "actions, phone tools with native hard-guardrail confirmations, deep " +
                "intents, hardware controls, ContactsResolver, and WhatsApp/YouTube flows.\n" +
                "L5 Vision + Overlay — done: all 12 floating avatars (transparent, " +
                "no backdrop) with drag/edge-snap, tap-to-abort and the 4-step Task HUD; " +
                "MediaProjection screen share at ~1 FPS streaming into Gemini Live; " +
                "accessibility screenshots; see_screen + vision_tap tools for " +
                "pixel-level targeting.\n" +
                "L6 Always-on — done: offline Vosk wake word (\"Hey Jarvis\") " +
                "in a microphone foreground service — screen-off and locked, grammar-" +
                "constrained, battery-aware auto-pause (≤20% pauses, charging or " +
                ">30% resumes), mic arbitration with voice sessions, lock-screen " +
                "wake; voice-biometrics enrollment (3 samples stored on-device — " +
                "verification matching arrives in a later update).\n" +
                "All six levels built — Jarvis complete build."
        )
        InfoCard(
            title = "Design refresh — D1 (Warm Minimal · Purple)",
            body = "Current build: the D1 design refresh restyles the whole app on " +
                "the Warm Minimal direction — flat white cards on a calm " +
                "lavender-tinted background, very soft shadows, one friendly " +
                "typeface (Manrope) everywhere, and a token-driven theme system. " +
                "Purple is the new default theme (the old Sunset retired into " +
                "it); Light, Dark and Red follow the same calm flat treatment, " +
                "and a custom background hex now derives its own surface ladder. " +
                "D2 (per-screen restyle + transitions) and D3 (orb + HUD polish) " +
                "follow."
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
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
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

package com.nshd.jarvis2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

        RoadmapCard()
        InfoCard(
            title = "Design refresh — D2 (screens + motion)",
            body = "This build: every screen is restyled onto the D1 tokens — " +
                "one radius and spacing ladder, token cards everywhere, and a " +
                "shared status-pill language (permissions, providers, roadmap). " +
                "Navigation now crossfades with a soft directional slide, " +
                "chat and memory items rise in as they appear, the mic button " +
                "swells while Jarvis is listening, and the confirmation gate " +
                "shows its 30-second auto-deny as a draining bar. System " +
                "reduced-motion settings switch all of it to plain fades."
        )
        InfoCard(
            title = "Design refresh — D1 (Warm Minimal · Purple)",
            body = "Done: the D1 design refresh restyled the whole app on " +
                "the Warm Minimal direction — flat white cards on a calm " +
                "lavender-tinted background, very soft shadows, one friendly " +
                "typeface (Manrope) everywhere, and a token-driven theme system. " +
                "Purple is the new default theme (the old Sunset retired into " +
                "it); Light, Dark and Red follow the same calm flat treatment, " +
                "and a custom background hex now derives its own surface ladder."
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
            .padding(bottom = JarvisDimens.spaceMd)
    ) {
        Column(modifier = Modifier.padding(JarvisDimens.cardPadding)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(JarvisDimens.spaceSm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private enum class RoadmapStatus { DONE, CURRENT, UPCOMING }

private data class RoadmapEntry(
    val title: String,
    val body: String,
    val status: RoadmapStatus
)

private val ROADMAP = listOf(
    RoadmapEntry(
        "L1 Foundation",
        "Scaffold, theme engine, orb, permissions dashboard.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "L2 Brain",
        "Provider layer + failover, encrypted API keys, real chat + Code mode, " +
            "Memory Core, permanent signing.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "L3 Voice",
        "Gemini Live WebSocket loop (tap-to-talk orb, barge-in, session " +
            "resumption), fallback speech recognition + TTS via the L2 brain, " +
            "5 Gemini voices + custom voice, silent execution + finish chime, " +
            "personas + 12 languages in the prompt.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "L4 Digital Hands",
        "Accessibility screen reader and verified actions, phone tools with " +
            "native hard-guardrail confirmations, deep intents, hardware " +
            "controls, ContactsResolver, and WhatsApp/YouTube flows.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "L5 Vision + Overlay",
        "All 12 floating avatars (transparent, no backdrop) with drag/edge-snap, " +
            "tap-to-abort and the 4-step Task HUD; MediaProjection screen share " +
            "at ~1 FPS streaming into Gemini Live; accessibility screenshots; " +
            "see_screen + vision_tap tools for pixel-level targeting.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "L6 Always-on",
        "Offline Vosk wake word (\"Hey Jarvis\") in a microphone foreground " +
            "service — screen-off and locked, grammar-constrained, battery-aware " +
            "auto-pause (≤20% pauses, charging or >30% resumes), mic arbitration " +
            "with voice sessions, lock-screen wake; voice-biometrics enrollment " +
            "(3 samples stored on-device — verification matching arrives in a " +
            "later update).",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "D1 Design tokens",
        "Warm Minimal · Purple token system: Manrope type scale, flat theme " +
            "ladders for Purple/Light/Dark/Red + derived custom hex.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "D2 Screens + motion",
        "Per-screen restyle on the D1 tokens and the motion system — screen " +
            "transitions, list entry, shared status pills. This build.",
        RoadmapStatus.CURRENT
    ),
    RoadmapEntry(
        "D3 Orb + HUD polish",
        "Orb state palettes and amplitude curve, floating HUD restyle, final " +
            "accessibility + code-review QA pass.",
        RoadmapStatus.UPCOMING
    )
)

/** Roadmap as structured rows with status pills (D2) — one card, hairline dividers. */
@Composable
private fun RoadmapCard() {
    Card(
        shape = RoundedCornerShape(JarvisDimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = JarvisDimens.cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = JarvisDimens.spaceMd)
    ) {
        Column(modifier = Modifier.padding(JarvisDimens.cardPadding)) {
            Text(
                text = "Level roadmap",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
            ROADMAP.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(vertical = JarvisDimens.spaceMd)
                    )
                }
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = entry.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(JarvisDimens.spaceMd))
                    JarvisPill(
                        text = when (entry.status) {
                            RoadmapStatus.DONE -> "Done"
                            RoadmapStatus.CURRENT -> "Current"
                            RoadmapStatus.UPCOMING -> "Upcoming"
                        },
                        tone = when (entry.status) {
                            RoadmapStatus.DONE -> PillTone.SUCCESS
                            RoadmapStatus.CURRENT -> PillTone.PRIMARY
                            RoadmapStatus.UPCOMING -> PillTone.NEUTRAL
                        },
                        icon = when (entry.status) {
                            RoadmapStatus.DONE -> Icons.Filled.Check
                            RoadmapStatus.CURRENT -> Icons.Filled.PlayArrow
                            RoadmapStatus.UPCOMING -> Icons.Filled.Schedule
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(JarvisDimens.spaceMd))
            Text(
                text = "All six levels built — Jarvis complete build.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

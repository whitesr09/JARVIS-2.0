package com.nshd.jarvis2.ui

import androidx.compose.foundation.BorderStroke
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
            // 120dp bottom clearance so the last card never hides
            // behind the bottom bar (F2).
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp)
    ) {
        Text(
            text = "Updates & Architecture",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        InfoCard(
            title = "v1.3.1 — Voice crash hunt: crash capture + connect hardening",
            body = "Hotfix for the Voice screen closing while connecting. " +
                "The whole connect path is hardened: speech recognition " +
                "and text-to-speech are now strictly main-thread, the " +
                "wake-word recognizer must fully release the microphone " +
                "before a session starts (mic handshake), WebSocket " +
                "callbacks can no longer throw out of the socket thread, " +
                "audio start/stop is fully guarded, a partial wake-word " +
                "model download is detected and never loaded, and the " +
                "orb skips any frame with non-finite values instead of " +
                "crashing. New crash capture: if the app ever closes " +
                "unexpectedly, the next launch opens a crash report " +
                "screen with the full technical trace (Copy / Share), " +
                "and Settings → App Identity & Integrity → Diagnostics " +
                "shows the session log with connect stage markers."
        )
        InfoCard(
            title = "v1.3.0 — F4 redesign: canvas orb, themes + Hands-Free",
            body = "F4: Canvas orb (orb.html system), theme system per orb.html " +
                "(Light/Dark/Red/Sunset/Custom), UI redesign — floating nav, " +
                "Home/Chat/Settings refresh, Hands-Free guide screen, Voice crash fix."
        )
        RoadmapCard()
        InfoCard(
            title = "v1.2.0 — Task queue, background wake + F3 fixes",
            body = "The orb is now rendered by the new orb engine (ported " +
                "from the web simulator): smoother state-driven motion and " +
                "palette shifts on every surface it appears. Multi-step " +
                "requests now run as a visible task queue — the floating " +
                "HUD lists up to six queued/running/done tasks with the " +
                "current task's latest steps and an overall progress bar, " +
                "so you can see the whole plan, not just one goal. The " +
                "agent is more disciplined: it verifies each step before " +
                "moving on and asks for confirmation exactly where the " +
                "hard guardrails require it — nowhere else. Wake is now " +
                "fully in the background: \"Hey Jarvis\" starts listening " +
                "without opening the app, and tapping the floating avatar " +
                "toggles a background voice session the same way (the " +
                "app only opens from the HUD's own button, or from the " +
                "failure notification if listening couldn't start). " +
                "Connection Test is now v2: it checks the endpoint first, " +
                "then probes each chat model with a tiny ping — showing " +
                "per-model latency, skipping live-audio models, offering " +
                "a one-tap switch when your chosen model fails but " +
                "another works — and caches the results per provider."
        )
        InfoCard(
            title = "v1.1.0 — Universal Voice Engine + F2 fixes",
            body = "Voice no longer depends on one Gemini native-audio model. " +
                "The new Universal engine runs mic → text → your chat model → " +
                "voice, so it works with ANY model from ANY provider you configure; " +
                "Native Live stays for Gemini real-time audio models, and a failed " +
                "Live connection now switches to Universal instead of dying. " +
                "Settings → API & Connection can fetch the model list straight from " +
                "your API (LIVE AUDIO models are badged). Also in this round: the " +
                "Gemini Live language-code rejection is fixed (with an automatic " +
                "no-language retry), open_app finds installed apps reliably, the mic " +
                "button no longer glows red during normal sessions, tool results " +
                "render as small status chips instead of raw text, errors are " +
                "explained in plain words, the floating bubble hides over Jarvis " +
                "itself, and the Developer icons + Wake Word layout are repaired."
        )
        InfoCard(
            title = "Design refresh — D3 (orb + HUD polish)",
            body = "Done: the final design pass. The orb now breathes instead " +
                "of jittering (its response to your voice is smoothed), shifts " +
                "palette with its state — a tighter swirl while thinking, the " +
                "error colour on failure — and sits on the soft purple glow " +
                "Warm Minimal allows it. The floating Task HUD is no longer " +
                "theme-blind: it follows the app theme (including a custom " +
                "background hex) and re-skins live when you change themes. " +
                "A full accessibility and code-review QA pass closed the " +
                "refresh out."
        )
        InfoCard(
            title = "Design refresh — D2 (screens + motion)",
            body = "Done: every screen is restyled onto the D1 tokens — " +
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
    // F4 glass: flat surface, 24dp radius, hairline outlineVariant border.
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            "transitions, list entry, shared status pills.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "D3 Orb + HUD polish",
        "Orb state palettes and amplitude curve, theme-following floating " +
            "HUD, final accessibility + code-review QA pass.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "F2 Universal Voice",
        "Universal Voice Engine (any model, any provider), live model " +
            "discovery, Gemini Live language fix, and the on-device QA " +
            "fix round.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "F3 Task queue + background wake",
        "Orb engine port, visible task queue in the floating HUD, " +
            "agent discipline + confirmations, background wake and " +
            "overlay-tap voice (no app launch), Connection Test v2.",
        RoadmapStatus.DONE
    ),
    RoadmapEntry(
        "F4 Orb + UI redesign",
        "Canvas orb (orb.html system) with its theme system " +
            "(Light/Dark/Red/Sunset/Custom), floating pill navigation, " +
            "Home/Chat/Settings refresh, Hands-Free guide screen, " +
            "Voice crash fix. This build.",
        RoadmapStatus.CURRENT
    )
)

/** Roadmap as structured rows with status pills (D2) — one card, hairline dividers. */
@Composable
private fun RoadmapCard() {
    // F4 glass: flat surface, 24dp radius, hairline outlineVariant border.
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            Text(
                text = "v1.3.0 — F4 redesign: canvas orb, theme system, " +
                    "floating nav and the Hands-Free guide (task queue + " +
                    "background wake shipped at v1.2.0, Universal Voice " +
                    "Engine at v1.1.0, design refresh completed at v1.0.0). " +
                    "Voice crash-capture hotfix shipped at v1.3.1.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nshd.jarvis2.core.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Plan #104: live update check against GitHub Actions builds.
        val updateContext = LocalContext.current
        val updateScope = rememberCoroutineScope()
        val updateAvailable by UpdateChecker.updateAvailable.collectAsState()
        val updateStatus by UpdateChecker.statusLine.collectAsState()
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { UpdateChecker.check(updateContext) }
        }
        InfoCard(
            title = if (updateAvailable) {
                "⬆️ A newer build is available"
            } else {
                "Update check"
            },
            body = updateStatus
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                updateScope.launch {
                    runCatching {
                        updateContext.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(UpdateChecker.ACTIONS_URL)
                            )
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open GitHub Actions — download the latest APK")
        }
        Spacer(modifier = Modifier.height(16.dp))

        InfoCard(
            title = "v1.9.0 — More Powers: new hands, more messaging, smarter memory",
            body = "A big powers release. New tools: long-press, " +
                "double-tap, drag and pinch gestures, slider control, " +
                "read-this-page, clipboard get/set/copy/paste and an " +
                "undo for the last text edit. Messaging grew up: " +
                "WhatsApp can share your latest gallery photo and " +
                "reply in the chat that's already open, plus Instagram " +
                "DMs, Telegram, SMS and Gmail — every send still asks " +
                "you first, showing the recipient and the exact text. " +
                "Clock and calendar intents set alarms, timers and " +
                "events in one command; say \"note that …\" to save a " +
                "quick note. Voice remembers the language that worked, " +
                "respects Do-Not-Disturb, and gains a hold-to-talk " +
                "mode plus a wake-sensitivity slider with a recent-wake " +
                "log. Memory gets a review queue for auto-saved facts " +
                "and a duplicate-merge tidy; chat exports to .txt, " +
                "renders markdown tables and previews HTML. Twelve " +
                "new avatars, launcher shortcuts, a Quick Settings " +
                "tile, batched multi-tool steps, per-app settle " +
                "timing, and a Logs insights section with usage per " +
                "provider, slowest commands and a storage manager."
        )
        Spacer(modifier = Modifier.height(10.dp))

        InfoCard(
            title = "v1.8.0 — Panel, Orb & Feel: live panel, smarter orb, living Home",
            body = "The floating panel grew up: while a task runs collapsed " +
                "it shrinks to a slim pill (spinner, current step, % done) " +
                "instead of vanishing; expanded it shows the last 5 tasks " +
                "with their results, a live waveform while you talk to it, " +
                "an avatar face that works, celebrates and gets dizzy with " +
                "the plan, a haptic tick as each step lands, and quick " +
                "actions — Stop (now a REAL stop: the pipeline, the agent " +
                "loop and the deep intents all check a stop flag between " +
                "steps) and Pause/Resume for queued tasks. The panel also " +
                "remembers where you left it per app, follows every theme " +
                "including your custom colour, and stays readable on " +
                "light themes. The orb now pulses to Jarvis's own voice " +
                "on Live (not just your mic), tunes its particle quality " +
                "to your GPU and battery automatically, and takes a warm " +
                "accent in Girlfriend Mode. Home is alive: real weather " +
                "for your area, your next calendar event, and today's " +
                "gold price. Voice gets barge-in sensitivity (Low / " +
                "Normal / High), a device-voice picker with preview, " +
                "uncut long-reply reading with a Stop chip, and sessions " +
                "that resume themselves after a phone call. Chat gets " +
                "long-press actions (copy, speak, save to memory, " +
                "share), edit & resend, regenerate, full-history search, " +
                "a stop-generation button with a live tok/s readout, and " +
                "dictation right in the composer. Say \"forget …\" and " +
                "Jarvis removes matching memories and tells you exactly " +
                "what it dropped; correct a contact (\"not Mom, Amma\") " +
                "and the alias is learned. Hands got tougher: taps retry " +
                "centre → parent → edge, the keyboard no longer covers " +
                "the field being typed into, failed taps save an " +
                "annotated screenshot to the logs, system dialogs are " +
                "reported (never auto-approved), and Spotify/YouTube " +
                "stay warm after first use. Under the hood: a permission " +
                "health dashboard with brand-correct keep-alive links " +
                "for iQOO/Vivo, Samsung and Xiaomi, full backup & " +
                "restore (settings + memories + provider setups — keys " +
                "stay encrypted on-device), crash auto-recovery back to " +
                "your last screen, safe mode after repeated startup " +
                "crashes, a GitHub update checker with a badge on this " +
                "tab, and a one-time \"What's new\" card after updating."
        )
        Spacer(modifier = Modifier.height(12.dp))

        InfoCard(
            title = "v1.7.0 — Fast & Solid: Live fallback, offline voice, 50+ instant commands",
            body = "Live is resilient now: if your set Live model doesn't " +
                "answer, Jarvis tries the next native-audio models on your " +
                "API automatically (up to 3, fresh session each) and tells " +
                "you which one connected — and when Live fails, the Voice " +
                "screen's new \"Why?\" diagnosis explains it in plain " +
                "language with the Live log and the exact next step. A " +
                "weak-network guard notices slow Live setups and stops " +
                "making you wait the full 12 seconds. No internet? Voice " +
                "commands fall back to the on-device Vosk engine (download " +
                "the wake model once) instead of dying. 50+ instant " +
                "commands now skip the model entirely: flashlight, " +
                "screenshot, battery, time, date, volume, brightness, " +
                "media pause/next/previous, Wi-Fi/Bluetooth/airplane " +
                "panels, silent mode, lock screen, go home/back, call " +
                "<name>, stop — plus everything from before. Agent steps " +
                "run on your fastest tested model, screens are sent as " +
                "compact diffs instead of full dumps, and the hands got " +
                "smarter: a self-healing element finder, scroll-and-search, " +
                "per-app playbooks, and a vision fallback that kicks in " +
                "after two missed taps. A watchdog now warns you if " +
                "Android silently kills the accessibility service. And in " +
                "Settings: a one-tap diagnostics pack for sharing exactly " +
                "what broke (no keys, ever), plus a full self-test that " +
                "checks mic, speech, voice, hands, overlay, provider, " +
                "offline model and storage with a Fix link for each failure."
        )
        Spacer(modifier = Modifier.height(12.dp))

        InfoCard(
            title = "v1.6.0 — Message sending fixed, floating task panel, direct play routing",
            body = "WhatsApp messages actually send now. The send used to die " +
                "in a double confirmation: the send tool asked its approval, " +
                "then the tap guard asked AGAIN for the very same Send " +
                "button — often where no prompt could appear — so the tap " +
                "timed out and nothing sent. An approved send now carries a " +
                "one-shot permit for exactly that tap, sends are verified " +
                "twice (message field cleared AND the sent bubble on screen), " +
                "and the confirmation itself can now appear EVERYWHERE: in " +
                "the app, as a card inside the floating panel over any app, " +
                "or by simply saying \"yes\" / \"send it\" in a voice session. " +
                "The floating HUD is now a TASK PANEL like a pro assistant: " +
                "it pops up on its own when a task starts and shows the goal, " +
                "a live plan checklist (✓ done, spinner running, · waiting), " +
                "\"2/4 done · 50%\" with a progress bar, and the result line " +
                "when the task finishes — then tucks itself away. Play is " +
                "direct: \"play Rathinamo on Spotify\", \"play MrBeast " +
                "video\", \"watch One Name\" and plain \"play <song>\" " +
                "(Spotify by default) all skip the slow model loop and go " +
                "straight to the app — trailing periods from speech no " +
                "longer break them. The orb is bigger on Home and Voice, " +
                "and Home's orb now thinks, listens and speaks with live " +
                "states just like the Voice screen. Voice fixes too: the " +
                "recognizer is reused instead of rebuilt every turn " +
                "(150 ms restarts), the listening language is pinned — " +
                "\"Auto\" means English (India), shown on the Voice " +
                "screen — and if a transcript ever lands in the wrong " +
                "script, Jarvis notices and listens once more.\n" +
                "Plus a self-audit pass — 3 extra fixes under the hood."
        )
        Spacer(modifier = Modifier.height(12.dp))

        InfoCard(
            title = "v1.5.0 — Speed round: Spotify fast play, live task HUD, editor delete + code writing",
            body = "Tasks now show on the floating avatar's HUD while they run — " +
                "every command (chat, voice, quick commands, Live tool turns) " +
                "registers in the task list with a live step line; before, only " +
                "split multi-task requests ever registered, and Live voice tasks " +
                "bypassed it entirely, so the panel sat at \"0 of 0 done — idle\" " +
                "mid-task. \"Play X on Spotify\" is now ONE fast deep intent " +
                "(open → search → tap the song) instead of six slow agent steps, " +
                "with playback verified on screen. Hands are quicker everywhere: " +
                "settle checks poll every 100 ms with tight caps, typing is an " +
                "instant SET_TEXT insert at the cursor (it no longer erases the " +
                "field), and each step logs its model-vs-action time split. " +
                "QuickEdit gains real editing: say \"delete the last line\" / " +
                "\"clear everything\" and the new edit_text tool deletes in the " +
                "focused editor; ask for code in QuickEdit and the chat model " +
                "writes the COMPLETE code straight into the editor (insert_code), " +
                "with a short confirmation and preview here in chat.\n" +
                "Plus a full self-audit pass — 4 extra fixes under the hood."
        )
        Spacer(modifier = Modifier.height(12.dp))

        InfoCard(
            title = "v1.4.1 — Live fresh-session fix, faster girlier TTS, brand icons",
            body = "Native Live fix: the app used to save the Gemini Live " +
                "session-resumption handle and send it on every fresh " +
                "connect — but handles live only minutes, so Google " +
                "rejected each new session up front (\"session expired / " +
                "not found\", \"Invalid session handle\") before the model " +
                "was ever reached. Handles are now in-memory only: fresh " +
                "connects always start a clean session, the handle is " +
                "used solely for the in-session reconnect, a stale-handle " +
                "rejection retries once with a clean session before any " +
                "fallback, and the failure caption now says \"Live session " +
                "rejected (stale session)\" instead of \"model not found\" " +
                "(the Live log line shows \"connect: fresh session (no " +
                "handle)\"). Universal voice: new TTS speed (default " +
                "1.18×) and pitch (default 1.22× — girlier) sliders in " +
                "Settings → Voice with a Test voice button; changes apply " +
                "to the next reply live, and the first spoken chunk of a " +
                "reply now starts at the first clause, not the first full " +
                "sentence. Developer footer: WhatsApp, Instagram, " +
                "Telegram and GitHub now render the real brand glyphs as " +
                "local Compose vectors (previously Android drawables). " +
                "Also in this build: YouTube play waits for the results " +
                "to load and picks the first real video (skipping ads " +
                "and Shorts), tool results show their full sentence, a " +
                "model call that drops mid-task now retries on the same " +
                "connection instead of killing the task, mid-task " +
                "failures explain in plain words what did happen, and " +
                "the keyboard lifts the chat composer properly — the " +
                "send button stays fully visible and the bottom nav " +
                "hides while you type."
        )
        InfoCard(
            title = "v1.4.0 — Full app logs, Live diagnostics + live-only model guard",
            body = "New: a full log system — Settings → App Logs & " +
                "Diagnostics shows every app log live (voice, Gemini " +
                "Live socket, API calls, agent/tools, gates, wake word, " +
                "overlay, crashes) with level/tag filters, search, " +
                "pause, copy, share, save-to-Downloads and clear; logs " +
                "persist across restarts and API keys are redacted. " +
                "Live voice diagnostics: the real reason Native Live " +
                "fails (bad model, rejected key, gateway key, setup " +
                "timeout after 12s, unsupported language) is now " +
                "captured end-to-end and shown in the switch message — " +
                "\"Native Live unavailable (reason) — switched to " +
                "Universal\". Live-only model guard: a Live model set " +
                "as the chat model (e.g. gemini-3.8-live) is swapped " +
                "for a text model in chat/Universal instead of failing " +
                "with a raw HTTP 400 — and raw provider errors are never " +
                "spoken or shown as replies anymore. Universal voice " +
                "now prefers Google TTS and picks its best " +
                "network voice for your language; the Voice screen " +
                "names the device voice it uses. Orb: the square panel " +
                "behind the sphere is gone and the rings/orbit particles " +
                "stay inside the orb; the Voice header no longer wraps " +
                "and stale \"Didn't catch that\" hints clear properly."
        )
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

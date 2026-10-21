# JARVIS-2.0 — Master Build Plan

**Repo:** https://github.com/whitesr09/JARVIS-2.0
**Developer / Operator:** N S H D (GitHub @whitesr09)
**App name:** Jarvis (user-customizable, see §Branding)
**Reference:** "Jarvis Intelligence OS v3.1 Pro" — 17-page screenshot blueprint (PDF) + Gemini analysis + Nshd's locked preference answers (2026-10-21).
**Goal in his words:** a complete, hands-free, fully controllable mobile agent as an Android APK.

---

## 1. What Jarvis is

A local-first, zero-backend Android agent that:

- **Talks** — real-time bidirectional voice via Gemini Live WebSocket, plus REST tool-calling models.
- **Sees** — the screen, two ways (dual-mode targeting, §4).
- **Acts** — physically taps, types, scrolls and controls hardware through the Accessibility Service ("Digital Hands"), deep Android intents first, UI gestures as fallback.
- **Remembers** — an on-device Neural Memory Bank (Room/SQLite), linked to Android contacts.
- **Lives on screen** — a floating overlay avatar + 4-step Task Progress HUD while it works inside other apps.
- **Stays private** — no accounts, no external database, API keys in EncryptedSharedPreferences, direct TLS connection device → AI provider.

## 2. Locked preferences (Nshd's answers, 2026-10-21)

1. **Platform:** Native Android APK only. Developed on GitHub (CI builds the APK), built alongside Zara. **First step: a live interactive web simulator replica** to test UI + voice loop before native build.
2. **Brain:** Hybrid — Gemini Live WebSocket (voice + vision) **and** REST tool-calling (UI actions). User adds their own API key (with a Test Connection button) and picks the model, like the reference app. Also support **OpenAI-compatible providers** — Groq, FreeLLMAPI, OpenRouter, custom gateway URL. Free-tier first: **automatic failover to the next best available model** when an endpoint errors / rate-limits.
3. **Code mode:** The `<> Code` pill switches system prompt **and** UI (markdown + syntax highlighting + copy buttons) **and** routes to a deeper reasoning model, with the same failover chain.
4. **Device control:** **Dual-mode targeting loop** in V1 spirit — deep interactions first, more primitives in later updates. Safety = **confirmation only for hard guardrails** (§5), everything else executes directly.
5. **Wake word:** **Both systems, switchable in Settings** — always-on wake phrase ("Hey Jarvis") in a foreground service (works screen-off/locked) OR tap-to-talk on the orb / floating avatar. User chooses based on battery + preference.
6. **Voice biometrics:** Enroll 3 samples ("Hey Jarvis, wake up"), Strict / Balanced / Relaxed sensitivity, live match test, reset/re-enroll. (Implementation choice: §6.)
7. **Silent Execution:** Mutes task narration, HUD shows progress, **subtle chime when a task finishes**.
8. **Floating avatar:** Single-tap = toggle mic listening, or pause/abort the running task. Double-tap = expand/collapse the 4-step Task HUD (Goal, Recent Steps, Running Indicator, Progress). Drag & release = snap to nearest edge (L/R). **All 12 avatars** (Alien, Blob, Cat, Circle, Cloud, Droid, Drop, Flower, Ghost, Hexagon, Mech, Pebble) as crisp designed vector assets with working/speaking states.
9. **Memory Core:** As in reference — automatic fact extraction via tool calls (`save_memory` / `search_memory`) **and** manual add/edit/delete, categories Personal / Preferences / Contacts / Routines, search + filter chips, JSON backup/restore. **Linked to ContactsContract** — aliases resolve ("Call Mom", "WhatsApp Rahul"), ambiguity asks.
10. **Personas:** Priority order — **Girlfriend Mode first** (jealous, clingy, flirting, attached, loving, angry, romantic, sarcastic, mood swings), then **Jarvis Pro** (witty/strategic default). (Reference also lists Pari/Kabir/TechGPT X — later updates.) **All 5 Gemini voices** (Aoede, Kore, Fenrir, Puck, Charon) + **option to add a custom voice model**. **All 12 languages** (Auto, English, Hindi, Hinglish, Telugu, Tamil, Kannada, Malayalam, Bengali, Marathi, Gujarati, Bhojpuri).
11. **Orb:** Professional reactive particle orb — dynamic to audio amplitude: idle breathing, listening pulse, speaking waveform, thinking swirl. (Implementation choice: §7.)
12. **Themes:** Light, Dark, Red, Sunset (default, as screenshots) + custom background via `#RRGGBB` hex picker + quick swatches.
13. **Branding:** App name **Jarvis** by default, with a proper in-app system to rename the assistant. The AI addresses the user as **Nishad** (customizable callsign). Developer section (in-app, with icons + links):
    - Email: nishadahammed081@gmail.com
    - WhatsApp: +91 8590455801 — https://wa.me/+918590455801
    - Instagram: @__nshd.__ — https://www.instagram.com/__nshd.__
    - Telegram: @nshd_0 — https://t.me/nshd_0
    - GitHub: @whitesr09 — https://github.com/whitesr09

## 3. Decisions Zara made where he said "choose the best"

| Question | Decision | Why |
|---|---|---|
| Framework | **100% Native Kotlin + Jetpack Compose** | Only native gives direct, reliable AccessibilityService, MediaProjection, WindowManager overlay, foreground services. Flutter/RN bridges would fight every core feature. Reuses his StudyFlow / NUR-M3 / Gemini-app experience. |
| Build pipeline | GitHub repo + **GitHub Actions CI** builds debug APK (his proven workflow from Gemini-FreeLLM-Android: JDK 17, Gradle 8.14.3, AGP 8.13, Kotlin 2.2.20, SDK 35, min SDK 26) | He is phone-only; local VM builds are impossible (Java TCP blocked). CI is his established pattern. |
| Brain routing | **Provider layer**: `AiProvider` interface → GeminiLiveProvider (WebSocket), OpenAiCompatibleProvider (REST: Groq / FreeLLMAPI / OpenRouter / custom), GeminiRestProvider. **Hedged failover chain** ordered by user preference, auto-advance on 429/5xx/timeout, status shown in UI | Directly reuses the hedged Auto-model failover he already shipped in Gemini-FreeLLM-Android PR #9. Free-tier survival = failover, not one key. |
| API key storage | EncryptedSharedPreferences, per-provider keys, **Test Connection & Latency** button (ping + tiny completion) | Matches reference Page 10; keys never leave the device except to the chosen provider. |
| Model names | **Never hard-coded.** Model field is user-editable text + presets list. Reference names a preview model ("gemini-3.1-flash-live-preview") that may not exist for his key — presets start from Gemini Live native-audio models and the field can be changed without an app update. | Preview model names churn; hard-coding = broken app. |
| Wake word engine | **sherpa-onnx Keyword Spotting on-device** (later native level), with tap-to-talk shipped first | Fully offline, tiny battery vs. looping cloud SpeechRecognizer, works locked. Android's `VoiceInteractionService`/hotword is OEM-restricted — not reliable on Vivo/iQOO. |
| Voice biometrics | **On-device speaker embeddings (sherpa-onnx / ECAPA-style)**, staged: enrollment UI + phrase/fingerprint check first, real embedding cosine-match when the wake-word engine lands | True speaker verification needs an embedding model; a pitch check alone is security theatre. Honest staging, same UX from day one. |
| Orb (native) | **Compose Canvas particle system** (~600 particles, amplitude-driven states) | Native, 60fps, no WebView/WebGL weight, theme-tintable. Web simulator uses Canvas 2D with the same state machine so what he tests is what ships. |
| Screen vision fallback | MediaProjection capture → **1 FPS JPEG** frames → Gemini (Live or REST vision) for coordinate targeting when the accessibility tree is blind (Chrome/WebView/canvas UIs) | Exactly the reference's Page-6 diagnostic rule. 1 FPS keeps free-tier quota + battery sane. |

## 4. Architecture (native)

```
app (com.nshd.jarvis2 — package TBD at scaffold, keep neutral: com.nshd.jarvis)
├── ui/            Home (orb), Chat (+Code mode), Settings, Updates, Memory, Permissions,
│                  Avatars, Themes, Personas, ApiConnection, VoiceEnroll
├── brain/         AiProvider interface, GeminiLive WS client, OpenAI-compatible REST client,
│                  FailoverRouter, ToolRegistry, SystemPromptBuilder (persona+language+callsign)
├── hands/         JarvisAccessibilityService — node-tree actions: clickByText/Id, tap(x,y),
│                  swipe, typeText, back/home/recents/notifications; ScreenReader (tree dump)
├── vision/        MediaProjection service — frame capture 1 FPS, coordinate targeting
├── overlay/       FloatingOverlayService — avatar, drag+edge snap, tap/double-tap, Task HUD (4-step)
├── voice/         WakeWordService (sherpa-onnx KWS), VoiceBiometrics, TTS, chime player
├── memory/        Room DB (facts, categories), MemoryTools, ContactsResolver (ContactsContract)
├── hardware/      Torch, AudioManager volume/mute, brightness, Bluetooth/Wi-Fi panels, AppLauncher,
│                  Caller (cellular ACTION_CALL vs WhatsApp call — permission-aware)
└── core/          EncryptedPrefs (keys, settings), PermissionDashboard (11 authorizations), ResultVerifier
```

**Agent loop (every task):** hear/read command → brain plans tool calls → execute one action → **verify result** (re-read screen / check state) → recover or stop safely if UI is unclear → report via voice + HUD. Max-steps per task with a visible stop (avatar tap / user touches screen).

**Deep intents before tapping (V1):** launch app by package, WhatsApp chat via `wa.me`/deep link, YouTube search intent, ACTION_CALL, Settings panels — UI automation only when no intent exists.

## 5. Safety guardrails — confirmation ONLY for these

- Sending a payment / any UPI or purchase action
- Delete-for-everyone (WhatsApp), deleting files/photos/messages
- Placing a call to a number **not** in contacts (in-contacts calls execute)
- Sending a message whose recipient resolved ambiguously (multiple contact matches)
- Bulk actions (Instagram/WhatsApp lists): hard cap per run, stop if the list stops moving, end-of-list evidence required (reference Page 6)
- Everything else: execute, verify, narrate in HUD. Emergency stop: tap floating avatar, touch screen during automation, or say "Jarvis stop".

## 6. Build sequence

### Step 0 — Web simulator replica (FIRST, per his instruction)
Single-page interactive replica (HTML/Canvas) in a phone frame, testable on his iQOO in the browser:
- Home with reactive particle orb (mic amplitude via getUserMedia, with fallback animation), status pill, weather card, quick tiles
- Chat screen with Jarvis / `<> Code` pills (code mode = markdown + highlighted code blocks + copy)
- Full Settings replica: personas (Girlfriend first, Jarvis Pro), 5 voices + custom voice field, 12 languages, API & Connection panel (key input, provider presets Groq/FreeLLM/OpenRouter/Gemini, **real Test Connection for OpenAI-compatible REST** from the browser; Gemini Live WS test = status note, full WS runs native), Memory Core (working, localStorage), Permissions dashboard (visual), Avatars grid (12, selectable, floating demo + HUD), Themes (4 + custom hex), wake-word mode switch, silent-execution toggle + chime
- Floating avatar + 4-step HUD demo running a scripted task ("Open WhatsApp and message…")
- Developer section with his links/icons
Purpose: lock look, feel and flows with him before native code. His feedback rounds happen here — cheap and fast.

### Native levels (after simulator sign-off)
- **L1 Foundation:** Gradle/CI scaffold, theme engine (4+custom), Home orb (Canvas particles), bottom nav (Home/Chat/Settings/Updates + raised mic), Permissions dashboard (all 11 from reference Page 12), Developer section. CI green APK.
- **L2 Brain (REST):** Provider layer + failover, API key settings + test, Chat (normal + Code mode w/ markdown/highlight/copy + reasoning-model route), Memory Core (Room + tools + manual CRUD + backup/restore).
- **L3 Voice:** Gemini Live WebSocket session, tap-to-talk orb loop, TTS voices (5 + custom), Silent Execution + chime, personas + 12 languages wired into prompt builder.
- **L4 Digital Hands:** AccessibilityService full action set, deep intents, verified action loop + recovery, hardware controls, ContactsResolver + calling/messaging flows with §5 guardrails.
- **L5 Vision + Overlay:** MediaProjection 1 FPS fallback targeting, FloatingOverlayService (12 avatars, drag/snap, tap/double-tap, 4-step HUD), always-on vs while-working modes.
- **L6 Always-on:** WakeWordService (sherpa-onnx KWS, screen-off), voice biometrics (embedding verification, 3-sample enroll, sensitivity), battery-aware mode switching (auto tap-only below a battery % he sets).

## 7. Honest risks / unknowns

- **Gemini Live from a browser** is restricted (key exposure + WS auth) — the simulator tests UI/flows and REST providers for real; the Live voice loop is fully testable only in the native app. The simulator will say so instead of faking it.
- **Free-tier quotas** will bite during heavy testing — the failover chain across providers is the mitigation, not a cure.
- **iQOO/Vivo background killing** — overlay + foreground services need the OEM battery whitelist (already a tile in the Permissions dashboard, reference Page 12).
- **Wake-word + biometrics accuracy** on-device models are good, not perfect; Balanced sensitivity is the recommended default.

---

## Addendum (2026-10-21) — Reference source audit

**Reference:** a public open-source JARVIS Android tutorial series (a multi-part set of self-contained, ready-to-run Android Studio projects: Kotlin 2.0.20, Gradle 8.9, Android 8.0+) was audited part-by-part as the engineering reference behind the v3.1 Pro reference app. The final part is cumulative (the production engine).

| Part | Contents | Maps to our level |
|---|---|---|
| 1 | Gemini Live Bidi WebSocket, PCM audio streaming, Canvas 3D orb | L3 voice + orb (L1 UI) |
| 2 | **Vosk offline hotword manager**, foreground standby service (incl. RESEARCH.md) | L6 wake word |
| 3 | Accessibility engine, automation commands, Room Memory DB | L4 hands + L2 memory |
| 4 | Ultra low-latency audio pipeline, natural barge-in / turn-taking | L3 voice polish |
| 5 | WhatsApp & YouTube automations, advanced accessibility, UI polish | L4 automations |
| 6 | Real-time screen-sharing vision & multimodal reasoning (MediaProjection) | L5 vision |
| 7 | Agentic workflows, **17 floating overlays**, sound FX, production engine | L5 overlay + agent loop |

**Changes to our earlier decisions:**
- **Wake word: Vosk offline hotword is now the primary engine** (proven in the reference series, Parts 2–3), replacing the sherpa-onnx plan. Fully offline, foreground-service based.
- **Native strategy: audit-first, not from-scratch.** Before writing each native level, study the matching reference part's source (esp. the final part) and adopt its proven patterns/engines, then extend with our additions (multi-provider failover, Girlfriend persona, themes, custom branding, safety guardrails, Code mode). This cuts most of the "unknown" risk in §7.
- Pre-built APKs for the reference parts exist under that project's Releases — Nshd can install the final part's APK on his iQOO today to feel the reference app before we build.

**Branding decision (updated 2026-10-21):** JARVIS-2.0 ships under the developer's own branding only — the app and this repo carry a single "Developed by N S H D" credit and no third-party tutorial attribution. (The original mandatory-attribution plan recorded here was superseded by the developer's explicit decision in Fix Round F1.)

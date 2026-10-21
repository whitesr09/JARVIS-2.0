# JARVIS-2.0

**Jarvis** — a local-first, hands-free, fully controllable mobile agent for Android, developed by **N S H D**.

Jarvis talks (real-time voice), sees your screen (accessibility tree + screen-vision fallback), and acts for you — tapping, typing, scrolling and controlling hardware — while a floating avatar and task HUD show exactly what it's doing. No accounts, no external backend: your API keys, memory and voice stay on your device.

> **Current level: L1 — Foundation.** Gradle/CI scaffold, theme engine (Sunset / Light / Dark / Red + custom hex), animated particle-orb home, chat shell, settings with operator identity, and the full 11-authorization permissions dashboard. The brain, voice and automation land level by level (see roadmap).

## Build the APK (GitHub Actions)

Every push to `main` builds a debug APK in CI — no local Android Studio needed:

1. Open the repo's **Actions** tab → **Build JARVIS-2.0 APK**.
2. Wait for the run to go green, open it, and download the **`jarvis-2.0-apk`** artifact.
3. Unzip it and install `app-debug.apk` on your phone (allow "install from browser/files" once if Android asks).

You can also run the workflow manually from the Actions tab (**Run workflow**).

## Phone setup (first run)

1. Install and open **Jarvis**.
2. Go to **Settings → System Permissions** (or the **Screen** tile on Home).
3. Grant what you want Jarvis to use — Microphone, Notifications, Contacts, and the special pages for **Display Over Other Apps**, **Accessibility Service** (enable *Jarvis Digital Hands*), **Background Battery** (important on Vivo/iQOO so the system doesn't kill Jarvis), and **Modify System Settings**.
4. Personalize in **Settings**: assistant name (default *Jarvis*), what it calls you (default *Nishad*), persona, language, theme.

## Roadmap

| Level | Scope |
|---|---|
| **L1 Foundation** *(current)* | Scaffold + CI, theme engine, orb home, bottom nav with raised mic, chat shell, settings, permissions dashboard, developer section |
| **L2 Brain** | Provider layer (Gemini + Groq / FreeLLMAPI / OpenRouter) with free-tier failover, API key settings + connection test, real chat + Code mode (markdown/highlight/copy), Memory Core |
| **L3 Voice** | Gemini Live WebSocket voice loop, tap-to-talk orb, TTS voices + custom voice, personas + 12 languages, silent execution + finish chime |
| **L4 Digital Hands** | Full accessibility automation, deep intents, verified action loop + recovery, hardware controls, contact-aware calling/messaging with hard safety guardrails |
| **L5 Vision + Overlay** | MediaProjection 1 FPS vision fallback, floating avatar (12 designs, drag/edge-snap, tap/double-tap) + 4-step Task HUD |
| **L6 Always-on** | Vosk wake word (screen-off), voice biometrics (3-sample enroll), battery-aware mode switching |

Full design: see [MASTER-PLAN.md](MASTER-PLAN.md). A clickable pre-build replica lives in [simulator/](simulator/).

## Developer

**N S H D**

- Instagram: https://www.instagram.com/__nshd.__
- WhatsApp: https://wa.me/+918590455801
- Telegram: https://t.me/nshd_0
- Email: nishadahammed081@gmail.com
- GitHub: https://github.com/whitesr09
- Phone: +91 8590455801

## Credits

Reference design & tutorial: **Tech GPTx** — https://github.com/techgptx/Playlist
Credit to Tech GPTx for the JARVIS AI Android source code / tutorial that inspired this design.

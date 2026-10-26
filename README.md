# JARVIS-2.0

**Jarvis** — a local-first, hands-free mobile agent for Android, developed by **N S H D**.

Jarvis combines real-time voice, AI-provider integrations, on-device memory, accessibility-based actions, screen-vision support, a floating avatar/task HUD, and an optional offline wake word. Features depend on provider configuration, Android permissions, device capabilities, and background-execution restrictions.

> **Current app version: v2.0.3.** This repository's current implementation includes a provider-based AI brain, Memory Core, Gemini Live voice, Digital Hands accessibility automation, screen vision, floating overlay/task HUD, and optional Vosk wake-word recognition. A successful CI build verifies compilation and configured build tasks; it does not guarantee every live voice or device-automation flow works on every phone.

## Build the APK (GitHub Actions)

1. Open the repository's **Actions** tab: <https://github.com/whitesr09/JARVIS-2.0/actions>.
2. Open the latest successful **Build JARVIS-2.0 APK** run.
3. Download the artifact attached to that run and extract it.
4. Check the APK type and signing certificate before installing. A debug APK and a permanently signed release APK are not interchangeable for upgrades if their signing certificates differ.

You can also run the workflow manually from the Actions tab when the workflow supports manual dispatch. Release builds should only be treated as release-ready after the signing configuration and artifact have been verified.

## Phone setup

1. Install and open Jarvis.
2. Open **Settings → System Permissions**.
3. Grant only the permissions needed for the features you intend to use: microphone, notifications, contacts, overlay, accessibility, and screen capture.
4. Review Android's battery/background restrictions if you enable always-on wake-word listening. Some manufacturers may still restrict background services.
5. Configure an AI provider and verify its connection before testing voice or agent actions.

Accessibility and screen-capture features can expose sensitive on-screen information or perform actions in other apps. Keep them disabled when not needed, review each action's confirmation behavior, and never share diagnostic logs without checking them for private information.

## Development and verification

- **Primary branch:** `main`
- **Current app version:** `2.0.3` (see `app/build.gradle.kts`)
- **Language/UI:** Kotlin, Jetpack Compose
- **JDK:** 17
- **Android:** minimum SDK 26; compile/target SDK 35 in the current Gradle configuration
- **Build workflow:** GitHub Actions

Use a feature branch for changes. For each change set, run the available Gradle tests and APK build, inspect the generated artifact, and perform device-level smoke tests for features that depend on Android services, permissions, providers, audio, or accessibility.

See [PHASED-IMPROVEMENT-PLAN.md](PHASED-IMPROVEMENT-PLAN.md) for the staged hardening roadmap and acceptance criteria. The interactive pre-build simulator lives in [simulator/](simulator/).

## Developer

**N S H D**

- Instagram: <https://www.instagram.com/__nshd.__>
- WhatsApp: <https://wa.me/+918590455801>
- Telegram: <https://t.me/nshd_0>
- Email: nishadahammed081@gmail.com
- GitHub: <https://github.com/whitesr09>

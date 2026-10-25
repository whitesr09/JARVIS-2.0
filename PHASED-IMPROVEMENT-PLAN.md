# JARVIS-2.0 — Five-Phase Improvement Plan

Baseline: v2.0.3 (e2ee360fa35120b0cb94266b62694837cdf3a8a9)
Working branch: improvement/phase-1-release-hardening
Target: keep main stable; deliver each phase as a reviewable change set and require CI plus device smoke tests where applicable.

## Phase 1 — Release hardening and truthful diagnostics
- Keep README/version/build instructions aligned with Gradle and the actual APK.
- Identify builds by source commit SHA rather than comparing CI timestamps to Android's install timestamp.
- Make update checks retryable after network/API failure and avoid reporting an update when the installed build identity is unknown.
- Replace hard-coded Voice replies PASS with a status that distinguishes a capability/configuration check from a real audio round trip.
- Remove plaintext API-key fallback; surface secure-storage initialization failure without silently writing credentials unencrypted.
- Require explicit release-signing verification in release workflows; never mistake a debug artifact for a release.
- Add tests for update response parsing, unknown build identity, HTTP failures, secure-storage failure behavior, and diagnostic statuses.

Acceptance: Gradle unit tests and APK build pass; no API key is written to plaintext preferences; update status never claims a version comparison it cannot prove; README links resolve.

## Phase 2 — Provider, streaming, and agent correctness (in progress)

Implemented on the working branch:
- Chat failover now buffers provider output and only publishes the completed successful reply; partial output from a failed provider is discarded.
- Agent model-call retries now buffer deltas per attempt, so a transient failure cannot leak partial text or duplicate output on retry.
- Caller-supplied tool-step budgets are clamped to 1..HARD_STEP_CAP before the existing progress-based extension logic.
- Model-supplied tool calls are validated locally for known tool names, required arguments, schema types, enum values, unexpected fields, and bounded JSON/string sizes before any phone-side dispatch.
- Non-streaming chat, model-discovery, and connection-test response bodies are capped at 1 MiB; streaming responses are capped at 2 MiB of line content.
- Added unit tests for valid arguments, missing required values, unknown tools/fields, invalid enums, wrong types, and oversized payloads.

Still required before Phase 2 acceptance:
- Define provider capabilities and supported modalities explicitly.
- Add deterministic fake-provider tests for timeout, cancellation, malformed response, partial stream, and fallback behavior.
- Make task/turn IDs explicit and isolate state between concurrent requests.
- Add deterministic fake-provider tests for timeout, cancellation, malformed response, partial stream, and fallback behavior.

Acceptance: provider tests cover all failure paths; no duplicate tool execution or mixed-provider response; user-visible failures are actionable.

## Phase 3 — Voice, wake word, and lifecycle reliability
- Make audio-session ownership explicit across tap-to-talk, Gemini Live, wake word, and speech recognition.
- Add reconnect/backoff with a hard retry limit and clear user-facing state.
- Ensure microphone and recognizer resources release on cancellation, service stop, permission revocation, and process recreation.
- Add battery-aware wake-word modes and clear OEM background-restriction guidance.
- Report voice round-trip checks as verified only after a real session produces valid audio.
- Add lifecycle tests and a manual device matrix for screen-off, Bluetooth, interruptions, and network changes.

Acceptance: no stuck microphone/recording state; bounded recovery; voice tests honestly distinguish simulated, cached, and live checks.

## Phase 4 — Digital Hands safety and action verification
- Centralize risk classification for send, purchase, delete, call, settings, and navigation actions.
- Require a visible, cancellable confirmation for consequential actions; a spoken yes must be tied to the current action and expire quickly.
- Prevent stale, replayed, duplicate, or ambiguous confirmation tokens.
- Verify action outcomes from the resulting UI/state rather than assuming a successful tap means success.
- Add dry-run mode, action receipts, and a user-visible audit trail with redacted values.
- Test ambiguous contacts, duplicate names, permission loss, app layout changes, timeouts, and cancellation.

Acceptance: no consequential action can bypass current consent; failures are reported instead of falsely marked successful; regression tests cover duplicate/replayed confirmation.

## Phase 5 — Maintainability, UX, performance, and release automation
- Split oversized UI/service/agent classes into focused components without changing behavior in the same commit.
- Add accessibility semantics, scalable text, touch-target checks, and light/dark theme visual regression coverage.
- Profile startup, memory growth, overlay rendering, logs, and long-running background sessions.
- Pin/validate build tooling, run unit tests and static checks on pull requests, and produce checksummed APK artifacts.
- Separate debug and signed-release workflows and verify the certificate fingerprint before release.
- Update architecture docs, feature matrix, privacy notes, troubleshooting, and release notes.

Acceptance: PR CI is green, APK artifact and signature are verified, performance budgets are recorded, and device smoke-test results are attached to the release.

## Working rules
1. Do not push directly to main; use the phase branch and a pull request.
2. One logical fix per commit; do not combine large refactors with behavior changes.
3. Run ./gradlew testDebugUnitTest assembleDebug for each phase when available.
4. CI success is not proof of live microphone, provider, accessibility, or background behavior.
5. Never commit API keys, keystores, signing passwords, logs with personal data, or real user content.
6. Record each item as implemented, automated-tested, device-tested, or blocked; do not mark a feature verified without evidence.

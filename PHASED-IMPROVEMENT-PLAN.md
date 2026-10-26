# JARVIS-2.0 — Five-Phase Improvement Plan

Baseline: v2.0.3 (e2ee360fa35120b0cb94266b62694837cdf3a8a9)
Working branch: improvement/phase-1-release-hardening
Target: keep main stable; deliver each phase as a reviewable change set and require CI plus device smoke tests where applicable.

## Phase 1 — Release hardening and truthful diagnostics (partially implemented; verification pending)

Implemented on the working branch:
- Build identity uses the source commit SHA; update checks do not claim a comparison when the embedded SHA is unknown, and transient errors remain retryable.
- Provider storage now fails closed instead of writing API keys to plaintext preferences when encrypted storage cannot initialize.
- If encrypted storage opens, a migration attempts to move legacy plaintext provider settings into encrypted storage and remove the old copy only after the encrypted commit succeeds.
- Provider settings UI now reports a secure-save failure instead of claiming settings were saved.
- The voice self-test no longer hard-codes a PASS: it reports that the audio round-trip is NOT TESTED and separately checks encrypted provider-storage availability.
- Phase 5 workflow changes now withhold signing secrets from PR jobs and add release signature/certificate/checksum verification.

Still required before Phase 1 acceptance:
- CI verification of the current secure-storage and self-test commits.
- Targeted verification of legacy-file migration, migration failure, keystore failure, and UI behavior on a device/emulator.
- Unit tests for update parsing/unknown SHA/HTTP failures and secure-storage failure behavior.
- Inspect exported diagnostics for private data and ensure users can review what will be shared.

Acceptance: no new API key is written to plaintext preferences; legacy migration is verified; self-test never claims an unperformed audio check; update status is trustworthy; signed-release verification passes.

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

Acceptance: provider tests cover all failure paths; no duplicate tool execution or mixed-provider response; user-visible failures are actionable.

## Phase 3 — Voice, wake word, and lifecycle reliability (in progress)

Implemented on the working branch:
- Track the microphone-acquisition startup coroutine as a session job; cancel it when a session is stopped or replaced.
- Track Live setup work as a session job, check cancellation after loading memory and before connecting, and rethrow coroutine cancellation rather than converting it into a user-facing error.
- Route unexpected microphone-acquisition and Live setup exceptions through the existing voice failure handler.
- CI passed unit tests and APK build workflow for the session-start cancellation commit fc72e3a3c2c71fbbd895950cb75fed34bd2e705d and delayed-reconnect cancellation commit eee2058bb43c6a72630e33d42ff8b749d8027b0e.

Still required before Phase 3 acceptance:
- Audit and bound all reconnect paths; prevent duplicate concurrent reconnect attempts and cancel delayed reconnect work on stop.
- Verify AudioCapture, GeminiLiveClient/WebSocket, AudioPlayer, wake-word service, and speech recognizer ownership and cleanup across all exit paths.
- Add deterministic lifecycle tests where feasible and a manual device matrix for screen-off, Bluetooth, interruptions, permission revocation, and network changes.
- Report voice round-trip checks as verified only after a real session produces valid audio.
- Add battery-aware wake-word modes and clear OEM background-restriction guidance.

Acceptance: no stuck microphone/recording state; bounded recovery; voice tests honestly distinguish simulated, cached, and live checks.

## Phase 4 — Digital Hands safety and action verification (in progress)

Implemented on the working branch:
- Confirmation-gate admission check and prompt publication now happen in one synchronized section, preventing two concurrent callers from replacing one another's pending prompt.
- Confirmation cleanup now runs in a `finally` block, so cancellation of the owning action clears its stale pending prompt and cannot block later confirmations.
- Confirmation logs no longer include action descriptions, which may contain contact names or message text; logs use gate IDs and decisions instead.
- Send-confirmation asks now reserve one active ask ID for the full ask/re-ask sequence; concurrent asks fail closed without replacing the active prompt.
- Send-answer callbacks validate that the currently visible ask and active deferred still correspond, preventing stale callbacks from approving an ended ask.
- Send-confirmation cleanup is cancellation-safe; pending UI state and the active reservation are cleared on all exit paths.
- Send-confirmation logs no longer include recipient names or send contents.
- WhatsApp sends no longer grant a permit to both regular and Business package variants. The permit is scoped to the recognized foreground WhatsApp package, issued immediately before the confirmed send tap, expires quickly, and is revoked when that tap exits.
- WhatsApp send approval is bound to the same package that was active when the user was asked; the composer text and Send target are re-read from a fresh accessibility snapshot after approval, and the exact draft must still match before the tap.
- WhatsApp send logs avoid phone numbers, recipient names, full accessibility dumps, and raw send-tap failure details; the action result reports whether delivery was verified.
- Send permits are now one-shot across the entire app rather than one-shot per package; a package mismatch clears the permit instead of leaving it available for a later app switch.
- Device-action arguments enforce explicit ranges for screen coordinates, percentages, alarm values, timer lengths, wait timeouts, calendar bounds, and deletion counts; `delete_last_chars` requires a count.
- Coroutine cancellation now propagates through `ToolExecutor` and the agent tool-dispatch boundary, rather than being converted into a normal tool failure that could let the agent continue.
- WhatsApp action logs no longer include the recipient name or raw failure detail; the actionable details are returned to the current task instead.
- The WhatsApp photo-share logs no longer include recipient phone digits, and contact-correction logs no longer print the names being corrected.
- Added unit tests for confirmation cancellation/concurrency/stale gate IDs, send-ask cancellation/concurrency, strict answer parsing, and rejection of out-of-range action values.

Still required before Phase 4 acceptance:
- Centralize risk classification for send, delete, call, and other consequential actions.
- Verify action outcomes from resulting UI/state rather than assuming a successful tap means success.
- Add dry-run mode and a user-visible audit trail with redacted values.
- Test ambiguous contacts, duplicate names, permission loss, app layout changes, timeouts, and confirmation replay.


Acceptance: no consequential action can bypass current consent; failures are reported instead of falsely marked successful; regression tests cover duplicate/replayed confirmation.

## Phase 5 — Maintainability, UX, performance, and release automation (in progress)

Implemented on the working branch; latest commit still requires CI verification:
- Added event/branch-specific workflow concurrency so newer runs cancel stale runs without merging PR and push trust contexts.
- Signing secrets are only injected into trusted push/manual signing steps; pull-request jobs are restricted to debug builds.
- CI now runs unit tests plus debug assembly, validates keystore/alias configuration, verifies the signed release APK and its certificate against the configured keystore, and emits SHA-256 checksums for APK artifacts.
- Started a repository-wide audit. The full audit and prioritized backlog are available in the audit roadmap attached to this conversation; confirmed issues and remaining verification tasks are tracked separately.

Still required before Phase 5 acceptance:
- Verify the new workflow end-to-end on current HEAD and inspect the downloaded APK/artifact contents.
- Check in a verified Gradle wrapper and distribution checksum instead of generating a wrapper in CI.
- Establish a static-analysis/lint baseline and gate newly introduced findings.
- Refactor oversized classes incrementally after characterization tests are added.
- Add UI/accessibility, performance, diagnostics privacy, release notes, and device smoke-test coverage.

Acceptance: PR CI is green; release APK signature/certificate/checksum verification passes; build is reproducible; critical actions have tests; device-dependent behavior is reported as device-tested or blocked rather than assumed.

## Audit reference

See [REPOSITORY-AUDIT-ROADMAP.md](REPOSITORY-AUDIT-ROADMAP.md) for the repository-wide findings, evidence status, and prioritized remaining work. The separate audit file is a baseline and does not replace CI or device verification.

## Working rules
1. Do not push directly to main; use the phase branch and a pull request.
2. One logical fix per commit; do not combine large refactors with behavior changes.
3. Run ./gradlew testDebugUnitTest assembleDebug for each phase when available.
4. CI success is not proof of live microphone, provider, accessibility, or background behavior.
5. Never commit API keys, keystores, signing passwords, logs with personal data, or real user content.
6. Record each item as implemented, automated-tested, device-tested, or blocked; do not mark a feature verified without evidence.

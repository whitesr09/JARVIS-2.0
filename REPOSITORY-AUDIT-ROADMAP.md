# JARVIS-2.0 — Repository Audit and Improvement Backlog

Audit date: 2026-10-26  
Working branch: improvement/phase-1-release-hardening  
Status: Broad audit baseline recorded; latest secure-storage, self-test, and Phase 5 workflow commits still require CI. This document is not a release sign-off.

## Scope and method

Both uploaded archives were inventoried and compared. The main snapshot contained 146 files and no unit-test files; the improvement snapshot contained 150 files including 86 production Kotlin files and three Kotlin test files. Production Kotlin source in the improvement snapshot totalled about 36,900 lines. The live branch has moved ahead of the uploaded snapshot, so the archives are a baseline rather than an exact copy of the latest HEAD.

The review covered the source/resource/workflow/build/documentation inventory; repository-wide scans for TODO/error handling, non-null assertions, direct logging of personal values, and high-risk Android APIs; and focused inspection of provider storage, self-tests, update checks, backup/import, diagnostics, model/tool validation, failover/cancellation, voice lifecycle, confirmation gates, accessibility actions, and send flows. Device-specific microphone, Bluetooth, background-service, accessibility-tree, and message-delivery behavior remains unverified until tested on devices.

## Confirmed findings

Severity: P0 = security/release blocker; P1 = important privacy/reliability risk; P2 = maintainability/quality issue. These findings are grounded in inspected code; device-specific reproduction may still be needed.

| ID | Severity | Finding | Status |
|---|---|---|---|
| A-01 | P0 | ProviderStore previously fell back to plaintext SharedPreferences when encrypted preferences failed, risking API-key exposure at rest. | Fix committed: fail closed, attempt migration into encrypted storage, and remove the legacy plaintext file only after a successful encrypted commit. CI and migration failure paths still need verification. |
| A-02 | P1 | The Voice replies self-test hard-coded PASS without attempting a real audio exchange. | Fix committed: reports NOT TESTED and adds an encrypted-provider-storage check. CI still pending. |
| A-03 | P1 | DiagnosticsPack exports crash/log history after credential-pattern sanitization. Sanitizing API-key patterns does not remove all personal information. Recent commits redact corrected names and recipient digits in the identified contact-correction/photo-sharing logs, but a full log-call audit remains necessary. | Open: redact personal values at call sites; review/preview exported diagnostics before sharing. |
| A-04 | P1 | BackupManager import checks a format marker but does not fully validate formatVersion, a settings-key allowlist, payload size, and value constraints. | Open: strict version/schema/key/type/size validation and transactional restore tests. |
| A-05 | P1 | Some communication paths use a Boolean success result for a staged composer or dispatched tap, which is not the same as verified message delivery. | Open: add explicit VERIFIED / STAGED_FOR_USER / FAILED / OUTCOME_UNKNOWN action outcomes. |
| A-06 | P1 | VoiceSessionManager combines session ownership, reconnects, audio, WebSocket callbacks, screen sharing, and phone-call pauses. Recent job-cancellation fixes help, but pause-during-setup and error-teardown races still need tests. | Partially addressed; finish lifecycle race tests and device matrix. |
| A-07 | P1 | Automated tests are concentrated in three files despite extensive provider, agent, accessibility, backup, and voice functionality. | Open: add deterministic fake-provider tests and pure-logic coverage before refactoring. |
| A-08 | P1 | AgentPipeline, SettingsScreen, FloatingOverlayService, DeepIntents, VoiceSessionManager, ToolExecutor, and HandsActions are very large classes, roughly 1,500–2,500 lines each in the snapshot. | Open: behavior-preserving decomposition after characterization tests. |
| A-09 | P1 | The project has only gradle-wrapper.properties, not checked-in wrapper scripts/JAR; CI regenerates the wrapper. | Open: commit a verified wrapper and distribution checksum for reproducible builds. |
| A-10 | P1 | The old workflow injected signing secrets at job scope and could enter signed-release steps on PR events. | Fix committed: PR jobs are debug-only; trusted push/manual signing validates the key, verifies APK/certificate, and emits SHA-256. New workflow still needs a green run and artifact inspection. |
| A-11 | P2 | UpdateChecker reads response JSON without an explicit size cap. Its GitHub query is constrained, but defensive body limits and parsing tests are still appropriate. | Open. |
| A-12 | P2 | Several non-null assertions and broad exception handlers remain. Some are guarded, but each must be reviewed; broad Throwable catches can conceal broken callback state or fatal errors. | Open: replace assertions with explicit states and audit catches individually. |
| A-13 | P2 | Tool arguments now have name/type/enum/size checks plus selected numeric bounds, but every tool schema still needs to be checked against its actual dispatch contract. | Partially addressed; add table-driven schema/dispatch validation. |

## Work already present on the branch

- Chat failover buffers provider output so partial text from a failed provider is not presented as a successful reply.
- Agent retry output is buffered; cancellation is rethrown at important provider/tool boundaries.
- Tool arguments are validated before phone-side dispatch; provider response bodies and streaming output have size limits.
- Confirmation-gate cancellation/concurrency and stale IDs are covered by tests.
- WhatsApp send approval is tied to the same foreground package; its draft and Send control are refreshed after approval, and a short-lived one-shot permit is scoped to the package/tap.
- Voice startup and delayed reconnect jobs are tracked and cancelled on stop.
- The CI workflow is being hardened for PR secret separation, signed APK verification, certificate identity, checksums, and stale-run cancellation.

The confirmation/range test suite passed in CI for commit 452b0ccfe3484dc8695ea1913442e3939da5db22. That success does not cover later commits; the current secure-storage, self-test, and workflow changes remain unverified until their own CI run completes.

## Prioritized plan

### P0 — security and release gates
1. Verify secure-storage initialization failure on device/emulator; ensure no new API key reaches plaintext storage.
2. Test migration of legacy plaintext settings, partial encrypted state, migration commit failure, cleanup failure, and restart after migration.
3. Decide how to handle a legacy plaintext file when encrypted storage cannot initialize; never read/write it as a fallback.
4. Surface encrypted-store/migration warnings consistently in provider screens and diagnostics.
5. Audit all log calls containing contact, phone, message, screenshot, clipboard, transcript, or accessibility content.
6. Redact diagnostics exports and review the final export payload before sharing.
7. Enforce backup format version, input-size limit, schema, settings-key allowlist, type/range validation, and atomic restore.
8. Verify signing secrets are absent from PR jobs and release artifacts cannot upload unless signature/certificate checks succeed.
9. Verify signed APK fingerprint matches the intended install/upgrade key; include SHA-256 checksums in artifacts.
10. Install and upgrade a real device APK; compare certificate fingerprints before declaring a release ready.

### P1 — voice, agents, and device actions
11. Replace Boolean action results with verified/partial/unknown/failure states.
12. Audit WhatsApp, SMS, Gmail, photo-share, calendar, call, alarm, timer, and media outcomes; do not equate launch/tap success with completion.
13. Avoid duplicate sends after an ambiguous post-tap outcome; instruct the user to check the conversation before retrying.
14. Bind send permits to an action ID as well as package/expiry and revoke on cancellation, app switch, or failed verification.
15. Reject late spoken answers that began before the currently pending confirmation.
16. Prevent a stale accessibility index from pointing at a different target after a snapshot refresh.
17. Centralize risk classification for send/delete/call/purchase/settings/generic-tap and macro paths.
18. Add confirmation tests for timeout, cancellation, stale IDs, concurrent asks, replay, and duplicate answers.
19. Add fake-provider tests for timeout, malformed JSON, partial stream, HTTP/network failure, cancellation, failover, oversize body, and tool-call stream failure.
20. Define provider capabilities for text, image input, tools, and native audio; reject unsupported modality combinations early.
21. Add explicit request/turn IDs and prove concurrent turns do not share queue, ledger, executor, delta, or confirmation state.
22. Preserve cancellation through every exception boundary and individually review Throwable catches.
23. Test phone-call pause during database read, Live setup, reconnect delay, and reconnect callback.
24. Verify capture/player/WebSocket/wake-word cleanup after stop, permission revocation, socket close, retry, process death, Bluetooth changes, and call interruption.
25. Use bounded retry/backoff and reset retry state only on a genuinely established session.
26. Keep voice self-test unverified until a live session produces and plays audio.
27. Test screen-off, battery saver, OEM background restrictions, Bluetooth, network changes, and wake-word arbitration on multiple devices/Android versions.
28. Test duplicate/ambiguous contacts and reject them without defaulting to the first result.
29. Verify action results against post-action UI/state.
30. Test permission loss/service disconnect between preflight and final tap.

### P1 — data integrity and provider configuration
31. Separate secure-storage-unavailable, migration-incomplete, not-configured, and network-failure states.
32. Add provider store save/read round-trip and corrupted-cache tests.
33. Cap UpdateChecker responses; test empty/malformed runs, HTTP errors, unknown SHA, and retry after transient failures.
34. Verify only the intended successful main-branch build is used for update status and clearly distinguish debug/release artifacts.
35. Whitelist backup settings keys and reject unknown/dangerous values.
36. Validate calendar/time inputs, timezone/DST assumptions, and receiving-app acceptance.
37. Add model-discovery parsing, dedupe, limits, and capability-classification tests.
38. Test long histories, corrupt memory, duplicates, database concurrency, backup/restore, and compaction boundaries.

### P2 — maintainability and release quality
39. Decompose AgentPipeline into routing, scheduling, history, agent-loop, and response-formatting units.
40. Split ToolExecutor into tool-family executors behind central authorization and validation.
41. Split HandsActions into click/text/gesture/clipboard/undo/guard components behind one serialized mutation boundary.
42. Split DeepIntents into communication, media, system controls, calendar, and share paths.
43. Split VoiceSessionManager into explicit session lifecycle, reconnect policy, audio ownership, and Live event handling.
44. Extract shared UI state/error rendering from SettingsScreen, ChatScreen, HandsFreeScreen, and FloatingOverlayService incrementally.
45. Replace remaining non-null assertions with explicit state handling.
46. Establish a lint/static-analysis baseline, then gate new warnings.
47. Add accessibility semantics, touch-target tests, text scaling, landscape and screen-reader tests.
48. Add UI tests for settings persistence, confirmations, provider edits, backup/restore, and common action paths.
49. Pin GitHub Actions to verified commit SHAs or use a controlled action-update process; preserve least privilege.
50. Check in a verified Gradle wrapper and document phone-first GitHub Actions build workflows.
51. Keep APK checksums and signing certificate fingerprints with artifacts and distinct debug/release names.
52. Add release notes and an auditable device smoke-test checklist.
53. Profile startup, memory, main-thread blocking, overlay redraw, screenshot retention, and background sessions.
54. Review notification listener/screen-capture retention and user controls.
55. Record each item as implemented, CI-tested, device-tested, or blocked.

## Acceptance policy

Do not merge or publish while a P0 remains open. Do not treat a compile/CI pass as proof of live audio, accessibility, message delivery, or background-service behavior. Keep changes on the improvement branch and make one logical fix per commit.

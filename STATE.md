# STATE - ride-hailing-platform

Last updated: 2026-11-25 (gate round 11: session recycle #5 recovery + 3 contract violations on the rides API fixed (ST201 x2, ST204) + web-view crash fix + live-view.yml YAML/SDK/AVD repair; verification runs in flight)
Working mode: **SPEED MODE v2 (D69) + NON-STOP RULE (D74)** - three verification tiers (task/batch/gate), never block on CI, parallel local work, ~45 min task budget, STATE/PROGRESS_LOG updated once per batch. Check `gh run list --limit 3` at each task start; fix red before continuing. NON-STOP RULE: never end the turn while BACKLOG.md has todo items (unless a hard stop applies) - finishing a task/batch/phase is NOT a stop reason; blocked tasks go to BLOCKERS.md and the turn moves on; if cut off, STATE.md must already be current so the next session resumes at the recorded task.
Current phase: 4 (rider booking happy path) - P4-1 fare engine + P4-2 rides API committed (178e400, b3fcabc); round-11 contract run caught 3 violations (offset=null accepted, GET estimate 404-not-405 via {id} shadowing, partial fare PATCH silently default-resetting straightLine/quote) - ALL FIXED this batch, CI verification in flight. Phase 3 code complete; P3-2 offline-map e2e verification re-dispatched (previous run 45593853432 CANCELLED by concurrency before any job ran).
Gate round 11 (2026-11-25, session recycle #5): toolchain wiped again (gh+JDK17+gradle gone; android-sdk/platforms survived). Recovered gh 2.63.2, JDK 17.0.21.1, mirror init script; repo re-cloned clean at 19ffe9b (origin==local). Findings + fixes:
1. CI reds triaged: 45605167019 (actionlint: live-view.yml line-89 indent = 7 spaces from 19ffe9b - YAML invalid), 45600492576 (spectral: exclusiveMinimum boolean under OAS 3.1 + api-contract 3 failures on the NEW rides endpoints), 45604159692 (live-view dispatch: boot failed - emulator binary absent on ubuntu-24.04, ANDROID_HOME not set). P3-2 verification run 45593853432 was CANCELLED by concurrency with zero jobs (superseded before starting).
2. api-contract 3 violations FIXED with tests (151 backend tests green local): (a) GET /v1/rides?offset=null was silently defaulted -> 400 invalid_pagination on present-but-malformed params; (b) GET /v1/rides/estimate hit GET /v1/rides/{id} ("estimate" as an id) -> 401 - explicit GET 405 route + catch-all route("{...}") (registered LAST) answering 405+Allow for known paths, 404 for unknown, with /test/* deliberately NOT known (release absence proofs keep 404); (c) PATCH /v1/admin/config/fares decoded partial bodies through default-valued fields -> silently RESET straightLine/quote - now demands every schema-required key (cities/straightLine/quote + nested) and 400s otherwise. Spec: FareConfig.quote section added (QuotePolicy component, required), exclusiveMinimum in OAS 3.1 numeric form, hooks fare body carries quote.
3. WEB-VIEW (user directive "make sure web-view works"): test-viewer server.js CRASHED on /api/serials when adb is missing (unhandled spawn ENOENT error event killed the whole process) - fixed with guarded spawn + single-shot answer; local smoke green (401 gate, state/step/log APIs, HTML, REPLAY mode with REPLAY_DIR). live-view.yml: YAML fixed, emulator+platform-tools+google_apis image install hard-sets ANDROID_HOME=/usr/local/lib/android/sdk (ci.yml-proven pattern), AVD e2e_api34 pixel_5 created before start-emulators.sh.
Last completed task: gate round 11 contract+web-view batch (commits this push)

## Gate status table

| Gate | Status |
|---|---|
| Phase 0 (Part B delivery plan) | CLOSED 2026-11-24: D11.9 first-green pipeline + report gallery with storyboard screenshots and video, formally CI-verified (run 45507128835, all jobs green, media served from Pages: 6 filmstrip PNGs + 2 mp4 recordings, real UI pixels) |
| Phase 2 (android shell) | GATE CLOSED 2026-11-25, tag phase-2-gate @ 122175d. Scope all done: P2-1a scaffold, P2-1b nav/theming/i18n, P2-1c Hilt/Room+FTS/DataStore/outbox, P2-2 MapLibre main screens (self-hosted tiles), P2-3 storyboard+video evidence, P2-4 auth UI (Ktor+AuthRepository+SessionScreen). Gate evidence per area (identical code per path at HEAD): backend-test + api-contract GREEN run 45530028415 @122175d (PG double-entry ledger verified on real Postgres); android-build + android-ui rider/driver GREEN run 45527631693 @841de18 (emulator shards, instrumented nav tests assert the REAL map/session screens); e2e-cross-actor GREEN run 45527631693; admin-web-test GREEN forced run 45525186273 @fd4cb63 (i18n matrix incl. 30-shot RTL/dark baselines, axe 0 violations on Arabic RTL); mesh-core-sim GREEN 45525186273. Gate budget honest: 3 CI iterations for backend-test (2 CI-only ledger bugs: uuid casts + wallet FK seeding; none reachable by in-memory tests), 1 for android-ui (ambiguous text node). DEFERRED to P2-5 (BACKLOG): android font-scale 1.3 + small/large-phone screenshot cells on CI - CLOSED: nightly 45592490175 all green (see BACKLOG P2-5) |
| Mesh GATE 1-2 (Part C) | GATE 2 CLOSED AND CI-VERIFIED 2026-11-24 (run 45256630914, mesh-core-sim success): beacons (certified profiles, short MAC, adaptive pacing) + PRESENCE (backend-only seal) + GATEWAY_RELAY (opaque forward, internet shortcut) - scenarios 16-19 PASS on CI + locally |
| Mesh GATE 3 (Stage 3) | DONE 2026-11-24: 15/15 scenarios + baseline gate green on CI (run 45245604015, mesh-sim-evidence artifact uploaded) + locally; suite now 19 scenarios (16-19 = GATE 2 close-out) |
| Mesh GATE 4-7 (Part C) | pending (Stage 4 starts with Phase 2 android) |
| Phases 1-11 (Part B) | in progress (P1-1..P1-4, P1-5a/a2, P1-6, P1B-1..5 done; P4-1/P4-2 in CI verify; P3-2 re-dispatched) |

## Environment status (A3 bootstrap audit, 2026-11-23)

| Tool | Local | Notes |
|---|---|---|
| git, gh 2.63.2, python3, ffmpeg, make, node, npm | ok | gh reinstalled 2026-11-24 at /home/z/my-project/tools/gh/bin (needs PATH + GH_TOKEN export each session); node v24, npm 11; actionlint 1.7.7 re-fetched to /tmp (session-local) |
| JDK 17 (javac 17.0.21.1) | ok | /home/z/my-project/tools/jdk17 (reinstalled 2026-11-24) |
| Gradle 8.10.2 | ok | /home/z/my-project/tools/gradle/gradle-8.10.2/bin/gradle (reinstalled; gradle-home cache survived the recycle) |
| actionlint 1.7.7, schemathesis 4.34.0 (pip) | ok | reinstalled 2026-11-24; spectral via npx @stoplight/spectral-cli@6 |
| Android SDK / adb / emulator | PARTIAL (local) | cmdline-tools 11076708 + platform-34 + build-tools 34.0.0 installed 2026-11-24 (Phase 2 start) - COMPILE-ONLY: no KVM/emulator (no KVM in sandbox, 2 vCPU); emulator verification stays on CI runners (D4) |
| Docker / Compose | MISSING (local) | compose stacks run in CI (api-contract job) and on dev machines; degraded local verification recorded |
| scrcpy / Xvfb / cloudflared | not installed (optional) | live-view workflow handles tunneling in CI |

Degraded-verification statement (A3/A6): local emulator-based verification and compose runs are NOT possible in this sandbox; the equivalent evidence is produced by the GitHub Actions emulator/compose jobs. This is recorded wherever evidence is cited.

2026-11-24 keystore audit (P0-9 record correction): gh secret list timestamps prove the 4 signing secrets were created 2026-11-23T17:05 and NEVER overwritten - no rotation happened. The on-disk backup at download/keystore-backup (generated 2026-11-23T17:05:37Z, AES-256-CBC PBKDF2-SHA256 x200000) therefore MATCHES the current secrets and was round-trip re-verified this session: decrypt (passphrase = sibling BACKUP-PASSPHRASE.txt) -> ride-hailing-release.p12 -> keytool lists alias ride-hailing-release with the embedded store password (RESTORE-NOTE.txt inside the archive). Earlier 'backup destroyed' note was stale; plaintext intermediates shredded after verification.

## Counters

- Tasks completed this phase: 33 + P2-3 evidence chain (storyboard module/rule/apps + 8 CI fixes: gallery media copy, poll-pull, step-collector, INTERNET permission, cleartext, adb reverse, google_apis switch, e2e SDK setup)
- CI runs green: ...45507128835 (P0-C FORMAL CLOSE: all jobs green incl. 6/6 android-ui on google_apis with real pixel evidence + e2e-cross-actor finally on REAL emulators; gallery filmstrip + videos served), 45494296659/45492000401 (rounds 9/8), 45462273542 (first full-pipeline green), 45407895919 (P1-CONTRACT); Pages /latest HTTP 200
- Fix attempts used this turn: the P2-3 evidence chain consumed ~15 CI iterations (WAY over the D23 3-attempt budget, recorded honestly): a CHAIN of 8 root causes, each closed with hard log evidence before the next surfaced (no blind retries): (1) AGP post-test uninstall wipes app dirs, (2) UiAutomation shell = app uid on aosp_atd, (3) scoped storage denies cross-app pulls, (4) INTERNET permission missing, (5) cleartext HTTP policy blocked the viewer POST, (6) guest screenrecord dead + ATD display pipeline off by design -> google_apis, (7) gallery media never copied into _site (PRE-EXISTING: every gallery image ever 404'd), (8) e2e-cross-actor ran hollow-green (PRE-EXISTING: no SDK on the job + degraded exit-0). Video+storyboard evidence now real and served

## Next 5 tasks

1. Verify round-11 push CI: api-contract (3 rides violations) + live-view.yml dispatch (web-view live: stack + viewer + 2 emulators + tunnel URL annotation); fix red, re-dispatch
2. Re-dispatch the P3-2 e2e-offline-maps verification (workflow_dispatch; previous run cancelled by concurrency) - green flips P3-2 to done
3. Phase 3 GATE: P3-2 green -> gate report, tag phase-3-gate, start Phase 4 remainder immediately (NON-STOP D74)
4. P4-3 on-device estimator: FareMath port in android (same KAT vectors -> parity suite), fare-bundle fetch/cache (DataStore, expiry + stale flag), offline estimate (cached route -> straight-line x calibrated detour)
5. P4-4 rider booking UI: pickup/dropoff pickers, ride-type chips, estimate card (range + permanent disclaimer + breakdown), book -> POST /v1/rides, trip screen with signed quote, cancel

## Session environment (2026-11-25 recycle #5 recovery)

- repo re-cloned clean at 19ffe9b (origin==local, zero drift); gh 2.63.2 + JDK 17.0.21.1 reinstalled under /home/z/my-project/tools/; gradle-home cache did NOT survive; android-sdk/platforms survived (platform-tools/adb NOT present locally)
- Maven Central STILL 429 from sandbox: mirror init /home/z/my-project/tools/gradle-init-mirror.gradle (-I flag REQUIRED on every gradle invocation, session-local, not committed)
- Gradle needs --max-workers=1 in this sandbox; JAVA_HOME must point to tools/jdk17 for every gradle shell
- env helper: source /home/z/my-project/tools/env.sh (GH_TOKEN + PATH); git credential helper must be re-set per shell (see STATE phase-0 record)
- System java is a JRE (no javac) - JAVA_HOME must point to tools/jdk17 for every gradle shell

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

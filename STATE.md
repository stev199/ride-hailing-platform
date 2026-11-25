# STATE - ride-hailing-platform

Last updated: 2026-11-25 (gate round 10: nexus_4 SDK removal + am instrument /test/ segment both root-caused and fixed; verification runs in flight; Phase 4 planning started)
Working mode: **SPEED MODE v2 (D69) + NON-STOP RULE (D74)** - three verification tiers (task/batch/gate), never block on CI, parallel local work, ~45 min task budget, STATE/PROGRESS_LOG updated once per batch. Check `gh run list --limit 3` at each task start; fix red before continuing. NON-STOP RULE: never end the turn while BACKLOG.md has todo items (unless a hard stop applies) - finishing a task/batch/phase is NOT a stop reason; blocked tasks go to BLOCKERS.md and the turn moves on; if cut off, STATE.md must already be current so the next session resumes at the recorded task.
Current phase: 3 (offline maps and visited places) - code COMPLETE (P3-1 8333005, P3-3 5738206, P3-2 cell wired b28fc38 + gate rounds 5-10); gate closure pending the two in-flight verification runs (below). Phase 2 closed with tag phase-2-gate; P2-5 screenshot cells now wired (the deferral closes).
Gate round 10 (2026-11-25, session recycle #4): recovered toolchain (gh 2.63.2 at tools/bin/gh; JDK17/SDK wiped - see Environment status). Two red findings on HEAD 52846b1, both root-caused with source-level evidence and fixed:
1. Nightly nexus_4 cells: Google REMOVED the Nexus 4 device profile from the SDK (verified by extracting com/android/sdklib/devices/nexus.xml from the current sdklib.core.jar - Nexus list starts at Nexus 7). Fix ada776b: small_phone (generic 720x1280@320dpi, same ~360x640dp footprint). Verify: nightly run 45592490175.
2. e2e-offline-maps INSTRUMENTATION_FAILED-in-600ms (rounds 8+9 shared the same signature with different specs): the runner spec 'com.ridehailing.rider.test/test/androidx.test.runner.AndroidJUnitRunner' has a literal 'test/' middle segment; ComponentName.unflattenFromString splits at the FIRST '/' so the className never resolves and AMS.startInstrumentation returns false (AOSP Instrument.java android-14.0.0_r2 line 535 + AMS startInstrumentation false paths enumerated; APK ground truth from the failed run's android-apks artifact: package/testpkg/targetPackage/runner all correct). Fix ec8b474: spec = pkg.test/androidx.test.runner.AndroidJUnitRunner + pm-list-instrumentation preflight. Verify: forced run 45593853432.
Last completed task: gate round 10 fixes (ada776b nightly matrix, ec8b474 e2e instrument spec) + batch bookkeeping (BACKLOG P2-5/P3-2 -> CI verify in flight, P3-3 -> done)

## Gate status table

| Gate | Status |
|---|---|
| Phase 0 (Part B delivery plan) | CLOSED 2026-11-24: D11.9 first-green pipeline + report gallery with storyboard screenshots and video, formally CI-verified (run 45507128835, all jobs green, media served from Pages: 6 filmstrip PNGs + 2 mp4 recordings, real UI pixels) |
| Phase 2 (android shell) | GATE CLOSED 2026-11-25, tag phase-2-gate @ 122175d. Scope all done: P2-1a scaffold, P2-1b nav/theming/i18n, P2-1c Hilt/Room+FTS/DataStore/outbox, P2-2 MapLibre main screens (self-hosted tiles), P2-3 storyboard+video evidence, P2-4 auth UI (Ktor+AuthRepository+SessionScreen). Gate evidence per area (identical code per path at HEAD): backend-test + api-contract GREEN run 45530028415 @122175d (PG double-entry ledger verified on real Postgres); android-build + android-ui rider/driver GREEN run 45527631693 @841de18 (emulator shards, instrumented nav tests assert the REAL map/session screens); e2e-cross-actor GREEN run 45527631693; admin-web-test GREEN forced run 45525186273 @fd4cb63 (i18n matrix incl. 30-shot RTL/dark baselines, axe 0 violations on Arabic RTL); mesh-core-sim GREEN 45525186273. Gate budget honest: 3 CI iterations for backend-test (2 CI-only ledger bugs: uuid casts + wallet FK seeding; none reachable by in-memory tests), 1 for android-ui (ambiguous text node). DEFERRED to P2-5 (BACKLOG): android font-scale 1.3 + small/large-phone screenshot cells on CI (sandbox has no emulator; admin side already has the full RTL/dark matrix) |
| Mesh GATE 1-2 (Part C) | GATE 2 CLOSED AND CI-VERIFIED 2026-11-24 (run 45256630914, mesh-core-sim success): beacons (certified profiles, short MAC, adaptive pacing) + PRESENCE (backend-only seal) + GATEWAY_RELAY (opaque forward, internet shortcut) - scenarios 16-19 PASS on CI + locally |
| Mesh GATE 3 (Stage 3) | DONE 2026-11-24: 15/15 scenarios + baseline gate green on CI (run 45245604015, mesh-sim-evidence artifact uploaded) + locally; suite now 19 scenarios (16-19 = GATE 2 close-out) |
| Mesh GATE 4-7 (Part C) | pending (Stage 4 starts with Phase 2 android) |
| Phases 1-11 (Part B) | in progress (P1-1..P1-4, P1-5a/a2, P1-6, P1B-1..5 done; P1-5, P1-4c next) |

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

1. Verify gate-round-10 runs: forced CI 45593853432 (e2e-offline-maps instrument fix) + nightly 45592490175 (small_phone cells); fix red, flip BACKLOG cells to done
2. Phase 3 GATE: when both runs green - write gate report, tag phase-3-gate, start Phase 4 immediately (NON-STOP D74)
3. Phase 4 rider booking happy path: P4-1 ride quotes API (fare estimate endpoint + estimator math, JVM-tested) + P4-2 booking flow UI (pickup/dropoff -> quote -> request) - see BACKLOG Phase 4 section (being written)
4. P4-3 booking e2e: rider books from map UI against compose backend (cross-actor evidence lands Phase 5 when drivers exist)
5. Extras: mesh long-mode scenario runner (nightly --scenario=long selection; BACKLOG)

## Session environment (2026-11-25 recycle #3 recovery)

- repo re-cloned; gh CLI + JDK17 (17.0.21.1 Adoptium) + Android SDK (cmdline-tools 11076708, platform-34, build-tools 34.0.0) reinstalled under /home/z/my-project/tools/; gradle-home cache survived; restore script scripts/restore_toolchain.sh
- Maven Central STILL 429 from sandbox: mirror init /home/z/my-project/tools/gradle-init-mirror.gradle (-I flag, session-local, not committed); Robolectric android-all runtime routed via robolectric.dependency.repo.url (set in core-data testOptions)
- local.properties (sdk.dir) created in android/ - untracked, sandbox-only
- Gradle needs --max-workers=1 in this 4GB sandbox: parallel KSP/Hilt workers OOM-killed the daemon twice (recorded; CI unaffected)
- System java is a JRE (no javac) - JAVA_HOME must point to tools/jdk17 for every gradle shell

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

# STATE - ride-hailing-platform

Last updated: 2026-11-24
Working mode: **SPEED MODE v2 (D69)** - three verification tiers (task/batch/gate), never block on CI, parallel local work, ~45 min task budget, STATE/PROGRESS_LOG updated once per batch. Check `gh run list --limit 3` at each task start; fix red before continuing.
Current phase: 2 (android shell) - Phase 1 batch DONE (P1-CONTRACT CI-verified run 45407895919 rounds 1-5; nightly 45405712150 ALL GREEN incl. mesh-long-sim fix)
Current task: P2-1a Android scaffold pushed (e6fbec7..b791319: standalone gradle root, :core-ui theme+i18n en/fr/ar, :app-rider/:app-driver shells, trivial tests, FULL android-build CI contract verified locally incl. APKs) - CI android-build + android-ui emulator jobs + nightly matrix verify on this push; next: P2-1b navigation+theming, or P1-4c admin i18n (gate-tier visual)
Last completed task: P2-1a Android project scaffold (task tier: unit tests green + assembleDebug + assembleDebugAndroidTest both apps; run-ui-tests.sh standalone layout fix; recycle-corrupted gradle caches purged)

## Gate status table

| Gate | Status |
|---|---|
| Phase 0 (Part B delivery plan) | PENDING - one item open: D11.9 first-green pipeline with trivial tests on two emulators (needs Phase 1/2 module skeletons; see BACKLOG P0-C) |
| Mesh GATE 1-2 (Part C) | GATE 2 CLOSED AND CI-VERIFIED 2026-11-24 (run 45256630914, mesh-core-sim success): beacons (certified profiles, short MAC, adaptive pacing) + PRESENCE (backend-only seal) + GATEWAY_RELAY (opaque forward, internet shortcut) - scenarios 16-19 PASS on CI + locally |
| Mesh GATE 3 (Stage 3) | DONE 2026-11-24: 15/15 scenarios + baseline gate green on CI (run 45245604015, mesh-sim-evidence artifact uploaded) + locally; suite now 19 scenarios (16-19 = GATE 2 close-out) |
| Mesh GATE 4-7 (Part C) | pending (Stage 4 starts with Phase 2 android) |
| Phases 1-11 (Part B) | in progress (P1-1..P1-4, P1-6 done; P1-5a, P1-4b, P1-5 next) |

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

- Tasks completed this phase: 30 (P2-1a Android scaffold: standalone gradle root + :core-ui/:app-rider/:app-driver + trivial tests + run-ui-tests.sh layout fix; recycle recovery incl. gradle cache corruption purge - see PROGRESS_LOG.md)
- CI runs green: 45256630914 (GATE 2 close-out), 45286635288 (P1-3 forced full run), 45333709256 (P1-4 admin shell), 45348236832 (batch 1), 45356449692 (P1-5a2 docs push), 45371383121 (P1-CONTRACT push; api-contract skipped there - contract filter fix 4ff447b), 45407895919 (P1-CONTRACT CI-VERIFIED: api-contract SUCCESS, rounds 1-5 closed), 45405712150 (nightly ALL GREEN incl. mesh-long-sim fix); Pages gallery /latest HTTP 200
- Fix attempts used this turn: recycle recovery + gradle cache corruption diagnosis (empty workerMain/transforms dir shells -> purge; modules-2 verified intact) - all within D23 budget, no blockers

## Next 5 tasks

1. Verify P2-1a on CI (android-build + android-ui rider/driver emulator shards + nightly matrix) - first CI compile of the android module
2. P2-1b navigation (Compose Navigation) + full theming pass + i18n completion
3. P1-4c admin i18n EN/FR/AR-RTL + screenshot baselines (gate-tier visual work)
4. P2-1c Hilt DI, Room+FTS, DataStore, outbox v1
5. Extras: mesh long-mode scenario runner (nightly --scenario=long selection; BACKLOG)

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

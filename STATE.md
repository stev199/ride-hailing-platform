# STATE - ride-hailing-platform

Last updated: 2026-11-24
Current phase: 1 (backend core) + 1b (mesh core, parallel track) - P1B-2b GATE 2 CLOSE-OUT DONE (beacons + PRESENCE + GATEWAY_RELAY, scenarios 16-19, local green; CI verification dispatched); parallel P1-3 users/roles/RBAC
Current task: watch CI for the GATE 2 close-out push (mesh job = 19-scenario suite + baseline gate); then P1-3 users/roles/RBAC + P1-4 admin shell
Last completed task: P1B-2b beacons/PRESENCE/GATEWAY_RELAY - app primitives (22 tests) + sim scenarios 16-19 (all PASS) + baseline additive-only; scenario 19 caught a REAL replay hole (gateway early-return skipped seen-cache storage - D60), fixed and pinned by the suite

## Gate status table

| Gate | Status |
|---|---|
| Phase 0 (Part B delivery plan) | PENDING - one item open: D11.9 first-green pipeline with trivial tests on two emulators (needs Phase 1/2 module skeletons; see BACKLOG P0-C) |
| Mesh GATE 1-2 (Part C) | GATE 2 CLOSED 2026-11-24: beacons (certified profiles, short MAC, adaptive pacing) + PRESENCE (backend-only seal) + GATEWAY_RELAY (opaque forward, internet shortcut) - scenarios 16-19 green locally, CI verification in flight |
| Mesh GATE 3 (Stage 3) | DONE 2026-11-24: 15/15 scenarios + baseline gate green on CI (run 45245604015, mesh-sim-evidence artifact uploaded) + locally; suite now 19 scenarios (16-19 = GATE 2 close-out) |
| Mesh GATE 4-7 (Part C) | pending (Stage 4 starts with Phase 2 android) |
| Phases 1-11 (Part B) | in progress (P1-1, P1-2, P1-6 done; P1-3/P1-4/P1-5 next) |

## Environment status (A3 bootstrap audit, 2026-11-23)

| Tool | Local | Notes |
|---|---|---|
| git, gh 2.63.2, python3, ffmpeg, make, node, npm | ok | gh reinstalled 2026-11-24 at /home/z/my-project/tools/gh/bin (needs PATH + GH_TOKEN export each session); node v24, npm 11 |
| JDK 17 (javac 17.0.21.1) | ok | /home/z/my-project/tools/jdk17 (reinstalled 2026-11-24) |
| Gradle 8.10.2 | ok | /home/z/my-project/tools/gradle/gradle-8.10.2/bin/gradle (reinstalled; gradle-home cache survived the recycle) |
| actionlint 1.7.7, schemathesis 4.34.0 (pip) | ok | reinstalled 2026-11-24; spectral via npx @stoplight/spectral-cli@6 |
| Android SDK / adb / emulator | MISSING (local) | no KVM in sandbox, 2 vCPU, 8GB disk free - install scheduled with Phase 2 start; CI runners have KVM and carry emulator jobs (D4) |
| Docker / Compose | MISSING (local) | compose stacks run in CI (api-contract job) and on dev machines; degraded local verification recorded |
| scrcpy / Xvfb / cloudflared | not installed (optional) | live-view workflow handles tunneling in CI |

Degraded-verification statement (A3/A6): local emulator-based verification and compose runs are NOT possible in this sandbox; the equivalent evidence is produced by the GitHub Actions emulator/compose jobs. This is recorded wherever evidence is cited.

2026-11-24 recycle note: the sandbox wipe also destroyed download/keystore-backup (Actions secrets are write-only). Keystore rotation + fresh secrets + new encrypted backup re-planned as P0-9; safe pre-release because nothing is published yet (no tags by plan).

## Counters

- Tasks completed this phase: 16 (P1-2 auth, P1B-3 routing, D49 contract alignment, D50 pages rerun-safety, P0-9 keystore backup, P1B-4 GATE 3 - see PROGRESS_LOG.md)
- CI runs green: 45222823793 (D49/D50), 45243331948 (GATE 3 suite on push), 45245604015 (forced full run: test+simReport, mesh-sim-evidence artifact, ALL GREEN incl. ci-summary); live-view 45243841560 guard+skip verified
- Fix attempts used this turn: 6 distinct root causes (D51 hop-budget off-by-one, D52 dedup-before-discovery, D53 inline clock stall, relay TTL slack, stress rate-limit config, live-view guard) - within retry budget (D23), no blockers

## Next 5 tasks

1. Verify GATE 2 close-out on CI (mesh job: 19 scenarios + baseline gate) - in flight
2. P1-3 users/roles/RBAC + Postgres-backed stores + P1-4 admin shell + login (admin-web)
3. P1-5 config service: signed bundle serving /v1/config + login-methods wired to LoginMethodsConfig
4. P1B-5 Stage 4 prep: sim-bridge topology engine wiring
5. P0-C remainder: first-green pipeline with admin + two-emulator trivial tests (needs Phase 2 android shell)

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

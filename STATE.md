# STATE - ride-hailing-platform

Last updated: 2026-11-24
Current phase: 1 (backend core) - P1-3 users/roles/RBAC DONE AND CI-VERIFIED (run 45286635288 ALL GREEN incl. backend-test with PG stores + api-contract); next: P1-4 admin shell
Current task: P1-4 admin shell (React+TS+Vite): login, RBAC routing, layout, config center v0 (needs P1-5 config service endpoints)
Last completed task: P1-3 users/roles/RBAC - 12-perm RBAC matrix (RIDER/DRIVER empty set = driver blindness precursor), session/device registry (force logout, X-Device-Label), admin user management with hash-chained audit (no secrets in payloads), PG-backed stores + V2 event_log reload migration; admin endpoints documented in openapi (spectral 0 errors)

## Gate status table

| Gate | Status |
|---|---|
| Phase 0 (Part B delivery plan) | PENDING - one item open: D11.9 first-green pipeline with trivial tests on two emulators (needs Phase 1/2 module skeletons; see BACKLOG P0-C) |
| Mesh GATE 1-2 (Part C) | GATE 2 CLOSED AND CI-VERIFIED 2026-11-24 (run 45256630914, mesh-core-sim success): beacons (certified profiles, short MAC, adaptive pacing) + PRESENCE (backend-only seal) + GATEWAY_RELAY (opaque forward, internet shortcut) - scenarios 16-19 PASS on CI + locally |
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

- Tasks completed this phase: 18 (P1B-2b GATE 2 close-out + P1-3 users/roles/RBAC - see PROGRESS_LOG.md)
- CI runs green: 45256630914 (GATE 2 close-out full run), 45286635288 (P1-3 forced full run: backend-test incl. PG stores vs the postgres service container, api-contract incl. schemathesis on the new admin endpoints, mesh 19 scenarios, report-publish after the D66 recursion fix, ALL GREEN incl. ci-summary); Pages gallery /runs/45286635288/ HTTP 200
- Fix attempts used this turn: 6 distinct root causes (D51 hop-budget off-by-one, D52 dedup-before-discovery, D53 inline clock stall, relay TTL slack, stress rate-limit config, live-view guard) - within retry budget (D23), no blockers

## Next 5 tasks

1. P1-4 admin shell (React+TS+Vite): login, RBAC routing, layout (against the P1-3 admin API)
2. P1-5 config service: signed bundle serving /v1/config + login-methods wired to LoginMethodsConfig
3. P1B-5 Stage 4 prep: sim-bridge topology engine wiring
4. P0-C remainder: first-green pipeline with admin + two-emulator trivial tests (needs Phase 2 android shell)
5. P1-CONTRACT: authenticated schemathesis coverage (register/login hooks)

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

# STATE - ride-hailing-platform

Last updated: 2026-11-24
Current phase: 1 (backend core) + 1b (mesh core, parallel track) - P1-2 auth done; P1B Stage 2 routing implemented (GATE 2 close-out pending: beacons + GATEWAY_RELAY)
Current task: D49 contract fix verified locally (schemathesis 386/386 pinned seed); force-full-run CI verification in flight, then P1B-4 simulator + 15 scenarios + baseline.json (Stage 3, GATE 3); parallel P1-3 users/roles/RBAC + P0-9 keystore backup re-creation
Last completed task: D49 generator-precise contract (openapi patterns == enforced validation, ContractAlignmentTest, schemathesis pinned+seeded) - backend 63 tests + spectral + actionlint green locally

## Gate status table

| Gate | Status |
|---|---|
| Phase 0 (Part B delivery plan) | PENDING - one item open: D11.9 first-green pipeline with trivial tests on two emulators (needs Phase 1/2 module skeletons; see BACKLOG P0-C) |
| Mesh GATE 1-7 (Part C) | pending (Stage 1 starts with Phase 1b) |
| Phases 1-11 (Part B) | pending |

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

- Tasks completed this phase: 13 (P1-2 auth, P1B-3 routing, D49 contract alignment - see PROGRESS_LOG.md)
- CI runs green: see PROGRESS_LOG (D49 verification run in flight)
- Fix attempts used this turn: 3 distinct root causes on the contract job (ST202 phone schema gap, ST201 multi-@ + null acceptance, Java \v class semantics in the test harness) - within retry budget (D23), no blockers

## Next 5 tasks

1. Watch force-full-run CI green (api-contract with pinned schemathesis + seed) and record run URL
2. P0-9 keystore rotation + overwrite 4 secrets + fresh encrypted backup in download/keystore-backup
3. P1B-4 simulator + 15 scenarios + baseline.json (Stage 3, GATE 3) - routing primitives ready
4. P1B-2b beacons (DRIVER_BEACON/PRESENCE) + GATEWAY_RELAY internet shortcut (closes GATE 2)
5. P1-3 users/roles/RBAC + Postgres-backed stores + P1-4 admin shell + login (admin-web)

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

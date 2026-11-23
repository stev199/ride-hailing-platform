# STATE - ride-hailing-platform

Last updated: 2026-11-24
Current phase: 1 (backend core) + 1b (mesh core, parallel track) - P1-2 auth done; P1B Stage 2 routing implemented (GATE 2 close-out pending: beacons + GATEWAY_RELAY)
Current task: P1B-4 simulator + 15 scenarios + baseline.json (Stage 3, GATE 3); parallel P1-3 users/roles/RBAC
Last completed task: D49 contract fix verified on CI: force-full-run 45222823793 ALL GREEN incl. api-contract (compose + pinned schemathesis) and report-publish; D50 rerun-safe Pages deploy; P0-9 keystore rotated + fresh encrypted backup in download/keystore-backup

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

- Tasks completed this phase: 15 (P1-2 auth, P1B-3 routing, D49 contract alignment, D50 pages rerun-safety, P0-9 keystore backup - see PROGRESS_LOG.md)
- CI runs green: push run 45222423620 + force-full-run 45222823793 (ALL GREEN incl. api-contract + report-publish + ci-summary)
- Fix attempts used this turn: 4 distinct root causes (D49 ST202 phone schema gap, D49 ST201 multi-@ + null acceptance, Java \v class semantics in the test harness, D50 duplicate github-pages artifacts on rerun) - within retry budget (D23), no blockers

## Next 5 tasks

1. P1B-4 simulator + 15 scenarios + baseline.json (Stage 3, GATE 3) - routing primitives ready
2. P1B-2b beacons (DRIVER_BEACON/PRESENCE) + GATEWAY_RELAY internet shortcut (closes GATE 2)
3. P1-3 users/roles/RBAC + Postgres-backed stores + P1-4 admin shell + login (admin-web)
4. P1-5 config service: signed bundle serving /v1/config + login-methods wired to LoginMethodsConfig
5. P0-C remainder: first-green pipeline with admin + two-emulator trivial tests (needs Phase 2 android shell)

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

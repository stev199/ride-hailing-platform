# STATE - ride-hailing-platform

Last updated: 2026-11-23
Current phase: 0 (near-complete; see gate table) - Phase 0 Part B items done, CI skeleton live, toolchain partially local
Current task: push CI v2 + docs, watch CI, then Phase 1 backend skeleton
Last completed task: Phase-0 CI skeleton (full D3 job graph), Part E tooling (viewer/report/storyboard/sim-bridge/e2e), spec docs saved, architecture docs written, local JDK17+Gradle installed

## Gate status table

| Gate | Status |
|---|---|
| Phase 0 (Part B delivery plan) | PENDING - one item open: D11.9 first-green pipeline with trivial tests on two emulators (needs Phase 1/2 module skeletons; see BACKLOG P0-C) |
| Mesh GATE 1-7 (Part C) | pending (Stage 1 starts with Phase 1b) |
| Phases 1-11 (Part B) | pending |

## Environment status (A3 bootstrap audit, 2026-11-23)

| Tool | Local | Notes |
|---|---|---|
| git, gh 2.63.2, python3, ffmpeg, make, node, npm | ok | gh at /home/z/my-project/tools/gh/bin (needs PATH + GH_TOKEN export each session) |
| JDK 17 (javac 17.0.21.1) | ok | /home/z/my-project/tools/jdk17 |
| Gradle 8.10.2 | ok | /home/z/my-project/tools/gradle/gradle-8.10.2/bin/gradle |
| Android SDK / adb / emulator | MISSING (local) | no KVM in sandbox, 2 vCPU, 8GB disk free - install scheduled with Phase 2 start; CI runners have KVM and carry emulator jobs (D4) |
| Docker / Compose | MISSING (local) | compose stacks run in CI (api-contract job) and on dev machines; degraded local verification recorded |
| scrcpy / Xvfb / cloudflared | not installed (optional) | live-view workflow handles tunneling in CI |

Degraded-verification statement (A3/A6): local emulator-based verification and compose runs are NOT possible in this sandbox; the equivalent evidence is produced by the GitHub Actions emulator/compose jobs. This is recorded wherever evidence is cited.

## Counters

- Tasks completed this phase: 8 (see PROGRESS_LOG.md)
- CI runs green: 2 (skeleton) + new runs from this push (see PROGRESS_LOG)
- Fix attempts used this turn: 3/3-category ok (actionlint hashFiles guard pattern, spectral ruleset, artifact-download resilience) - no blockers

## Next 5 tasks

1. Phase 1: backend skeleton (Ktor, /health, compose wiring, Flyway, hash-chained event store schema, config service skeleton) - BACKLOG P1-1
2. Phase 1: auth (OTP providers incl. dev inbox, JWT + rotating refresh, argon2id) - P1-2
3. Phase 1b: mesh-core Stage 1 protocol + crypto (GATE 1) - P1B-1..3
4. Phase 1: admin shell + login + RBAC (admin-web) - P1-4
5. P0-C: first-green pipeline (trivial backend/admin/mesh tests + report gallery) once 1/1b skeletons land

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

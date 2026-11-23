# STATE - ride-hailing-platform

Last updated: 2026-11-23
Current phase: 1 (backend core) + 1b (mesh core, parallel track) - Stage 1 GATE scope green
Current task: P1-2 auth (OTP providers + JWT + argon2id); next P1B-3 routing + Dijkstra oracle
Last completed task: P1-1 + P1B Stage 1 verified end-to-end: forced full CI run green incl. api-contract (schemathesis; RFC 9110 Allow fix) - run 45174319535

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

- Tasks completed this phase: 10 (see PROGRESS_LOG.md)
- CI runs green: 2 (skeleton) + new runs from this push (see PROGRESS_LOG)
- Fix attempts used this turn: 5 distinct root causes fixed within retry budget (D23) - no blockers

## Next 5 tasks

1. P1-2 auth: OTP providers (dev inbox adapter first), phone/email+password, JWT + rotating refresh, argon2id, rate limits
2. P1B-3 routing: seen-cache, flooding, breadcrumbs, route discovery, cost fn + Dijkstra oracle (Stage 2)
3. P1B-4 simulator + 15 scenarios + baseline.json (Stage 3, GATE 3)
4. P1-3 users/roles/RBAC + P1-4 admin shell + login (admin-web)
5. P0-C remainder: first-green pipeline with admin + two-emulator trivial tests (needs Phase 2 android shell)

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

# STATE - ride-hailing-platform

Last updated: 2026-11-24
Current phase: 1 (backend core) - P1-4 admin shell DONE AND CI-VERIFIED (run 45333709256 success: admin-web-test vitest+playwright+axe, lint eslint/tsc, trivy admin-web image scan); next: P1-5a config service endpoints
Current task: P1-5a config service endpoints (signed bundle serving /v1/config + login-methods wired to LoginMethodsConfig) - makes the admin config center v0 live
Last completed task: P1-4 admin shell (React+TS+Vite) - admin-web module: password login, single-flight refresh + boot rehydrate, best-effort logout, client RBAC mirror (pinned-fixture drift test vs Rbac.kt), permission-filtered layout, Overview/Users/Audit/Config-center-v0 pages; 18 vitest + 30 playwright (chromium+firefox, route-mocked API per D68, axe wcag2a/2aa 0 violations); nginx Dockerfile + compose service + trivy image scan wired; Makefile recipe tabs repaired (pre-existing breakage)

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
| Android SDK / adb / emulator | MISSING (local) | no KVM in sandbox, 2 vCPU, 8GB disk free - install scheduled with Phase 2 start; CI runners have KVM and carry emulator jobs (D4) |
| Docker / Compose | MISSING (local) | compose stacks run in CI (api-contract job) and on dev machines; degraded local verification recorded |
| scrcpy / Xvfb / cloudflared | not installed (optional) | live-view workflow handles tunneling in CI |

Degraded-verification statement (A3/A6): local emulator-based verification and compose runs are NOT possible in this sandbox; the equivalent evidence is produced by the GitHub Actions emulator/compose jobs. This is recorded wherever evidence is cited.

2026-11-24 keystore audit (P0-9 record correction): gh secret list timestamps prove the 4 signing secrets were created 2026-11-23T17:05 and NEVER overwritten - no rotation happened. The on-disk backup at download/keystore-backup (generated 2026-11-23T17:05:37Z, AES-256-CBC PBKDF2-SHA256 x200000) therefore MATCHES the current secrets and was round-trip re-verified this session: decrypt (passphrase = sibling BACKUP-PASSPHRASE.txt) -> ride-hailing-release.p12 -> keytool lists alias ride-hailing-release with the embedded store password (RESTORE-NOTE.txt inside the archive). Earlier 'backup destroyed' note was stale; plaintext intermediates shredded after verification.

## Counters

- Tasks completed this phase: 19 (P1-4 admin shell - see PROGRESS_LOG.md)
- CI runs green: 45256630914 (GATE 2 close-out full run), 45286635288 (P1-3 forced full run, ALL GREEN incl. ci-summary), 45333709256 (P1-4 admin shell: admin-web-test + lint + trivy image scan + report-publish + ci-summary; component jobs path-skipped); Pages gallery /runs/45333709256/ HTTP 200 (admin light/dark screenshots)
- Fix attempts used this turn: 6 distinct root causes (D51 hop-budget off-by-one, D52 dedup-before-discovery, D53 inline clock stall, relay TTL slack, stress rate-limit config, live-view guard) - within retry budget (D23), no blockers

## Next 5 tasks

1. P1-5a config service: signed bundle serving /v1/config + login-methods wired to LoginMethodsConfig (config center v0 goes live)
2. P1-4b admin users mutation UI: create/edit/ban/reset-password/force-logout against the P1-3 admin endpoints
3. P1B-5 Stage 4 prep: sim-bridge topology engine wiring
4. P0-C remainder: first-green pipeline with admin + two-emulator trivial tests (needs Phase 2 android shell); compose-backed admin Playwright project lands here (D68)
5. P1-CONTRACT: authenticated schemathesis coverage (register/login hooks)

## Phase 0 setup record (from repository bootstrap)

- Repo https://github.com/stev199/ride-hailing-platform (public), branch protection on `main` requires `ci-summary`, Pages enabled (build_type=workflow), release keystore in 4 Actions secrets, encrypted backup in /home/z/my-project/download/keystore-backup/ (+ BACKUP-PASSPHRASE.txt), Dependabot/secret-scanning/push-protection on, labels bug/feature/flaky/blocked/phase-1..11.
- Release tag: deferred until Phase 11 by plan.

## Key locations

- Pages report gallery: https://stev199.github.io/ride-hailing-platform/ (per-run /runs/<run-id>/)
- Specs: /docs/PROJECT_SPEC.md (B), /docs/MESH_SPEC_PROMPT.md (C), /docs/CI_SPEC.md (D), /docs/VISIBILITY_SPEC.md (E), /docs/AUTOPILOT_SPEC.md (A)
- Live-view dispatch: gh workflow run live-view.yml -f scenario=cross-actor-all (available once android exists)

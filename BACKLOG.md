# BACKLOG

Ordered granular tasks. Each is finishable and verifiable in one work session. Dependencies noted. Status: todo | doing | done | blocked | deferred.

## Phase 0 (repo, docs, CI, tooling)

| ID | Task | Depends | Status | Evidence |
|---|---|---|---|---|
| P0-1 | GitHub bootstrap: repo, settings, labels, Pages, branch protection (ci-summary), keystore secrets | - | done | STATE.md phase-0 record; CI run 45159002876 |
| P0-2 | Save master prompt parts to /docs (A1): PROJECT/MESH/CI/VISIBILITY/AUTOPILOT specs | P0-1 | done | docs/*.md |
| P0-3 | Bootstrap audit + local toolchain (JDK17, Gradle 8.10.2, gh) | P0-1 | done | STATE.md environment |
| P0-4 | Master docs: FEATURES, COMPETITIVE_AUDIT, ARCHITECTURE (Mermaid), THREAT_MODEL, OpenAPI skeleton | P0-2 | done | files; spectral 0 errors |
| P0-5 | Full CI skeleton per D3: changes/lint/backend/api-contract/admin/mesh-sim/android-build/ui-shards/e2e/security/report-publish/ci-summary + nightly/release/codeql/dependency-review/live-view/avd-cache/dependabot | P0-1 | done | .github/workflows/*; actionlint clean |
| P0-6 | Part E tooling: test-viewer (live+replay), report generator, storyboard helpers (Kotlin+Playwright), sim-bridge v0.1, e2e orchestrator skeleton | P0-5 | done | tools/*, e2e/* |
| P0-7 | Makefile + scripts (ci-local, seed-test, start-emulators, wait-for-boot, wait-for-health, collect-artifacts, run-ui-tests) + infra compose (infra/geo/app/test profiles) + fetch-map-data.sh | P0-5 | done | Makefile, scripts/, infra/ |
| P0-8 | Android SDK + cmdline-tools install locally + 2 AVDs + headless boot verification (A3) | P0-3 | deferred | reason: no KVM/limited disk in sandbox; emulator verification delegated to CI (D4). Revisit if environment changes; AVD creation included in CI avd-cache.yml |
| P0-C | D11.9 first-green pipeline: trivial backend/admin/mesh-sim tests + report gallery with storyboard screenshots and video | P1-1, P1B-1, P1-4 | todo | |
| P0-9 | Re-create release keystore + Actions secrets + encrypted off-repo backup (sandbox recycle destroyed the local backup; secrets unreadable by design) | P0-1 | done | rotation + 4 secrets + download/keystore-backup round-trip verified; scripts/p0_keystore_backup.sh |

## Phase 1 (backend core + admin shell)

| ID | Task | Depends | Status |
|---|---|---|---|
| P1-1 | Backend module skeleton: Ktor + /health + Dockerfile + Flyway (up) + hash-chained event store schema + config service skeleton (signed bundles) | P0-C later ok | done | run 45171484716; Flyway down = forward-fix policy (D33) |
| P1-2 | Auth: OTP providers (dev inbox adapter, SMTP, Telegram bot, self-hosted SMS doc), phone/email+password, JWT + rotating refresh, argon2id, rate limits | P1-1 | done | backend 53 tests; run 45174319535; D34-D37 |
| P1-3 | Users/roles/RBAC + sessions/devices + admin actions in audit store | P1-2 | todo |
| P1-4 | Admin shell (React+TS+Vite): login, RBAC routing, layout, config center v0 | P1-2 | todo |
| P1-5 | Ledger/wallet schema + double-entry invariant tests (schema now, services Phase 7) | P1-1 | todo |
| P1-6 | /test/barrier + /test/seed endpoints (test profile only) + test proving absence in release | P1-1 | done | /test/* routes + absence test (P1-1 commit) |
| P1-CONTRACT | Wire authenticated schemathesis coverage (register/login hooks) so /v1/me, login/password, otp/verify, refresh get positive authenticated cases (currently 401-only warnings); consider --max-examples bump in nightly; PR runs keep pinned seed (D49) | P1-2 | todo | |
| P1B-1 | mesh-core module: Gradle setup, binary envelope codec + fragmentation + fuzz/KAT/bit-flip tests (Stage 1) | P0-3 | done | 20 tests green run 45172363159 |
| P1B-2 | mesh-core crypto: Ed25519/X25519 + ChaCha20-Poly1305 via JDK built-ins (T-2 resolved, D32), rotating originId, hop MAC; RFC KATs pinned | P1B-1 | done | CryptoTest RFC 8032 + 8439 vectors green |
| P1B-3 | mesh-core routing: seen-cache, flooding, breadcrumbs, route discovery, cost function + Dijkstra oracle (Stage 2) | P1B-2 | done | 61 tests incl. 200-graph oracle parity |
| P1B-4 | mesh-core simulator: topology/mobility/loss models + 15 mandatory scenarios + metrics + baseline.json (Stage 3, GATE 3) | P1B-3 | done | docs/evidence/mesh-sim/; mesh-core/baseline.json gate; 15/15 PASS local 2026-11-24; D51-D58; CI wiring in ci.yml mesh job (test simReport + artifact); CI green run 45245604015 |
| P1B-2b | GATE 2 close-out: DRIVER_BEACON (certified profile snippet relay-once, short MAC, adaptive pacing), PRESENCE (sealed to backend), GATEWAY_RELAY (opaque forward, internet shortcut) + scenarios 16-19 | P1B-4 | done | mesh-core/src/main/kotlin/com/ridehailing/mesh/app/ (22 tests); scenarios 16-19 all PASS local; baseline.json additive (existing 15 byte-identical); replay hole caught+fixed (D60); commits a1a055d, d73e26d |
| P1B-5 | mesh-core Stage 4 Android integration prep: sim-bridge topology engine wiring | P1B-4 | todo |

## Phase 2 (android shell) and beyond

- P2-1 Android project skeleton (rider+driver modes, Compose theming, i18n en/fr/ar RTL, navigation, Hilt, Room+FTS, DataStore, outbox v1)
- P2-2 MapLibre main screen against self-hosted tiles (CI compose)
- P2-3 Storyboard helper wired into app modules + test-rule; Roborazzi baselines dir
- P2-4 Auth UI against Phase 1 backend
- P3-P11 per Part B delivery plan (tracked as phases land; P0/P1 features in FEATURES.md)

## Deferred / blocked

| ID | Item | Reason | Revisit |
|---|---|---|---|
| P0-8 | local SDK/AVD | sandbox: no KVM, 2 vCPU | Phase 2 start / environment change |
| X-06 Stage 6 | physical devices for BLE | none available in sandbox | per Part C: mark simulation/emulator-verified only, list unverified items |

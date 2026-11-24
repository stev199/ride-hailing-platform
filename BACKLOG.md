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
| P0-C | D11.9 first-green pipeline: trivial backend/admin/mesh-sim tests + report gallery with storyboard screenshots and video | P1-1, P1B-1, P1-4 | done | CLOSED 2026-11-24 (run 45507128835): all jobs green incl. android-ui on google_apis (ATD disables the display pipeline - pixels impossible) + e2e-cross-actor finally on REAL emulators (was hollow-green: no SDK + degraded exit-0, fixed fail-closed); gallery serves 6 real-UI storyboard step screenshots + 2 screen recordings (filmstrip + video URLs HTTP 200, real pixels verified: 147+ distinct colors); evidence chain = 8 root causes incl. 2 pre-existing pipeline bugs (gallery media never shipped to _site; hollow e2e) |
| P0-9 | Re-create release keystore + Actions secrets + encrypted off-repo backup (sandbox recycle destroyed the local backup; secrets unreadable by design) | P0-1 | done | 4 secrets created 2026-11-23T17:05 (no rotation ever happened - record corrected); download/keystore-backup matches, round-trip re-verified 2026-11-24 (decrypt -> keytool alias ride-hailing-release); scripts/p0_keystore_backup.sh was never committed - backup files ARE the recovery artifact |

## Phase 1 (backend core + admin shell)

| ID | Task | Depends | Status |
|---|---|---|---|
| P1-1 | Backend module skeleton: Ktor + /health + Dockerfile + Flyway (up) + hash-chained event store schema + config service skeleton (signed bundles) | P0-C later ok | done | run 45171484716; Flyway down = forward-fix policy (D33) |
| P1-2 | Auth: OTP providers (dev inbox adapter, SMTP, Telegram bot, self-hosted SMS doc), phone/email+password, JWT + rotating refresh, argon2id, rate limits | P1-1 | done | backend 53 tests; run 45174319535; D34-D37 |
| P1-3 | Users/roles/RBAC + sessions/devices + admin actions in audit store | P1-2 | done | Rbac.kt 12-perm matrix; sessions w/ device link + force logout; AdminService audited mutations; PG stores (V2 reload migration); openapi documented; 82 tests local, CI verify dispatched |
| P1-4 | Admin shell (React+TS+Vite): login, RBAC routing, layout, config center v0 | P1-2 | done | admin-web module: auth (password login, single-flight refresh, boot rehydrate, best-effort logout), client RBAC mirror w/ pinned-fixture drift test, permission-filtered layout, Overview/Users/Audit/Config v0; 18 vitest + 30 playwright (chromium+firefox, route-mocked, axe 0 violations); compose service + trivy image scan wired; D67-D68; commits d3a087f..af664ef; CI-VERIFIED run 45333709256 (admin-web-test+lint+security green, gallery /runs/45333709256/) |
| P1-4b | Admin users mutation UI: create/edit/ban/reset-password/force-logout against the P1-3 admin endpoints (buttons exist as hints in P1-4 shell) | P1-4 | todo |
| P1-4c | Admin i18n EN/FR/AR-RTL + light/dark screenshot baselines (D3.4 full set; current shell is EN-only, D68) | P1-4 | todo |
| P1-5 | Ledger/wallet schema + double-entry invariant tests (schema now, services Phase 7) | P1-1 | todo |
| P1-5a | Config service endpoints: signed bundle serving /v1/config + login-methods wired to LoginMethodsConfig (config center v0 needs them to go live) | P1-1 | done | commit 4544b13: ConfigService + provider wiring + /v1/config + admin GET/PATCH + openapi + admin-web live page; task-tier verified (D69); config EDITORS (PATCH UI) still open below |
| P1-5a2 | Config center editors: PATCH /v1/admin/config/login-methods UI (CONFIG_WRITE; toggle switches + priority inputs) + smoke | P1-5a | done | commit 981c040: editor card (toggles + priority inputs, changed-fields-only PATCH, Save disabled until dirty, devOnly note D37), verbatim server errors, ConfigPage.test 3 unit + e2e config.spec 5 (payload contract, duplicate_priority verbatim, axe w/ dialog open, dark editor evidence, ADMIN_SUPPORT no editor); local task-tier GREEN (tsc/eslint/vitest 21/chromium 26/build); CI-verified docs push run 45356449692 |
| P1-4c-sec | admin-web dependency security: 7 Dependabot alerts (2 critical: tinypool RCE gadget, vitest mocker injection/path-traversal; react-router open redirect CVE-2025-68470 bypass + SSR deserializeErrors) - all in dev/test tooling or SPA router; npm update of vitest/tinypool/react-router + full green re-run | P1-4 | done | commit 846390f: vitest 5.1.1 + react-router-dom 7.18.6, npm audit clean; run 45348236832 green (status cell drift fixed this batch) |
| P1-6 | /test/barrier + /test/seed endpoints (test profile only) + test proving absence in release | P1-1 | done | /test/* routes + absence test (P1-1 commit) |
| P1-CONTRACT | Wire authenticated schemathesis coverage (register/login hooks) so /v1/me, login/password, otp/verify, refresh get positive authenticated cases (currently 401-only warnings); consider --max-examples bump in nightly; PR runs keep pinned seed (D49) | P1-2 | done | hooks 1630a15: rider+bootstrap-admin bearer, login/otp/refresh full-shape overrides, refresh self-heal; local task-tier: load_from_env import+registration OK vs schemathesis 4.34.0, paths/env cross-checked vs openapi+Application.kt; CI-VERIFIED: dispatched run 45407895919 api-contract SUCCESS (rounds 1-5, 901 cases, warnings-only); nightly --max-examples bump deferred to Extras |
| P1B-1 | mesh-core module: Gradle setup, binary envelope codec + fragmentation + fuzz/KAT/bit-flip tests (Stage 1) | P0-3 | done | 20 tests green run 45172363159 |
| P1B-2 | mesh-core crypto: Ed25519/X25519 + ChaCha20-Poly1305 via JDK built-ins (T-2 resolved, D32), rotating originId, hop MAC; RFC KATs pinned | P1B-1 | done | CryptoTest RFC 8032 + 8439 vectors green |
| P1B-3 | mesh-core routing: seen-cache, flooding, breadcrumbs, route discovery, cost function + Dijkstra oracle (Stage 2) | P1B-2 | done | 61 tests incl. 200-graph oracle parity |
| P1B-4 | mesh-core simulator: topology/mobility/loss models + 15 mandatory scenarios + metrics + baseline.json (Stage 3, GATE 3) | P1B-3 | done | docs/evidence/mesh-sim/; mesh-core/baseline.json gate; 15/15 PASS local 2026-11-24; D51-D58; CI wiring in ci.yml mesh job (test simReport + artifact); CI green run 45245604015 |
| P1B-2b | GATE 2 close-out: DRIVER_BEACON (certified profile snippet relay-once, short MAC, adaptive pacing), PRESENCE (sealed to backend), GATEWAY_RELAY (opaque forward, internet shortcut) + scenarios 16-19 | P1B-4 | done | mesh-core/src/main/kotlin/com/ridehailing/mesh/app/ (22 tests); scenarios 16-19 all PASS local; baseline.json additive (existing 15 byte-identical); replay hole caught+fixed (D60); commits a1a055d, d73e26d |
| P1B-5 | mesh-core Stage 4 Android integration prep: sim-bridge topology engine wiring | P1B-4 | done | host bridge ad327cc (D72) + Stage 4 wiring bfe77a4: StackE2EBridgeTest real-time stack e2e over real sockets (2-hop flood byte-exact, flood-echo + dense multi-path dedup, loop prevention, QUOTE return leg); REAL transport write-corruption bug caught+fixed (writeLock); D73: backend mesh-gateway seam -> Phase 7; 111 tests green local task tier |

## Phase 2 (android shell) and beyond

| ID | Task | Depends | Status | Evidence |
|---|---|---|---|---|
| P2-1a | Android project scaffold: standalone android/ gradle root (AGP 8.7.3, Kotlin 2.0.21, wrapper 8.10.2, minSdk 26/target 34), :core-ui (Material3 theme light/dark + AppShell + AppMode + i18n en/fr/ar RTL-ready), :app-rider + :app-driver Compose shells, trivial unit tests (3/app) + locale-agnostic instrumented launch tests (P0-C surface); run-ui-tests.sh fixed for the standalone layout | P0-3 | done | commits e6fbec7, 426b1fc; FULL android-build CI contract verified locally (testDebugUnitTest + assembleDebug + assembleDebugAndroidTest both apps, APKs 8.7MB + ~986KB test APKs, aapt2 badging OK); actionlint clean |
| P2-1b | Navigation (Compose Navigation) + full theming pass + i18n completion (all user-facing strings en/fr/ar) | P2-1a | done | commit f3f0daa: core-ui ModeNavApp (NavHost + NavigationBar, singleTop + state save/restore, testTags), mode-aware theme (rider blue / driver amber, schema 2 pinned by unit tests), i18n en/fr/ar complete, navigation-switch instrumented tests both apps; CI-verified: forced full run 45462273542 (6/6 emulator shards green incl. navigation tests; setup-java fix 763a969 + shard-id fix 6315b22) |
| P2-1c | Hilt DI, Room+FTS (ride history/search), DataStore (session/theme), outbox v1 (offline-first write path) | P2-1b | todo | |
| P2-2 | MapLibre main screen against self-hosted tiles (CI compose) | P2-1c | todo | |
| P2-3 | Storyboard helper wired into app modules + test-rule; Roborazzi baselines dir | P2-1b | done | commits 6d09d2b, c711bee: android/storyboard module (StepStoryboard + StoryboardRule with zero-step E2 enforcement + Slug, 6/6 unit tests), apps wired + tests rewritten with step(); runner-args wiring bug fixed (numShards/shardIndex were silently dropped - sharding was a no-op; matrix collapsed to 1 shard until suites grow); screenrecord video + storyboard pull into the gallery (depth-safe layout, generate.js smoke-verified); docs/baselines/README.md; emulator evidence lands with the next android CI run |
| P2-4 | Auth UI against Phase 1 backend | P2-1c | todo | |
| P3-P11 | per Part B delivery plan | - | tracked as phases land; P0/P1 features in FEATURES.md | |

## Deferred / blocked

| ID | Item | Reason | Revisit |
|---|---|---|---|
| P0-8 | local SDK/AVD | sandbox: no KVM, 2 vCPU | Phase 2 start / environment change |
| X-06 Stage 6 | physical devices for BLE | none available in sandbox | per Part C: mark simulation/emulator-verified only, list unverified items |

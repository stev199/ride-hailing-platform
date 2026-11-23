# PART D: CI/CD AND PARALLEL TEST INFRASTRUCTURE (GITHUB ACTIONS)

> Saved from the master autonomous build prompt on 2026-11-23. Authoritative source: /home/z/my-project/upload/Pasted Content_1795430967449.txt. Precedence per prompt header: A overrides communication rules; C wins over B on mesh details; D wins on automation; E wins on reporting/visibility.

# PART D: CI/CD AND PARALLEL TEST INFRASTRUCTURE (GITHUB ACTIONS)
################################################################################

Phase 0 includes building the full CI skeleton below. From then on, "regression" (Verification step 9) means the GitHub Actions pipeline is green on the pushed commit. Locally green but CI red is NOT done. After each task: push, watch the run, fix failures under the retry policy, and link the run URL and artifacts in PROGRESS_LOG.md and FEATURES.md.
If no GitHub remote/token is available: record in BLOCKERS.md, build all workflows anyway, validate with actionlint and `act` (non-emulator jobs), run the same commands through /scripts/ci-local.sh, and mark CI evidence "not run on GitHub".

## D1. Principles
1. Run independent things in parallel: backend, API contract, admin web, Android unit/lint, rider UI shards, driver UI shards, mesh simulation, security scans. Use `needs:` only for real dependencies (build once, reuse artifacts).
2. Cancel superseded runs (`concurrency: group: ci-${{ github.ref }}, cancel-in-progress: true`).
3. Fast PR feedback (target <20 min), deeper nightly/release runs.
4. Least-privilege `permissions:`, pinned action versions, no secrets in logs, no secrets for fork PRs.
5. Same commands locally and in CI via /scripts and a Makefile (make test-backend, test-admin, test-android-ui, e2e, live).
6. Every failing job uploads diagnostics (D8).

## D2. Workflow files (.github/workflows/)
- ci.yml: on pull_request and push to main; path filters (dorny/paths-filter) skip unaffected components; a required `ci-summary` job always runs.
- nightly.yml: full device/API/locale matrix, load test, long mesh sim, dependency/security scans.
- release.yml: on tag v*.
- codeql.yml, dependency-review.yml, secret scan (gitleaks).
- live-view.yml: manual workflow for live emulator viewing (Part E5).
- dependabot.yml (Gradle, npm, Docker, Actions), PR template with the Definition of Done checklist, CODEOWNERS, /docs/CI.md documenting branch protection with required check `ci-summary`.

## D3. Job graph for ci.yml (parallel unless noted)
1. lint-and-static: ktlint, detekt, Android Lint, ESLint, tsc, actionlint, OpenAPI lint (spectral).
2. backend-test: PostgreSQL+PostGIS and Redis service containers (or Testcontainers); unit + integration tests, migrations up/down, RBAC matrix, ledger invariant, driver-DTO parity contract tests, WebSocket tests; JaCoCo coverage with a minimum threshold recorded in DECISIONS.md.
3. api-contract: start backend with docker compose; Schemathesis (or Dredd) against the OpenAPI spec, negative/auth fuzzing, k6 smoke with p95 thresholds.
4. admin-web-test: Vitest/RTL, then Playwright E2E against the real compose stack with seeded data, Chromium + Firefox shards, video/trace on failure, axe accessibility checks, screenshot comparison for key pages (light/dark, EN/FR/AR-RTL).
5. mesh-core-sim: pure JVM; entire Part C Stage 3 suite with fixed seeds; metrics/charts; fail on >10% regression vs /docs/evidence/mesh-sim/baseline.json.
6. android-build: assemble debug apps + androidTest APKs for rider and driver (flavors or entry modes), upload artifacts, Gradle cache (gradle/actions/setup-gradle); unit tests and Compose screenshot tests (Roborazzi/Paparazzi) run here.
7. android-ui-rider (needs android-build): matrix of shards, each on its own emulator with its own isolated backend stack.
8. android-ui-driver (needs android-build): same for driver, in parallel with rider shards.
9. e2e-cross-actor (needs android-build): ONE runner hosts backend + admin web + TWO emulators (rider and driver) + a browser, running real cross-actor scenarios simultaneously (D5).
10. security: CodeQL (Kotlin/Java, JS/TS), dependency-review, Trivy (filesystem + images), gitleaks.
11. report-publish (needs all, `if: always()`): builds the report gallery (Part E6) and publishes it.
12. ci-summary (needs all, `if: always()`): Markdown table (tests, coverage, flaky retries, durations, artifact and report links) to $GITHUB_STEP_SUMMARY and an updating PR comment; fails if any required job failed or was cancelled unexpectedly. It is the only required status check.

## D4. Parallel emulator UI tests (rider and driver), sharded
- Linux runners only. First step in every emulator job: enable KVM
  (echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules; sudo udevadm control --reload-rules; sudo udevadm trigger --name-match=kvm) and fail clearly if /dev/kvm is missing. Record runner CPU/RAM in the job summary.
- Use reactivecircus/android-emulator-runner: aosp_atd or google_apis image, x86_64, `disable-animations: true`, `emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none -camera-front none`, AVD snapshot cache (actions/cache keyed by api+arch+target+profile; a cache-warming job writes it on main), tuned ram/heap.
- PR matrix: { app: [rider, driver] } x { shard: [0..N-1] }, API 34, Pixel-class phone. Start N=3, tune from timing data so each shard is <~10 min. Concurrent emulators = 2*N. Sharding via `numShards`/`shardIndex` instrumentation arguments; AndroidX Test Orchestrator with clearPackageData.
- Nightly matrix: add API 26, 30, 34 (and latest stable preview), small phone + large phone + tablet, locales en/fr/ar (RTL), dark mode, font scale 1.3, a low-RAM profile; `fail-fast: false`.
- Per-shard backend isolation: each emulator job starts its own docker compose stack (backend, Postgres/PostGIS, Redis, mock OTP dev inbox, mock gateway services, tile/OSRM stubs or tiny city extract), seeded via /scripts/seed-test.sh with a unique namespace. Apps use base URL http://10.0.2.2:<port>. Wait for health endpoints before tests.
- Determinism: fixed mock GPS via `adb emu geo fix` or the emulator console (scripted routes for a moving driver), fixed clock/time zone, animations off, test-only feature flags, idempotent seed data, no external network except allowed mocks.
- Test content: rider flows (onboarding, login per enabled method, booking, scheduling, concurrent trips, cancel, wallet/points transfer, history, offline maps and airplane-mode behavior, estimate vs final quote UI, offline-mesh UI with SimulatedMeshTransport); driver flows (onboarding/doc upload, duty toggle, request accept/decline countdown, navigation, trip lifecycle, earnings, document-expiry alerts, always-online/degraded banner); permission dialogs via UiAutomator; process death/restore; rotation; RTL screenshots.
- Flakiness: retry failed tests at most 2 times, report every retried test as "flaky" and list it in /docs/FLAKY.md. A test flaky 3 runs in a row must be fixed or quarantined with a BACKLOG entry (never silently deleted or loosened).
- Always collect: step storyboard screenshots (Part E2), failure screenshots, video, full logcat, JUnit XML, HTML reports, `dumpsys meminfo/gfxinfo`, crash/ANR/StrictMode scan (fail the job on FATAL EXCEPTION, ANR, or StrictMode violation).

## D5. Cross-actor E2E: two emulators + backend + admin, simultaneously
- One runner (4+ vCPU, 14+ GB RAM preferred; on smaller runners use ATD images, smaller RAM, -no-snapshot-save and document it; if still infeasible, split into sequential sub-scenarios and record the degradation):
 1. docker compose up: backend, Postgres/PostGIS, Redis, mocks, mesh-gateway.
 2. Emulator A (rider, -port 5554) and B (driver, -port 5556) in the background via the SDK `emulator` binary (two AVDs via avdmanager); wait for `sys.boot_completed`; disable animations; install rider APK on A and driver APK on B (`adb -s emulator-5554 ...`).
 3. Admin web (built, served) and a Playwright browser session logged in as admin with the live map open.
- Orchestration: a small Kotlin/JVM or Node orchestrator in /e2e drives three actors concurrently (rider and driver via `adb -s ... shell am instrument` UiAutomator tests, admin via Playwright), synchronized through a test-only backend endpoint /test/barrier/{name} (test profile only; a test proves it is absent from release builds). RiderE2E and DriverE2E are separate instrumentation packages running in parallel.
- Mandatory scenarios (each asserts UI state, API responses, DB rows, WebSocket events, admin view, audit log, event hash chain):
 1. Online happy path: rider books, driver sees the request, accepts, moves via scripted geo fixes, rider sees live tracking, admin sees trip and both actors on the live map, completion, receipt, ratings, balanced ledger.
 2. Specific-driver booking and cancel by rider and by driver with fee rules.
 3. Scheduled trip auto-dispatch and concurrent second trip.
 4. Points transfer rider to rider with PIN, limits, fees; admin ledger matches.
 5. Document verification: driver uploads, admin approves in the browser, driver app updates live; reject then re-upload.
 6. Admin bans a rider mid-session: rider app logged out and blocked immediately; admin config change (max hops, concurrent-trip limit) propagates live to apps.
 7. Offline-origin booking via the SimulatedMeshTransport bridge (D6): offline rider, 1-3 hops, gateway driver, backend, a different assigned driver; admin sees origin and path; driver-blindness parity (automated screenshot diff + JSON schema diff vs scenario 1).
 8. Driver connection loss: airplane mode on driver emulator, "degraded" banner, admin status change, grace-period expiry sets off-duty.
 9. Network flakiness (`adb emu network speed/delay`), app killed mid-trip and restored (`am force-stop`, relaunch), no duplicate trips.
- On failure collect artifacts from BOTH emulators, backend logs (compose logs), DB dump of relevant tables, Playwright trace/video, orchestrator timeline, all time-aligned in one timeline.json.

## D6. Mesh in CI
- /mesh-core simulation runs as its own fast job on every commit.
- For emulator jobs, SimulatedMeshTransport instances connect through a host-side "sim bridge" process (reachable at 10.0.2.2) applying scenario topology, loss, latency. Nightly runs 1-, 2-, 3-hop topologies with 2-4 emulators (matrix over topology); PRs run 1- and 2-hop cases using the two-emulator job plus virtual nodes inside the bridge.
- Real Bluetooth cannot run on GitHub runners. Never claim BLE verification from CI.

## D7. CD
- On tag v*.*.*: build release backend and admin Docker images, push to GHCR, build Android release APK + AAB signed with a keystore from encrypted repository secrets (KEYSTORE_B64, KEY_ALIAS, KEY_PASSWORD; never committed; if missing produce unsigned builds and say so), SBOM (CycloneDX), Trivy on images, GitHub Release with conventional-commit changelog, checksums, artifacts.
- Staging deploy workflow (workflow_dispatch, optionally auto on main) via docker compose over SSH to a user-provided host, with environment protection, post-deploy smoke test, automatic rollback. Without host secrets, skip with a note and validate the compose stack in CI.
- Migrations tested forward and backward in CI; release refuses to publish if a migration test fails.
- Semantic versions; versionCode from tag/run number; min-app-version check tested.

## D8. Artifacts and reporting
Upload (retention 14 days; nightly 30): JUnit XML, HTML reports, JaCoCo, Playwright reports/traces/videos, emulator screenshots/videos/logcat, backend and compose logs, mesh metrics/charts, APK/AAB, SBOM. Track CI health in /docs/CI.md (pass rate, p50/p95 duration, flaky list, minutes per run). Keep curated evidence snapshots from main in /docs/evidence/ci/ (links or small screenshots only).

## D9. Performance and cost
Cache Gradle, npm/pnpm, Docker layers (buildx + gha cache), AVD snapshots, Playwright browsers. Build APKs once and share via artifacts. Path filters, concurrency cancel, skip emulator jobs for docs-only changes; PRs use one API level, nightly the full matrix. Record per-run minutes in /docs/CI.md; warn in DECISIONS.md if private-repo free minutes (2,000/month on the Free plan) would be exhausted and propose mitigations (public repo, larger runners, self-hosted KVM runners, fewer shards). Never require paid third-party test clouds (Firebase Test Lab, BrowserStack); optional adapters only.

## D10. CI Definition of Done (append to the Verification Protocol)
A feature/phase is done only when: its tests are wired into the right CI jobs (rider UI, driver UI, cross-actor, admin, API, mesh-sim) and the pipeline is green on GitHub (or marked "CI not run on GitHub" with the reason); new UI features have an instrumented test using the Step Storyboard helper plus an RTL + dark-mode screenshot test, and a cross-actor scenario if cross-actor; new API endpoints are in OpenAPI with contract, RBAC and negative tests; new admin screens have Playwright + axe tests; ci-summary shows no new flaky tests, no coverage drop beyond threshold, no new security findings above the agreed severity; and the report gallery for the run shows the new feature's screenshots and videos.

## D11. Phase 0 backlog (create immediately)
1. Makefile + /scripts (ci-local.sh, seed-test.sh, start-emulators.sh, wait-for-boot.sh, collect-artifacts.sh, run-ui-tests.sh).
2. ci.yml skeleton with all jobs (trivial tests first), filled in as features land.
3. AVD snapshot cache-warming workflow.
4. e2e-orchestrator skeleton + /test/barrier endpoint (test profile only) + test proving absence in release.
5. Sim bridge process for emulators.
6. Test Viewer, Step Storyboard helper, report generator (Part E).
7. ci-summary, report-publish, PR comment.
8. release.yml, nightly.yml, live-view.yml, security workflows, dependabot, PR template, CODEOWNERS, /docs/CI.md.
9. First green pipeline: trivial rider test + trivial driver test in parallel on two emulators, plus backend, admin, mesh-sim jobs; produce a report gallery with storyboard screenshots and a video. Record run URL and report URL as evidence.

## D12. Reference skeleton (starting point, not final)
```yaml
android-ui:
  needs: android-build
  runs-on: ubuntu-latest
  timeout-minutes: 40
  strategy:
    fail-fast: false
    matrix:
      app: [rider, driver]
      shard: [0, 1, 2]
  steps:
    - uses: actions/checkout@v4
    - name: Enable KVM
      run: |
        echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
        sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
        test -e /dev/kvm || { echo "KVM missing"; exit 1; }
    - uses: actions/download-artifact@v4
      with: { name: android-apks }
    - name: Start isolated backend
      run: make up-test && ./scripts/wait-for-health.sh && ./scripts/seed-test.sh ${{ matrix.app }}-${{ matrix.shard }}
    - uses: reactivecircus/android-emulator-runner@v2
      with:
        api-level: 34
        target: aosp_atd
        arch: x86_64
        disable-animations: true
        emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim
        script: ./scripts/run-ui-tests.sh ${{ matrix.app }} ${{ matrix.shard }} 3
    - uses: actions/upload-artifact@v4
      if: always()
      with:
        name: ui-${{ matrix.app }}-${{ matrix.shard }}
        path: artifacts/**
```

################################################################################

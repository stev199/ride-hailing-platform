# PART E: TEST VISIBILITY (SEE EVERY TEST, SCREEN, AND RUN)

> Saved from the master autonomous build prompt on 2026-11-23. Authoritative source: /home/z/my-project/upload/Pasted Content_1795430967449.txt. Precedence per prompt header: A overrides communication rules; C wins over B on mesh details; D wins on automation; E wins on reporting/visibility.

# PART E: TEST VISIBILITY (SEE EVERY TEST, SCREEN, AND RUN)
################################################################################

Goal: I must be able to SEE what the tests do, live or recorded, without access to your machine: screenshots per step, videos, a live multi-screen viewer, and a browsable report for every CI run. Build this in Phase 0 and keep it working for every later feature.

## E1. Principles
- Every UI test produces visible evidence: a storyboard of screenshots, a video, logs, and a status.
- Evidence is organized by run/app/shard/test and is browsable in one report; nothing requires reading raw logs.
- Use only free tooling. Third-party live tunnels are optional, opt-in, token-protected, and off by default.
- Use only fake seeded data in screenshots and videos (no real PII).

## E2. Step Storyboard (screenshots of every step)
- Provide a shared test helper (Android androidTest library, plus an equivalent for Playwright) such as `step("Tap Request ride") { ... }` that: (a) logs the step name with timestamp to the viewer/orchestrator, (b) captures a screenshot after the step (and before, for steps tagged `before=true`) via UiAutomator `takeScreenshot`, (c) names files `<run>/<app>/<shard>/<test>/<NN>-<slug>.png`, (d) on failure also dumps the view hierarchy (`uiautomator dump`) and the last 200 logcat lines.
- Every instrumented UI test MUST use `step()` for each meaningful action so the storyboard reads like a story. A lint/test rule fails the build if an androidTest class under /ui-tests has no `step()` calls.
- Compose screenshot tests (Roborazzi or Paparazzi) record baselines in /docs/baselines (small PNGs, committed); CI compares and produces diff images (expected / actual / diff) for the gallery. Baseline changes require an explicit PR note.
- Variant captures for key screens: light/dark, EN/FR/AR (RTL), font scale 1.0 and 1.3, small and large phones.

## E3. Video recording
- Per UI test: `adb shell screenrecord` in rolling segments (the tool limit is about 3 minutes per file; start a new segment and stitch them), pulled and converted with ffmpeg to small MP4/WebM (e.g., 720p, 10-15 fps, CRF-tuned). Keep videos for failed tests always and for passed tests when `RECORD_ALL=1` (default on for nightly and cross-actor, off for PR shards to save time; always record the first run of each new feature).
- Playwright: video + trace for admin tests (always for the cross-actor job).
- Cross-actor "tri-view" video: composite rider emulator + driver emulator + admin browser side by side (ffmpeg hstack/xstack), with a timeline overlay of step names and backend events, for each mandatory D5 scenario. This is the main demo artifact.
- Size budgets: warn if a single video exceeds 25 MB or a run's evidence exceeds 500 MB; downscale automatically.

## E4. Test Viewer (/tools/test-viewer): live and replay
Build a small web app (Node + Express + WebSocket, no paid services), started with `make viewer` (replay) or `make live` (boots emulators, starts the viewer, runs tests), default http://localhost:7070.
- LIVE mode: for each connected adb serial, stream the device screen as MJPEG (loop `adb exec-out screencap -p`, downscaled, ~3-5 fps; optional upgrade to scrcpy/ws-scrcpy for smoother video when available). Show rider and driver emulators side by side, plus the admin browser (Playwright CDP screencast or periodic screenshots), plus a live panel with: current test and step name (fed by a RunListener/`step()` helper POSTing events to the viewer via 10.0.2.2), pass/fail counters, logcat tail per device, backend log tail, and the cross-actor timeline.
- Controls: pause/resume, slow-motion (`STEP_DELAY_MS` injected between steps), step-by-step mode (`STEP_MODE=1`: tests wait for "Next" in the viewer; useful for demos), screenshot-now, download current run.
- REPLAY mode: load any run directory (local or downloaded CI artifact) and scrub through storyboard, video, logs, and the timeline.json in sync.
- Also support the raw emulator window for local runs: if a display exists run emulators windowed; otherwise optional Xvfb + x11vnc + noVNC on a documented port so I can view the real emulator window in a browser.
- The viewer must work identically on my machine and on a CI runner.

## E5. Live view from GitHub Actions (opt-in)
- Workflow `live-view.yml` (workflow_dispatch) with inputs: scenario (e.g., rider-booking, cross-actor-all), slow_motion_ms (default 800), step_mode (bool), keep_alive_minutes (default 10, max 30).
- The job enables KVM, starts the stack, two emulators, the Test Viewer, then exposes the viewer through a free tunnel (Cloudflare quick tunnel via `cloudflared tunnel --url http://localhost:7070`; alternatives: Tailscale/tmate documented), protected by a random access token generated per run (URL contains the token; viewer rejects requests without it). It writes the URL to the job summary AND as a workflow annotation as soon as it is ready, and keeps the job alive for keep_alive_minutes after tests finish so I can inspect the final state.
- Safety: never run live view on fork PRs, never expose secrets, never expose Docker/ADB ports directly (only the viewer), token required, auto-teardown at timeout. Document that the tunnel is third-party and optional.
- If tunnels are blocked or unavailable, say so and rely on E6 reports and E3 videos instead.

## E6. Report gallery for every CI run
- A generator (/tools/report) builds a static, self-contained site per run: index with the job matrix (app x shard x API x locale) and status; per-test cards (name, status, duration, flaky flag, storyboard filmstrip, video, failure hierarchy dump, logcat excerpt, screenshot diffs expected/actual/diff); cross-actor scenarios with tri-view video and a clickable timeline (UI, API, DB, WebSocket, admin events); Playwright report and traces; axe results; coverage; mesh-sim charts (delivery, latency, path stretch vs baseline); security findings summary.
- Publish: (1) GitHub Pages at /runs/<run-id>/ and /latest/ (keep the last 30 runs, prune older), (2) always upload the same report as an artifact `test-report-<run-id>` as a single self-contained zip/HTML (open index.html locally) as the fallback when Pages is unavailable (e.g., private repo on a plan without Pages) and say which mode was used, (3) the PR comment (updated in place) with a status table, the report link, and 3-4 inline key screenshots, (4) the job summary with the same.
- The gallery must be usable on a phone (responsive) and in light/dark.

## E7. Local workflow
- `make live`: boots two emulators (windowed if a display exists, otherwise headless with the viewer), starts backend + admin + viewer, runs rider + driver + admin tests concurrently, opens/prints the viewer URL.
- `make report`: builds the gallery from /artifacts and prints the path/URL.
- Document in README how to use scrcpy for a raw smooth mirror when available.

## E8. VISIBILITY CONTRACT (YOUR REPORTING OBLIGATIONS TO ME)
After EVERY completed task, in addition to the 1-3 line status, give: CI run URL, report gallery URL (or artifact name and local path), and the 2-4 most informative screenshots/video paths for that task (view them yourself first; if the chat supports images, attach/show them). At every phase gate, additionally produce: the tri-view demo video for the phase's main flow, a one-page /docs/evidence/phase-N/INDEX.html gallery, and the live-view dispatch command I can run (`gh workflow run live-view.yml -f scenario=<...>`). If something could not be shown (no KVM, no tunnel, no Pages), say exactly why and what I can see instead. Never describe a visual result you did not actually look at.

## E9. Definition of Done additions
A UI feature is done only when its storyboard, video (where required by E3), and RTL/dark screenshots appear in the run's report gallery, you have opened and visually reviewed them, and any layout defect found was fixed and recaptured.

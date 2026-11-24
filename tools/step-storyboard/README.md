# Step Storyboard helper (Part E2)

`step("Tap Request ride") { ... }` for every meaningful action in UI tests. Logs the step to the Test Viewer (E4), captures before/after screenshots, dumps view hierarchy + last 200 logcat lines on failure.

Layout contract: `<run>/<app>/<shard>/<test>/<NN>-<slug>.png`.

## Android (WIRED 2026-11-24, P2-3: `android/storyboard` module is the live copy)

`android/storyboard/src/main/kotlin/com/ridehailing/test/storyboard/StepStoryboard.kt` (this
file is the Phase 0 reference; the module copy is authoritative for apps):

- `StoryboardRule(app)` — JUnit rule wrapping the whole test: begin/finish events, per-step
  capture, FAIL evidence (screenshot + hierarchy + logcat), and a zero-step AssertionError.
  Declare it AFTER the compose rule (higher `@get:Rule order`) so it wraps it.
- `step(name, before = false) { assertions/actions }` wraps the lambda, captures via UiAutomator
  (2.3.0 File-based overloads), POSTs to the viewer at `http://10.0.2.2:7070/api/step`.
- A passing test with zero `step()` calls FAILS (E2: the storyboard must read like a story).

## Playwright (admin-web, Phase 2)

`tools/step-storyboard/playwright-helper.js` — `withStep(page, name, fn)` posts the same event shape, saves screenshot to the artifact dir, and captures trace on failure.

## Variant captures (E2)

Key screens captured in light/dark, EN/FR/AR-RTL, font scale 1.0/1.3, small/large phone — the CI matrix (nightly.yml) drives the variants; the helper names files `<NN>-<slug>-<variant>.png`.

## Compose baselines (E2)

Roborazzi/Paparazzi baselines live in `/docs/baselines` once Compose code exists (Phase 2); CI produces expected/actual/diff triples into the gallery; baseline changes require an explicit PR note.

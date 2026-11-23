# Step Storyboard helper (Part E2)

`step("Tap Request ride") { ... }` for every meaningful action in UI tests. Logs the step to the Test Viewer (E4), captures before/after screenshots, dumps view hierarchy + last 200 logcat lines on failure.

Layout contract: `<run>/<app>/<shard>/<test>/<NN>-<slug>.png`.

## Android (androidTest source; wired into the app modules in Phase 2)

`StepStoryboard.kt` — drop-in Kotlin object used from instrumentation tests:

- `StepStoryboard.begin(testId, test, app, shard)` registers the test with the viewer.
- `step(name, before = false) { assertions/actions }` wraps the lambda, captures via UiAutomator, POSTs to the viewer at `http://10.0.2.2:7070/api/step`.
- On failure: `uiautomator dump` + `logcat -d -t 200` captured into the artifact dir; rethrows.
- A lint/test rule (added with the app modules) fails any androidTest class under the UI-test source set with zero `step()` calls.

## Playwright (admin-web, Phase 2)

`tools/step-storyboard/playwright-helper.js` — `withStep(page, name, fn)` posts the same event shape, saves screenshot to the artifact dir, and captures trace on failure.

## Variant captures (E2)

Key screens captured in light/dark, EN/FR/AR-RTL, font scale 1.0/1.3, small/large phone — the CI matrix (nightly.yml) drives the variants; the helper names files `<NN>-<slug>-<variant>.png`.

## Compose baselines (E2)

Roborazzi/Paparazzi baselines live in `/docs/baselines` once Compose code exists (Phase 2); CI produces expected/actual/diff triples into the gallery; baseline changes require an explicit PR note.

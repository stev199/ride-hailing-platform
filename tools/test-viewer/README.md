# Test Viewer (Part E4)

Live and replay evidence viewer. Free tooling only (Node + Express + ws).

## Run

```bash
make viewer            # http://localhost:7070
REPLAY_DIR=/path/to/artifacts make viewer   # replay mode: also exposes /replay/<run>/...
VIEWER_TOKEN=<random> ...                    # token-protect (required when tunneled, E5)
```

## LIVE mode

- Each connected `adb` serial gets an MJPEG panel (~1.5 fps `screencap` polling; upgrade path: ws-scrcpy for smooth video when available).
- The Step Storyboard helper POSTs to `POST /api/step` with `{testId, test, app, shard, step, screenshot?}` and `{event:"test-end", status}`.
- Log tails: `POST /api/log {source|device, line}` from the orchestrator (backend logs, logcat).
- Counters update live; tests table shows last step per test.

## REPLAY mode

Point `REPLAY_DIR` at a run/artifact directory (e.g. downloaded `test-report-<run-id>`); browse `/replay/<run>/storyboard/**` and any `timeline.json` alongside the live panels.

## Controls (orchestrator-injected)

- `STEP_DELAY_MS` slow-motion between steps, `STEP_MODE=1` step-by-step (tests wait for viewer "Next" event) — wired by /e2e orchestrator in Phase 2.
- `screenshot-now`: `adb -s <serial> exec-out screencap -p > shot.png` from the panel URL.

## CI usage

live-view.yml (E5) boots the stack + viewer on the runner and exposes it through a Cloudflare quick tunnel with a per-run random token; the URL is written to the job summary and an annotation. Never on fork PRs; auto-teardown at timeout.

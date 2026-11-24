#!/usr/bin/env bash
# Collect per-shard artifacts (D4/D8): screenshots, storyboards, videos, logcat, JUnit XML, dumpsys.
# Called after UI test shards; always collects, even on failure.
set -uo pipefail
APP="${1:-all}"
SHARD="${2:-0}"
OUT="artifacts/ui-${APP}-${SHARD}"
mkdir -p "$OUT/logcat" "$OUT/screens" "$OUT/dumpsys" "$OUT/storyboard"

# Storyboard step screenshots (E2): the instrumentation copies them to
# /data/local/tmp/storyboard/<app>/<test-slug>/ DURING the test (StoryboardRule.persist)
# because AGP uninstalls the app after connected tests, wiping /sdcard/Android/data/<pkg>
# on API 30+. Pulled layout keeps the gallery walk depth limit (tools/report/generate.js):
# artifacts/ui-<app>-<shard>/storyboard/<app>/<test-slug>/*.png.
adb pull /data/local/tmp/storyboard/. "$OUT/storyboard/" >/dev/null 2>&1 \
  || echo "[warn] no storyboard artifacts in /data/local/tmp/storyboard"
adb shell rm -rf /data/local/tmp/storyboard >/dev/null 2>&1 || true

for s in $(adb devices | awk '/emulator-/{print $1}'); do
  adb -s "$s" shell screencap -p > "$OUT/screens/final-$s.png" 2>/dev/null || true
  adb -s "$s" logcat -d > "$OUT/logcat/$s.log" 2>/dev/null || true
  adb -s "$s" shell dumpsys meminfo > "$OUT/dumpsys/meminfo-$s.txt" 2>/dev/null || true
  adb -s "$s" shell dumpsys gfxinfo > "$OUT/dumpsys/gfxinfo-$s.txt" 2>/dev/null || true
done

# Crash/ANR/StrictMode scan (D4: fail the job on FATAL EXCEPTION / ANR / StrictMode violations)
VIOLATIONS=$(rg -l "FATAL EXCEPTION|ANR in|StrictMode violation" "$OUT/logcat" 2>/dev/null || true)
if [ -n "$VIOLATIONS" ]; then
  echo "::error::crash/ANR/StrictMode violations found: $VIOLATIONS"
  exit 1
fi
echo "[ok] artifacts in $OUT"

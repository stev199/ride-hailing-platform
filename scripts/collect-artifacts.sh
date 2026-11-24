#!/usr/bin/env bash
# Collect per-shard artifacts (D4/D8): screenshots, storyboards, videos, logcat, JUnit XML, dumpsys.
# Called after UI test shards; always collects, even on failure.
set -uo pipefail
APP="${1:-all}"
SHARD="${2:-0}"
OUT="artifacts/ui-${APP}-${SHARD}"
mkdir -p "$OUT/logcat" "$OUT/screens" "$OUT/dumpsys" "$OUT/storyboard"

# Storyboard step screenshots (E2): the instrumentation writes PNGs into its own external
# files dir, and run-ui-tests.sh DRAINS them continuously while the tests run (AGP uninstalls
# the app after connected tests, wiping /sdcard/Android/data/<pkg> on API 30+; UiAutomation
# shell = app uid on aosp_atd, so no /data/local/tmp bridge is possible). This final drain
# catches anything the poll loop raced on; the gallery-safe layout is
# artifacts/ui-<app>-<shard>/storyboard/<app>/<test-slug>/*.png (walk depth limit).
for pair in com.ridehailing.rider:rider com.ridehailing.driver:driver; do
  pkg="${pair%%:*}"; app="${pair##*:}"
  adb pull "/sdcard/Android/data/$pkg/files/storyboard" "$OUT/sb-$app" >/dev/null 2>&1 || continue
  cp -R "$OUT/sb-$app/storyboard/." "$OUT/storyboard/$app/" 2>/dev/null || true
  rm -rf "$OUT/sb-$app"
done
echo "[ok] storyboard evidence: $(find "$OUT/storyboard" -name '*.png' 2>/dev/null | wc -l) pngs"

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

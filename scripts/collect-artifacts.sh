#!/usr/bin/env bash
# Collect per-shard artifacts (D4/D8): screenshots, storyboards, videos, logcat, JUnit XML, dumpsys.
# Called after UI test shards; always collects, even on failure.
set -uo pipefail
APP="${1:-all}"
SHARD="${2:-0}"
OUT="artifacts/ui-${APP}-${SHARD}"
mkdir -p "$OUT/logcat" "$OUT/screens" "$OUT/dumpsys" "$OUT/storyboard"

# Storyboard step screenshots (E2): instrumentation wrote them under the app-under-test's
# external files dir (<...>/files/storyboard/<test-slug>/<NN>-<step-slug>.png). adb root was
# requested by run-ui-tests.sh; when it failed (non-rooted device), fall back to run-as tar
# for the debuggable app-under-test. Pulled layout keeps the gallery walk depth limit
# (tools/report/generate.js): artifacts/ui-<app>-<shard>/storyboard/<app>/<test-slug>/*.png.
for pkg in com.ridehailing.rider com.ridehailing.driver; do
  app="${pkg##*.}"
  mkdir -p "$OUT/storyboard/$app"
  if adb pull "/sdcard/Android/data/$pkg/files/storyboard/." "$OUT/storyboard/$app/" >/dev/null 2>&1; then
    echo "[ok] storyboard pulled for $pkg"
  else
    rm -rf "/tmp/sb-${pkg}"; mkdir -p "/tmp/sb-${pkg}"
    if adb exec-out run-as "$pkg" tar -cf - files/storyboard 2>/dev/null | tar -xf - -C "/tmp/sb-${pkg}" 2>/dev/null; then
      cp -R "/tmp/sb-${pkg}/files/storyboard/." "$OUT/storyboard/$app/" 2>/dev/null || true
      echo "[ok] storyboard pulled via run-as for $pkg"
    else
      echo "[warn] no storyboard artifacts for $pkg"
    fi
    rm -rf "/tmp/sb-${pkg}"
  fi
done

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

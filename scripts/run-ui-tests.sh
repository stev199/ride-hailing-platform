#!/usr/bin/env bash
# Run sharded instrumented UI tests for an app (D4). Sharding via AndroidJUnitRunner args
# (-e numShards/-e shardIndex are wired into the app defaultConfigs since P2-3 - previously
# the -P properties had no gradle consumer and sharding was a silent no-op). Suites smaller
# than the shard count would leave empty shards (connectedAndroidTest fails on zero tests),
# so ci.yml collapses to 1 shard until suites grow.
#
# Evidence pipeline (P2-3, run 45473604435 lessons):
# - APKs are built+installed FIRST (unrecorded gradle run); the recorded connected run then
#   covers only the test execution, so a single 180 s recording window is enough regardless
#   of gradle dependency download times.
# - Storyboards: the instrumentation copies them to /data/local/tmp/storyboard DURING the
#   test (StoryboardRule.persist) - AGP uninstalls the app after connected tests, wiping
#   /sdcard/Android/data/<pkg> on API 30+, so /data/local/tmp is the only reliable transport.
# Usage: run-ui-tests.sh <rider|driver|all> <shardIndex> <numShards>
set -euo pipefail
APP="${1:-all}"
SHARD="${2:-0}"
NUM="${3:-1}"
RECORD_ALL="${RECORD_ALL:-0}"
RUN_ID="${GITHUB_RUN_ID:-local}"

# AGP 8.7.x refuses JDK > 21 (e.g. runner defaults). Prefer an explicit JAVA_HOME;
# fall back to the GitHub-hosted runner's preinstalled JDK 17 if unset.
if [ -z "${JAVA_HOME:-}" ] && [ -n "${JAVA_HOME_17_X64:-}" ]; then
  export JAVA_HOME="$JAVA_HOME_17_X64"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Storyboard transport is run-as based (see pull_storyboards) - no adb root needed.
# Clean slate so stale storyboards/recordings from a previous run cannot leak into the gallery
# (run-as = app uid, absolute external path; fails harmlessly before the app is installed).
adb shell "run-as com.ridehailing.rider rm -rf /storage/emulated/0/Android/data/com.ridehailing.rider/files/storyboard 2>/dev/null ; run-as com.ridehailing.driver rm -rf /storage/emulated/0/Android/data/com.ridehailing.driver/files/storyboard 2>/dev/null" >/dev/null 2>&1 || true
adb shell rm -rf /data/local/tmp/ui-rec.mp4 /data/local/tmp/ui-probe.mp4 >/dev/null 2>&1 || true

VIDEO_DIR="artifacts/ui-${APP}-${SHARD}/video"
mkdir -p "$VIDEO_DIR"
REC_MODE="none"
REC_STARTED=0

start_recorder() {
  # local $1 = module id, for the artifact file name
  # Guest screenrecord can silently die on some emulator builds (file never created), so
  # a 3 s probe with surfaced stderr gates it before trusting it for the real run.
  if adb shell command -v screenrecord >/dev/null 2>&1; then
    local probe_err probe_size
    probe_err=$(adb shell screenrecord --time-limit 3 /data/local/tmp/ui-probe.mp4 2>&1 || true)
    probe_size=$(adb shell stat -c %s /data/local/tmp/ui-probe.mp4 2>/dev/null | tr -d '\r' || echo 0)
    if [ "${probe_size:-0}" -gt 1000 ]; then
      REC_MODE="guest"
      adb shell screenrecord --bit-rate 1000000 --time-limit 180 /data/local/tmp/ui-rec.mp4 >/dev/null 2>&1 &
    else
      echo "[warn] guest screenrecord probe failed (size=${probe_size:-none}): ${probe_err:0:200}"
      REC_MODE=""
    fi
  else
    REC_MODE=""
  fi
  if [ -z "$REC_MODE" ]; then
    if adb emu screenrecord start --time-limit 180 "$VIDEO_DIR/ui-rec.webm" >/dev/null 2>&1; then
      # Console screenrecord runs inside the emulator binary (host-side capture) - works
      # even when the guest binary cannot record.
      REC_MODE="console"
    else
      REC_MODE="none"
      echo "[warn] no screen recorder available - no video evidence for $1 this shard"
    fi
  fi
  REC_STARTED=$(date +%s)
}

stop_recorder() {
  # local $1 = module id
  case "$REC_MODE" in
    guest)
      # SIGINT (2) makes screenrecord finalize the current file; SIGKILL would truncate it.
      adb shell pkill -2 screenrecord >/dev/null 2>&1 \
        || adb shell kill -2 "$(adb shell pidof screenrecord | tr -d '\r')" >/dev/null 2>&1 || true
      sleep 2
      echo "[diag] recording file: $(adb shell ls -la /data/local/tmp/ui-rec.mp4 2>&1 | tr -d '\r')"
      if adb pull /data/local/tmp/ui-rec.mp4 "$VIDEO_DIR/ui-$1-${SHARD}.mp4" >/dev/null 2>&1 \
        && [ -s "$VIDEO_DIR/ui-$1-${SHARD}.mp4" ]; then
        echo "[ok] video pulled: $(stat -c %s "$VIDEO_DIR/ui-$1-${SHARD}.mp4" 2>/dev/null || echo '?') bytes"
      else
        echo "[warn] video pull failed or empty for $1"
      fi
      adb shell rm -f /data/local/tmp/ui-rec.mp4 /data/local/tmp/ui-probe.mp4 >/dev/null 2>&1 || true
      ;;
    console)
      # The emulator finalizes the host-side webm at the time limit (no documented stop
      # command); wait for a size-stable file, bounded, so artifact upload never races it.
      local waited=0
      while [ "$waited" -lt 210 ]; do
        local s1 s2
        s1=$(stat -c %s "$VIDEO_DIR/ui-rec.webm" 2>/dev/null || echo 0)
        sleep 5
        waited=$((waited + 5))
        s2=$(stat -c %s "$VIDEO_DIR/ui-rec.webm" 2>/dev/null || echo 0)
        if [ "$s1" -gt 0 ] && [ "$s1" = "$s2" ]; then break; fi
      done
      if [ -s "$VIDEO_DIR/ui-rec.webm" ]; then
        mv "$VIDEO_DIR/ui-rec.webm" "$VIDEO_DIR/ui-$1-${SHARD}.webm" 2>/dev/null || true
      fi
      ;;
  esac
  echo "[ok] video evidence mode=$REC_MODE for $1"
}

# Storyboard transport (P2-3, runs 45473604435..45482463962 root causes):
# 1) UiAutomation shell commands run as the APP uid on aosp_atd (empty output, no effect -
#    SELinux denies /data/local/tmp) -> no in-test bridge;
# 2) AGP uninstalls the app after connectedDebugAndroidTest, wiping /sdcard/Android/data/<pkg>
#    -> post-test pulls find nothing;
# 3) plain adb pull of another app's Android/data is denied by scoped storage (non-root shell).
# FIX: the app under test is DEBUGGABLE -> run-as (app uid) can tar its own external files dir,
# and run-ui-tests.sh drains it WHILE the tests run (backgrounded gradle), before the wipe.
OUT_DIR="artifacts/ui-${APP}-${SHARD}"
mkdir -p "$OUT_DIR/storyboard/rider" "$OUT_DIR/storyboard/driver"
pull_storyboards() {
  local pkg="$1" app="$2"
  local tmp="$OUT_DIR/sb-$app"
  mkdir -p "$tmp"
  adb exec-out "run-as $pkg tar -cf - /storage/emulated/0/Android/data/$pkg/files/storyboard 2>/dev/null" \
    | tar -xf - -C "$tmp" 2>/dev/null || { rm -rf "$tmp"; return 0; }
  cp -R "$tmp/storage/emulated/0/Android/data/$pkg/files/storyboard/." "$OUT_DIR/storyboard/$app/" 2>/dev/null || true
  rm -rf "$tmp"
}

count_storyboards() {
  find "$OUT_DIR/storyboard" -name '*.png' 2>/dev/null | wc -l
}

# E4 host-side storyboard transport: StepStoryboard POSTs every step screenshot (base64)
# to 10.0.2.2:7070 - the emulator's host-loopback alias. The collector below receives them
# and writes the gallery filmstrip directly on the runner (immune to device filesystem
# restrictions; the run-as device drain stays as belt-and-braces). One collector per shard
# run; node is preinstalled on GitHub runners.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STEP_COLLECTOR_PID=""
start_collector() {
  node "$REPO_ROOT/tools/report/step-collector.js" --port 7070 --out "$OUT_DIR" >/dev/null 2>&1 &
  STEP_COLLECTOR_PID=$!
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    curl -s -m 1 "http://127.0.0.1:7070/health" >/dev/null 2>&1 && break
    sleep 0.5
  done
  echo "[ok] step collector up on 10.0.2.2:7070 -> $OUT_DIR/storyboard"
}
stop_collector() {
  { [ -n "$STEP_COLLECTOR_PID" ] && kill "$STEP_COLLECTOR_PID"; } >/dev/null 2>&1 || true
  wait "${STEP_COLLECTOR_PID:-}" 2>/dev/null || true
  echo "[ok] step collector captured: $(count_storyboards) pngs"
  STEP_COLLECTOR_PID=""
}

run_shard() {
  local module="$1" s="$2" n="$3"
  echo "== UI shard app=$module shard=$s/$n RECORD_ALL=$RECORD_ALL =="
  # Install first, unrecorded: builds + installs both APKs so the recorded connected run
  # covers only the test execution.
  ( cd android && ./gradlew ":${module}:installDebug" ":${module}:installDebugAndroidTest" ) || return 1
  start_collector
  start_recorder "$module"
  local gradle_rc=0
  ( cd android && ./gradlew ":${module}:connectedDebugAndroidTest" \
    -Pandroid.testInstrumentationRunnerArguments.numShards="$n" \
    -Pandroid.testInstrumentationRunnerArguments.shardIndex="$s" \
    -Pandroid.testInstrumentationRunnerArguments.runId="$RUN_ID" ) &
  local gradle_pid=$!
  while kill -0 "$gradle_pid" 2>/dev/null; do
    pull_storyboards com.ridehailing.rider rider
    pull_storyboards com.ridehailing.driver driver
    sleep 1
  done
  wait "$gradle_pid" || gradle_rc=$?
  pull_storyboards com.ridehailing.rider rider
  pull_storyboards com.ridehailing.driver driver
  echo "[ok] storyboards drained: $(count_storyboards) pngs so far"
  stop_recorder "$module"
  stop_collector
  return "$gradle_rc"
}

overall_rc=0
case "$APP" in
  rider)  run_shard "app-rider" "$SHARD" "$NUM" || overall_rc=$? ;;
  driver) run_shard "app-driver" "$SHARD" "$NUM" || overall_rc=$? ;;
  all)    run_shard "app-rider" "$SHARD" "$NUM" || overall_rc=$?
          run_shard "app-driver" "$SHARD" "$NUM" || overall_rc=$? ;;
  *) echo "unknown app $APP"; exit 1 ;;
esac

# Always collect evidence (D4/D8: storyboards, logcat, dumpsys even on failure), then
# propagate the test result.
./scripts/collect-artifacts.sh "$APP" "$SHARD" || true
exit "$overall_rc"

#!/usr/bin/env bash
# Run sharded instrumented UI tests for an app (D4). Sharding via AndroidJUnitRunner args
# (-e numShards/-e shardIndex are wired into the app defaultConfigs since P2-3 - previously
# the -P properties had no gradle consumer and sharding was a silent no-op). Suites smaller
# than the shard count would leave empty shards (connectedAndroidTest fails on zero tests),
# so ci.yml collapses to 1 shard until suites grow.
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

# Emulator images are userdebug: root adbd so the shell can reach /sdcard/Android/data
# (storyboard pulls) - best effort; non-rooted devices fall back to run-as in collect.
adb root >/dev/null 2>&1 || true
sleep 3
# Clean slate so stale storyboards from a previous run cannot leak into the gallery.
adb shell rm -rf /sdcard/Android/data/com.ridehailing.rider/files/storyboard \
                 /sdcard/Android/data/com.ridehailing.driver/files/storyboard >/dev/null 2>&1 || true

# Screen recording for the report gallery video (E3/P0-C): best-effort, never fails tests.
# screenrecord caps at 180 s per segment; a background loop rotates segments and keeps only
# the last two (the actual test execution is the tail of the gradle run).
VIDEO_DIR="artifacts/ui-${APP}-${SHARD}/video"
mkdir -p "$VIDEO_DIR"
REC_PID=""
start_recorder() {
  ( i=0
    while :; do
      adb shell screenrecord --bit-rate 1000000 --time-limit 180 "/data/local/tmp/ui-seg-${i}.mp4" >/dev/null 2>&1 || true
      if [ "$i" -ge 2 ]; then
        adb shell rm -f "/data/local/tmp/ui-seg-$((i-2)).mp4" >/dev/null 2>&1 || true
      fi
      i=$((i+1))
    done ) &
  REC_PID=$!
}
stop_recorder() {
  # SIGINT makes screenrecord finalize the current segment; SIGKILL would truncate it.
  adb shell pkill -INT screenrecord >/dev/null 2>&1 \
    || adb shell kill -s INT "$(adb shell pidof screenrecord | tr -d '\r')" >/dev/null 2>&1 || true
  sleep 2
  { [ -n "$REC_PID" ] && kill "$REC_PID"; } >/dev/null 2>&1 || true
  wait "${REC_PID:-}" 2>/dev/null || true
  # Pull whatever segments survived (the loop may have started one more after the SIGINT).
  for f in $(adb shell ls /data/local/tmp/ 2>/dev/null | tr -d '\r' | awk '/^ui-seg-.*\.mp4$/' || true); do
    adb pull "/data/local/tmp/$f" "$VIDEO_DIR/" >/dev/null 2>&1 || true
    adb shell rm -f "/data/local/tmp/$f" >/dev/null 2>&1 || true
  done
}

run_shard() {
  local module="$1" s="$2" n="$3"
  echo "== UI shard app=$module shard=$s/$n RECORD_ALL=$RECORD_ALL =="
  start_recorder
  local gradle_rc=0
  ( cd android && ./gradlew ":${module}:connectedDebugAndroidTest" \
    -Pandroid.testInstrumentationRunnerArguments.numShards="$n" \
    -Pandroid.testInstrumentationRunnerArguments.shardIndex="$s" \
    -Pandroid.testInstrumentationRunnerArguments.runId="$RUN_ID" ) || gradle_rc=$?
  stop_recorder
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

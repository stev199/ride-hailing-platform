#!/usr/bin/env bash
# Run sharded instrumented UI tests for an app (D4). Sharding via AndroidX Orchestrator args.
# Usage: run-ui-tests.sh <rider|driver|all> <shardIndex> <numShards>
set -euo pipefail
APP="${1:-all}"
SHARD="${2:-0}"
NUM="${3:-1}"
RECORD_ALL="${RECORD_ALL:-0}"

run_shard() {
  local module="$1" s="$2" n="$3"
  echo "== UI shard app=$module shard=$s/$n RECORD_ALL=$RECORD_ALL =="
  # android/ is a standalone gradle root (same pattern as backend/, mesh-core/):
  # run from android/ with plain module ids, matching the android-build CI job.
  ( cd android && ./gradlew ":${module}:connectedDebugAndroidTest" \
    -Pandroid.testInstrumentationRunnerArguments.numShards="$n" \
    -Pandroid.testInstrumentationRunnerArguments.shardIndex="$s" )
}

case "$APP" in
  rider)  run_shard ":app-rider" "$SHARD" "$NUM" ;;
  driver) run_shard ":app-driver" "$SHARD" "$NUM" ;;
  all)    run_shard ":app-rider" "$SHARD" "$NUM"; run_shard ":app-driver" "$SHARD" "$NUM" ;;
  *) echo "unknown app $APP"; exit 1 ;;
esac

./scripts/collect-artifacts.sh "$APP" "$SHARD"

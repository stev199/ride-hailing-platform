#!/usr/bin/env bash
# Wait for all booted emulators (sys.boot_completed=1) with timeout.
set -euo pipefail
ADB="${ADB:-adb}"
TIMEOUT="${1:-300}"
ELAPSED=0
while true; do
  SERIALS=$("$ADB" devices | awk '/emulator-/{print $1}')
  MISSING=0
  for s in $SERIALS; do
    BOOT=$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || echo 0)
    [ "$BOOT" = "1" ] || MISSING=$(( MISSING + 1 ))
  done
  if [ "$MISSING" = "0" ] && [ -n "$SERIALS" ]; then
    echo "[ok] all emulators booted: $SERIALS"
    exit 0
  fi
  [ "$ELAPSED" -ge "$TIMEOUT" ] && { echo "[fail] boot timeout after ${TIMEOUT}s"; exit 1; }
  sleep 5; ELAPSED=$(( ELAPSED + 5 ))
done

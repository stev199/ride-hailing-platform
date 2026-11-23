#!/usr/bin/env bash
# Start N headless emulators (D4/D5). Requires ANDROID_HOME + system images + KVM.
# Local sandbox: no KVM/SDK (degraded, see STATE.md). CI: ubuntu runners have KVM.
set -euo pipefail
N="${1:-2}"
BASE_AVD="${AVD_NAME:-atd_api34}"

if [ -z "${ANDROID_HOME:-}" ] || [ ! -x "${ANDROID_HOME}/emulator/emulator" ]; then
  echo "ANDROID_HOME/emulator missing - cannot start emulators here (degraded mode)"
  exit 0
fi
test -e /dev/kvm || { echo "/dev/kvm missing - emulator startup will be extremely slow or fail"; exit 1; }

for i in $(seq 0 $(( N - 1 ))); do
  PORT=$(( 5554 + i * 2 ))
  "${ANDROID_HOME}/emulator/emulator" -avd "$BASE_AVD" \
    -port "$PORT" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot-save \
    -camera-back none -camera-front none \
    > "/tmp/emulator-$PORT.log" 2>&1 &
  echo "emulator serial emulator-$PORT starting (pid $!)"
done
./scripts/wait-for-boot.sh

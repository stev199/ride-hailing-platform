#!/usr/bin/env bash
# Start N headless emulators (D4/D5). Requires ANDROID_HOME + system images + KVM.
# CI: ubuntu runners have KVM; the e2e job installs the SDK emulator + google_apis image
# and creates the AVD itself (AVD_NAME=e2e_api34 default). ALLOW_MISSING_EMULATORS=1 is the
# sandbox escape hatch ONLY - in CI a missing emulator must FAIL, not degrade (the
# exit-0 degraded mode made e2e-cross-actor green without running any emulator for its
# whole history until 2026-11-24, run 45496557524 log).
set -euo pipefail
N="${1:-2}"
BASE_AVD="${AVD_NAME:-e2e_api34}"

if [ -z "${ANDROID_HOME:-}" ] || [ ! -x "${ANDROID_HOME}/emulator/emulator" ]; then
  if [ "${ALLOW_MISSING_EMULATORS:-0}" = "1" ]; then
    echo "ANDROID_HOME/emulator missing - degraded mode (ALLOW_MISSING_EMULATORS=1)"
    exit 0
  fi
  echo "ANDROID_HOME/emulator missing - cannot start emulators (set ALLOW_MISSING_EMULATORS=1 to opt out)"
  exit 1
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
./scripts/wait-for-boot.sh "${BOOT_TIMEOUT:-600}" || {
  echo "---- emulator logs (last 15 lines each) ----"
  for f in /tmp/emulator-*.log; do echo "== $f =="; tail -15 "$f" 2>/dev/null || true; done
  exit 1
}

#!/usr/bin/env bash
# Local pipeline subset (Part D: same commands locally and in CI).
# Runs what is runnable without Docker/emulators; reports what is not runnable and why.
set -uo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-all}"

run() { # run <name> <cmd...>
  local name="$1"; shift
  echo "== $name =="
  "$@" || { echo "[FAIL] $name"; FAIL=1; return 1; }
  echo "[ok] $name"
}

FAIL=0

if [ "$MODE" = "all" ] || [ "$MODE" = "lint" ]; then
  # actionlint (pinned)
  if [ ! -x /tmp/actionlint ]; then
    curl -fsSL -o /tmp/actionlint.tgz "https://github.com/rhysd/actionlint/releases/download/v1.7.7/actionlint_1.7.7_linux_amd64.tar.gz" \
      && tar -xzf /tmp/actionlint.tgz -C /tmp actionlint && chmod +x /tmp/actionlint
  fi
  run actionlint /tmp/actionlint -color || true
  # gitleaks (pinned)
  if [ ! -x /tmp/gitleaks ]; then
    curl -fsSL -o /tmp/gitleaks.tgz "https://github.com/gitleaks/gitleaks/releases/download/v8.24.3/gitleaks_8.24.3_linux_x64.tar.gz" \
      && tar -xzf /tmp/gitleaks.tgz -C /tmp gitleaks && chmod +x /tmp/gitleaks
  fi
  run gitleaks /tmp/gitleaks detect --no-banner --redact -v || true
  # OpenAPI lint via spectral if available
  if [ -f docs/api/openapi.yaml ]; then
    if command -v npx >/dev/null 2>&1; then
      run spectral npx --yes @stoplight/spectral-cli@6 lint docs/api/openapi.yaml || true
    else
      echo "[skip] spectral (no npx)"
    fi
  fi
fi

if [ "$MODE" = "all" ] || [ "$MODE" = "live" ]; then
  if [ -f tools/test-viewer/server.js ]; then
    echo "[live] viewer available: make viewer  (http://localhost:7070)"
  fi
  echo "[live] emulator flows require Android SDK + KVM - see STATE.md environment status"
fi

if [ "$FAIL" = "0" ]; then echo "== ci-local: PASS =="; else echo "== ci-local: FAIL =="; exit 1; fi

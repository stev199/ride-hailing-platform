#!/usr/bin/env bash
# Wait for compose stack health (D4 per-shard isolation).
set -euo pipefail
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
TIMEOUT="${1:-120}"
ELAPSED=0
while true; do
  if curl -fsS --max-time 3 "$BACKEND_URL/health" >/dev/null 2>&1; then
    echo "[ok] backend healthy at $BACKEND_URL"
    exit 0
  fi
  [ "$ELAPSED" -ge "$TIMEOUT" ] && { echo "[fail] health timeout"; exit 1; }
  sleep 5; ELAPSED=$(( ELAPSED + 5 ))
done

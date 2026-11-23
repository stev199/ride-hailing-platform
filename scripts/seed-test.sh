#!/usr/bin/env bash
# Seed deterministic demo/test data (A8, D4). v0.1: exercises the backend seed API when available.
# Full seeding (admin, riders, drivers, trips, fare configs, small city extract) lands with Phase 1.
set -euo pipefail

NAMESPACE="${1:-default}"
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"

echo "seed-test namespace=$NAMESPACE backend=$BACKEND_URL"

if ! command -v curl >/dev/null 2>&1; then echo "curl missing"; exit 1; fi

if curl -fsS --max-time 3 "$BACKEND_URL/health" >/dev/null 2>&1; then
  echo "backend healthy - calling /test/seed (test profile only)"
  curl -fsS -X POST "$BACKEND_URL/test/seed" -H 'Content-Type: application/json' \
    -d "{\"namespace\":\"$NAMESPACE\"}" || { echo "seed call failed"; exit 1; }
  echo "[ok] seeded namespace=$NAMESPACE"
else
  echo "[degraded] backend not reachable - seeding deferred until Phase 1 (recorded in PROGRESS_LOG)"
fi

#!/usr/bin/env bash
# Fetch/build small self-hosted geo datasets for local/dev (Part B: no tile.openstreetmap.org).
# Uses a small city extract (default: Monaco ~ small enough for CI/dev demos).
# Requires: docker (for planetiler + osrm images). This sandbox has no Docker -> run on dev machine or CI.
set -euo pipefail

DATA_DIR="${1:-/home/z/my-project/ride-hailing-platform/infra/data}"
CITY_PBF_URL="${CITY_PBF_URL:-https://download.geofabrik.de/europe/monaco-latest.osm.pbf}"
mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

echo "[1/4] city extract (PBF)"
if [ ! -f city.osm.pbf ]; then
  curl -fsSL -o city.osm.pbf "$CITY_PBF_URL"
fi

echo "[2/4] vector tiles (Planetiler -> MBTiles for tileserver-gl)"
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/data" ghcr.io/onthegomap/planetiler:latest \
  --area=monaco --download=false --mbtiles=/data/city.mbtiles /data/city.osm.pbf

echo "[3/4] OSRM graph (MLD)"
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-extract -p /opt/car.lua /data/city.osm.pbf
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-partition /data/city.osrm
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-customize /data/city.osrm

echo "[4/4] done. Files: city.osm.pbf city.mbtiles city.osrm*"
echo "Start the geo stack: docker compose --profile geo up"
echo "ODbL attribution required wherever tiles/routing are displayed."

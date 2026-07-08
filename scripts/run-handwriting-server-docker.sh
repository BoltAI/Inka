#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_PORT="${INKA_HANDWRITING_HOST_PORT:-8878}"
IMAGE="${INKA_HANDWRITING_IMAGE:-inka-handwriting-server:local}"

if ! command -v docker >/dev/null 2>&1; then
  cat >&2 <<'EOF'
Docker is required for the containerized handwriting server.
Install Docker Desktop or Colima, start Docker, then run this script again.
EOF
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  cat >&2 <<'EOF'
Docker is installed but the daemon is not reachable.
Start Docker Desktop or Colima, then run this script again.
EOF
  exit 1
fi

cd "$ROOT_DIR"

if docker compose version >/dev/null 2>&1; then
  echo "Starting Inka handwriting server on http://127.0.0.1:$HOST_PORT"
  echo "Set Inka Developer Settings -> Server Endpoint to http://<server-ip>:$HOST_PORT"
  exec docker compose up --build handwriting-server
fi

echo "Docker Compose is unavailable; falling back to docker build/run."
docker build -t "$IMAGE" .

echo "Starting Inka handwriting server on http://127.0.0.1:$HOST_PORT"
echo "Set Inka Developer Settings -> Server Endpoint to http://<server-ip>:$HOST_PORT"
exec docker run --rm \
  -p "$HOST_PORT:8787" \
  -e HOST=0.0.0.0 \
  -e PORT=8787 \
  -e ENGINE="${ENGINE:-pytorch}" \
  -e BIAS="${BIAS:-0.8}" \
  -e STEPS="${STEPS:-1500}" \
  -e DEVICE="${DEVICE:-cpu}" \
  -e SAMPLE_ATTEMPTS="${SAMPLE_ATTEMPTS:-8}" \
  "$IMAGE"

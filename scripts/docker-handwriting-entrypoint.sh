#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8787}"
ENGINE="${ENGINE:-pytorch}"
TOOLKIT_DIR="${TOOLKIT_DIR:-/opt/inka-handwriting/toolkit}"
MODEL_DIR="${MODEL_DIR:-$TOOLKIT_DIR/checkpoints/Epoch_52}"
BIAS="${BIAS:-0.8}"
STEPS="${STEPS:-1500}"
DEVICE="${DEVICE:-cpu}"
SAMPLE_ATTEMPTS="${SAMPLE_ATTEMPTS:-8}"

args=(
  --host "$HOST"
  --port "$PORT"
  --engine "$ENGINE"
)

if [ "$ENGINE" = "pytorch" ]; then
  args+=(
    --toolkit-dir "$TOOLKIT_DIR"
    --model-dir "$MODEL_DIR"
    --bias "$BIAS"
    --steps "$STEPS"
    --device "$DEVICE"
    --sample-attempts "$SAMPLE_ATTEMPTS"
  )
fi

exec python /opt/inka/scripts/handwriting-server.py "${args[@]}"

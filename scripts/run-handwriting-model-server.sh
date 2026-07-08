#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${INKA_HANDWRITING_CACHE_DIR:-$HOME/.cache/inka-handwriting}"
TOOLKIT_DIR="${TOOLKIT_DIR:-$CACHE_DIR/pytorch-handwriting-synthesis-toolkit}"
VENV_DIR="${VENV_DIR:-$CACHE_DIR/venv}"
MODEL_DIR="${MODEL_DIR:-$TOOLKIT_DIR/checkpoints/Epoch_52}"
HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8787}"
BIAS="${BIAS:-0.8}"
STEPS="${STEPS:-1500}"
DEVICE="${DEVICE:-cpu}"
SAMPLE_ATTEMPTS="${SAMPLE_ATTEMPTS:-8}"
ENGINE="${ENGINE:-pytorch}"

if [ "$ENGINE" = "pytorch" ] && [ ! -d "$TOOLKIT_DIR/.git" ]; then
  mkdir -p "$(dirname "$TOOLKIT_DIR")"
  git clone --depth 1 https://github.com/X-rayLaser/pytorch-handwriting-synthesis-toolkit.git "$TOOLKIT_DIR"
fi

if [ "$ENGINE" = "pytorch" ] && [ ! -x "$VENV_DIR/bin/python" ]; then
  python3 -m venv "$VENV_DIR"
fi

if [ "$ENGINE" = "pytorch" ] && { [ "${INKA_HANDWRITING_REFRESH_DEPS:-0}" = "1" ] || [ ! -f "$VENV_DIR/.inka-handwriting-deps-installed" ]; }; then
  "$VENV_DIR/bin/python" -m pip install --upgrade pip
  "$VENV_DIR/bin/python" -m pip install -r "$TOOLKIT_DIR/requirements.txt"
  touch "$VENV_DIR/.inka-handwriting-deps-installed"
fi

PYTHON_BIN="python3"
EXTRA_ARGS=()

if [ "$ENGINE" = "pytorch" ]; then
  PYTHON_BIN="$VENV_DIR/bin/python"
  EXTRA_ARGS=(
    --toolkit-dir "$TOOLKIT_DIR"
    --model-dir "$MODEL_DIR"
    --bias "$BIAS"
    --steps "$STEPS"
    --device "$DEVICE"
    --sample-attempts "$SAMPLE_ATTEMPTS"
  )
fi

exec "$PYTHON_BIN" "$ROOT_DIR/scripts/handwriting-server.py" \
  --host "$HOST" \
  --port "$PORT" \
  --engine "$ENGINE" \
  "${EXTRA_ARGS[@]}"

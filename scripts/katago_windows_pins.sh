#!/usr/bin/env bash

# Compatibility entry point for packaging scripts. All values come from the shared JSON catalog.
_KATAGO_PINS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${PYTHON_BIN:-}" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
  elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
  else
    echo "Python 3 is required to read katago-assets.json." >&2
    return 1 2>/dev/null || exit 1
  fi
fi

_katago_pin_get() {
  "$PYTHON_BIN" "$_KATAGO_PINS_ROOT/scripts/katago_asset_catalog.py" get "$1"
}

KATAGO_RELEASE_TAG="${KATAGO_RELEASE_TAG:-$(_katago_pin_get katagoReleaseTag)}"
HUMAN_SL_CUDA_COMPANION_SHA256="${HUMAN_SL_CUDA_COMPANION_SHA256:-$(_katago_pin_get assets.windows-nvidia.executableSha256)}"
TENSORRT_KATAGO_TAG="${TENSORRT_KATAGO_TAG:-$KATAGO_RELEASE_TAG}"
TENSORRT_KATAGO_ASSET="${TENSORRT_KATAGO_ASSET:-$(_katago_pin_get assets.windows-tensorrt.assetName)}"
TENSORRT_KATAGO_SHA256="${TENSORRT_KATAGO_SHA256:-$(_katago_pin_get assets.windows-tensorrt.sha256)}"
TENSORRT_KATAGO_URL="${TENSORRT_KATAGO_URL:-$("$PYTHON_BIN" "$_KATAGO_PINS_ROOT/scripts/katago_asset_catalog.py" asset-url windows-tensorrt)}"

unset -f _katago_pin_get
unset _KATAGO_PINS_ROOT

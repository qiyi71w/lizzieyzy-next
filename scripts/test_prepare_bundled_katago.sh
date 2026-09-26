#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=prepare_bundled_katago.sh
source "$ROOT_DIR/scripts/prepare_bundled_katago.sh"

[[ "$KATAGO_TAG" == "v1.18.2" ]]
[[ "$(catalog_get katagoSourceCommit)" == "47aadc08518b3e121f22539796c911002f699584" ]]
[[ "$KATAGO_RELEASE_BASE" == "https://github.com/wimi321/lizzieyzy-next/releases/download/"* ]]
[[ "$PREFERRED_MODEL_NAME" == "kata1-tf3-b11c768-s12002M-d6304M.bin.gz" ]]
[[ "$PREFERRED_MODEL_SIZE_BYTES" == "262017809" ]]
[[ "$MODEL_URL" == "https://media.katagotraining.org/uploaded/networks/models/kata1/$PREFERRED_MODEL_NAME" ]]
[[ "$PREFERRED_MODEL_SHA256" == \
  "4a6312e80faadee7b7dd28689a2e87a1efb4640c10132f16290da7a17b4c6d9e" ]]
[[ "$HUMAN_SL_CUDA_COMPANION_SHA256" == \
  "9a87f2e40233bb5694332546f9cad0a6248f4593341ccafb29225fbf025a6ef6" ]]
for pair in "windows-cpu|$WINDOWS_ASSET" "windows-opencl|$WINDOWS_OPENCL_ASSET" \
  "windows-nvidia|$WINDOWS_NVIDIA_ASSET" "linux-cpu|$LINUX_ASSET" \
  "linux-opencl|$LINUX_OPENCL_ASSET" "linux-nvidia|$LINUX_NVIDIA_ASSET"; do
  id="${pair%%|*}"
  asset="${pair#*|}"
  [[ "$asset" == "katago-source-47aadc08518b-$id.zip" ]]
  sha="$(expected_asset_sha256 "$asset")"
  [[ "$sha" =~ ^[0-9a-f]{64}$ ]]
  [[ "$sha" == "$(catalog_get "assets.$id.sha256")" ]]
done

uname() {
  printf '%s\n' "MINGW64_NT-10.0"
}

if is_macos_host; then
  echo "Windows Git Bash must not be detected as macOS" >&2
  exit 1
fi

skip_output="$(prepare_macos_bundle)"
grep -Fq "Skipping macOS KataGo bundle on non-macOS host" <<<"$skip_output"

uname() {
  printf '%s\n' "Linux"
}

if is_macos_host; then
  echo "Linux must not be detected as macOS" >&2
  exit 1
fi

uname() {
  printf '%s\n' "Darwin"
}

if ! is_macos_host; then
  echo "Darwin must be detected as macOS" >&2
  exit 1
fi

echo "prepare_bundled_katago host gating tests passed"

#!/usr/bin/env bash

release_sha256() {
  local file="$1"

  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return 0
  fi

  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return 0
  fi

  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$file" | awk '{print $NF}'
    return 0
  fi

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$file" <<'PY'
import hashlib
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
with path.open("rb") as fh:
    for chunk in iter(lambda: fh.read(1024 * 1024), b""):
        digest.update(chunk)
print(digest.hexdigest())
PY
    return 0
  fi

  echo "No SHA256 tool available" >&2
  return 1
}

write_sha256_file() {
  local output_file="$1"
  shift

  : >"$output_file"

  local file
  local checksum
  for file in "$@"; do
    if [[ ! -f "$file" ]]; then
      continue
    fi
    checksum="$(release_sha256 "$file")"
    printf '%s  %s\n' "$checksum" "$(basename "$file")" >>"$output_file"
  done
}

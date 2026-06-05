#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"

expected_abis=("arm64-v8a" "x86_64")
missing=0

check_tool() {
  local tool="$1"
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required tool not found: $tool" >&2
    return 1
  fi
}

check_tool readelf

for abi in "${expected_abis[@]}"; do
  binary="$JNI_DIR/$abi/libyt-dlp.so"
  echo "==> Checking $binary"

  if [[ ! -f "$binary" ]]; then
    echo "ERROR: missing bundled yt-dlp executable for ABI: $abi" >&2
    missing=1
    continue
  fi

  if [[ ! -s "$binary" ]]; then
    echo "ERROR: $binary is empty" >&2
    missing=1
    continue
  fi

  if [[ ! -x "$binary" ]]; then
    echo "ERROR: $binary is not executable; run: chmod 755 '$binary'" >&2
    missing=1
  fi

  if command -v file >/dev/null 2>&1; then
    file_output="$(file -b "$binary")"
    echo "file: $file_output"
  fi

  if ! readelf -h "$binary" >/dev/null 2>&1; then
    echo "ERROR: $binary is not a readable ELF executable/shared object" >&2
    missing=1
    continue
  fi

  machine="$(readelf -h "$binary" | awk -F: '/Machine:/ { gsub(/^[ \t]+/, "", $2); print $2; exit }')"
  echo "machine: $machine"

  case "$abi" in
    arm64-v8a)
      if [[ "$machine" != *"AArch64"* ]]; then
        echo "ERROR: expected AArch64 machine type for $abi, got: $machine" >&2
        missing=1
      fi
      ;;
    x86_64)
      if [[ "$machine" != *"Advanced Micro Devices X86-64"* && "$machine" != *"X86-64"* ]]; then
        echo "ERROR: expected x86-64 machine type for $abi, got: $machine" >&2
        missing=1
      fi
      ;;
  esac

  if readelf -l "$binary" | grep -q 'Requesting program interpreter'; then
    interpreter="$(readelf -l "$binary" | sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p' | head -n 1)"
    echo "interpreter: $interpreter"

    if [[ "$interpreter" != /system/bin/linker* ]]; then
      echo "WARNING: interpreter does not look like Android linker: $interpreter" >&2
    fi
  fi

  if command -v strings >/dev/null 2>&1 && strings "$binary" | grep -Eq '^/usr/bin/env python|^/usr/bin/python|^/bin/python'; then
    echo "ERROR: $binary appears to reference a host Python interpreter; bundle an Android runtime instead" >&2
    missing=1
  fi

  echo
done

if [[ "$missing" -ne 0 ]]; then
  echo "yt-dlp bundle validation failed." >&2
  exit 1
fi

echo "yt-dlp bundle validation passed. Still test on an Android device with: libyt-dlp.so --version"

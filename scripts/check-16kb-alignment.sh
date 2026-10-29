#!/usr/bin/env bash
set -euo pipefail

ARCHIVE="${1:?usage: check-16kb-alignment.sh <app.aab|app.apk>}"
[ -f "$ARCHIVE" ] || { echo "✋ file not found: $ARCHIVE" >&2; exit 2; }
command -v readelf >/dev/null 2>&1 || { echo "✋ readelf not found (install binutils)" >&2; exit 2; }
command -v unzip   >/dev/null 2>&1 || { echo "✋ unzip not found" >&2; exit 2; }

ALIGN=16384
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

unzip -o -q "$ARCHIVE" 'base/lib/*/*.so' 'lib/*/*.so' -d "$workdir" 2>/dev/null || true

mapfile -t libs < <(find "$workdir" -name '*.so' | sort)
if [ "${#libs[@]}" -eq 0 ]; then
  echo "✅ No native libraries in $(basename "$ARCHIVE") — 16 KB requirement trivially met."
  exit 0
fi

fail=0
for so in "${libs[@]}"; do
  name="${so#"$workdir"/}"
  bad=""
  while read -r a; do
    [ -z "$a" ] && continue
    dec=$(( a ))
    if [ "$dec" -lt "$ALIGN" ] || [ $(( dec % ALIGN )) -ne 0 ]; then
      bad="$bad $a"
    fi
  done < <(readelf -lW "$so" | awk '/LOAD/ {print $NF}')
  if [ -n "$bad" ]; then
    echo "❌ $name: LOAD alignment(s)$bad — not a multiple of 16 KB"
    fail=1
  else
    echo "✅ $name"
  fi
done

echo
if [ "$fail" -ne 0 ]; then
  echo "Some native libraries are NOT 16 KB-aligned. Rebuild with AGP 8.5.1+ and"
  echo "NDK r27+, or update the offending dependency to a 16 KB-ready version."
  echo "Docs: https://developer.android.com/guide/practices/page-sizes"
  exit 1
fi
echo "All native libraries are 16 KB-aligned. ✅"

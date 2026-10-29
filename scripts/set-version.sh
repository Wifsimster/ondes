#!/usr/bin/env bash
set -euo pipefail

ver="${1:?usage: set-version.sh <version>}"

if grep -qE '^VERSION_NAME=' gradle.properties; then
  sed -i -E "s/^VERSION_NAME=.*/VERSION_NAME=${ver}/" gradle.properties
else
  printf '\nVERSION_NAME=%s\n' "$ver" >> gradle.properties
fi

echo "Set VERSION_NAME=${ver} in gradle.properties"

#!/usr/bin/env bash
set -euo pipefail

KEYSTORE="${1:-upload-keystore.jks}"
ALIAS="${KEY_ALIAS:-upload}"

command -v keytool >/dev/null 2>&1 || {
  echo "✋ keytool not found. Install a JDK 17 (e.g. Temurin) and retry." >&2
  exit 1
}

if [ -e "$KEYSTORE" ]; then
  echo "✋ '$KEYSTORE' already exists — refusing to overwrite an existing key." >&2
  echo "   Delete it yourself first if you really intend to regenerate." >&2
  exit 1
fi

echo "Creating upload keystore '$KEYSTORE' (alias: '$ALIAS')."
echo "You'll be prompted for a keystore password and a key password — use a"
echo "strong password and store it in a password manager."
echo

keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000

abs_path="$(cd "$(dirname "$KEYSTORE")" && pwd)/$(basename "$KEYSTORE")"

b64() { base64 -w0 "$1" 2>/dev/null || base64 "$1"; }

cat <<EOF

============================================================================
 1) Local builds — create a file named 'keystore.properties' at the repo root
    (git-ignored) with:
----------------------------------------------------------------------------
storeFile=$abs_path
storePassword=<the keystore password you just entered>
keyAlias=$ALIAS
keyPassword=<the key password you just entered>
============================================================================
 2) CI — set these four GitHub Actions secrets (needs the gh CLI, run from the
    repo, replace the password placeholders):
----------------------------------------------------------------------------
  gh secret set KEYSTORE_BASE64   -b"\$(base64 -w0 '$abs_path' 2>/dev/null || base64 '$abs_path')"
  gh secret set KEYSTORE_PASSWORD -b'<keystore password>'
  gh secret set KEY_ALIAS         -b'$ALIAS'
  gh secret set KEY_PASSWORD      -b'<key password>'
============================================================================

KEYSTORE_BASE64 value (copy the single line below if you prefer pasting it in
the GitHub UI under Settings → Secrets and variables → Actions):

$(b64 "$abs_path")

Done. The next CI 'Build release AAB' step will sign with this upload key.
EOF

#!/usr/bin/env bash
#
# generate-keystore.sh — Create a release signing keystore for local builds.
#
# The APK must be signed to install on a device. This script creates a
# keystore and prints the four values you paste into CI secrets (or into
# android/keystore.properties for local builds).
#
# DO NOT commit the .jks file or the passwords. Both are gitignored.
#
# Usage:
#   ./scripts/generate-keystore.sh [output-path]
#
# Requirements: keytool (ships with any JDK).

set -euo pipefail

OUT="${1:-release.jks}"
ALIAS="${KEY_ALIAS:-pifilling}"

if [[ -e "$OUT" ]]; then
    echo "ERROR: $OUT already exists. Refusing to overwrite a signing key." >&2
    echo "Losing this key means you can never update an installed APK." >&2
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "ERROR: keytool not found. Install a JDK (17 recommended)." >&2
    exit 1
fi

# 32 bytes of urandom, hex-encoded. Meant to be pasted into a secret store,
# not remembered.
gen_password() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 24
    else
        head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
    fi
}

STOREPASS="$(gen_password)"
KEYPASS="$STOREPASS"

echo "Generating keystore: $OUT (alias: $ALIAS)"
keytool -genkeypair \
    -v \
    -keystore "$OUT" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$STOREPASS" \
    -keypass "$KEYPASS" \
    -dname "CN=Pi-Filling, OU=Release, O=Pi-Filling, L=Unknown, ST=Unknown, C=US" \
    >/dev/null 2>&1

echo
echo "Keystore created. Base64 form (what CI needs):"
echo
base64 -w0 <"$OUT" 2>/dev/null || base64 <"$OUT"
echo
echo
cat <<EOF
────────────────────────────────────────────────────────────────────────
Add these four repository secrets (Settings → Secrets and variables →
Actions → New repository secret):

  ANDROID_KEYSTORE_BASE64   the base64 blob printed above
  ANDROID_KEYSTORE_PASSWORD $STOREPASS
  ANDROID_KEY_ALIAS         $ALIAS
  ANDROID_KEY_PASSWORD      $KEYPASS

For a LOCAL release build instead, write android/keystore.properties:

  storeFile=$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")
  storePassword=$STOREPASS
  keyAlias=$ALIAS
  keyPassword=$KEYPASS

────────────────────────────────────────────────────────────────────────
KEEP THIS OUTPUT. Store the passwords in a password manager.

If you lose $OUT you cannot ship an update to an already-installed app —
Android will reject it as a different signer. Back the file up somewhere
you control; it is deliberately not in git.
────────────────────────────────────────────────────────────────────────
EOF

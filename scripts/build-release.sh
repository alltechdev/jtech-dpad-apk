#!/usr/bin/env bash
# Builds a signed release AAB (Android App Bundle) for Play Store upload.
# Signing secrets are sourced from ~/.config/jtech-dpad/signing.env — never committed.
# Usage: ./scripts/build-release.sh [assembleRelease|bundleRelease]

set -euo pipefail

SIGNING_ENV="${HOME}/.config/jtech-dpad/signing.env"
TASK="${1:-bundleRelease}"

if [[ ! -f "$SIGNING_ENV" ]]; then
    echo "ERROR: signing env not found at $SIGNING_ENV" >&2
    exit 1
fi

# Source signing vars: KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
set -a
source "$SIGNING_ENV"
set +a

cd "$(dirname "$0")/.."

echo "==> Building: $TASK"
./gradlew "$TASK" --no-daemon

echo ""
if [[ "$TASK" == "bundleRelease" ]]; then
    AAB="app/build/outputs/bundle/release/app-release.aab"
    echo "==> AAB: $AAB"
    echo "==> Upload this file to the Play Console."
else
    APK="app/build/outputs/apk/release/app-release.apk"
    echo "==> APK: $APK"
fi

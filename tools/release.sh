#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub Release (the APK is NOT committed to git).
# Bump versionCode/versionName in app/build.gradle.kts and commit first.
#
# Usage: tools/release.sh [--notes "text"]
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env.sh
REPO="${FILEN_GH_REPO:-nchiker/Filen-Filemanager}"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
NOTES="Install: download the APK on an arm64 Android 8.0+ phone and open it (allow installs from your browser/file manager when asked). Then sign in to Filen, or skip for local-only, and grant All files access. Updates install over the previous release."
[ "${1:-}" = "--notes" ] && NOTES="$2"

KS="${FILEN_KEYSTORE_PROPS:-$HOME/.local/share/filen-release/keystore.properties}"
[ -f "$KS" ] || { echo "No release keystore at $KS - refusing to publish a debug-signed APK" >&2; exit 1; }

tools/publish.sh                                   # the tag must point at code that is on GitHub
./gradlew :app:assembleRelease -q
SRC=app/build/outputs/apk/release/app-release.apk
APK="build/Filen-Files-$VERSION.apk"; mkdir -p build; cp "$SRC" "$APK"
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs "$APK" | grep -q "CN=Filen Files" \
  || { echo "APK is not signed with the release key" >&2; exit 1; }

SHA="$(git rev-list -1 filen-only)"
if gh release view "v$VERSION" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release v$VERSION exists: replacing its APK"; gh release upload "v$VERSION" "$APK" --repo "$REPO" --clobber
else
  gh release create "v$VERSION" "$APK" --repo "$REPO" --target main --title "Filen Files $VERSION" --notes "$NOTES"
fi
echo "Released: https://github.com/$REPO/releases/tag/v$VERSION"

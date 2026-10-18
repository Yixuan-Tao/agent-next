#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APK=${1:-"$ROOT/dist/agent-next-beta-0.1.apk"}
SDK=${ANDROID_SDK_ROOT:-/Users/tommy/Documents/ChatGPT/agent-phone/work/android-sdk}
BUILD_TOOLS="$SDK/build-tools/36.0.0"
JAVA_HOME=${JAVA_HOME:-/Users/tommy/Documents/Codex/2026-10-15/xia/work/tools/temurin21/Contents/Home}
PATH="$JAVA_HOME/bin:$PATH"

[ -f "$APK" ] || { echo "missing APK: $APK" >&2; exit 1; }
[ -x "$BUILD_TOOLS/apksigner" ] || { echo "missing apksigner" >&2; exit 1; }
[ -x "$BUILD_TOOLS/aapt" ] || { echo "missing aapt" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" > "$TMP/certs"
grep -q 'CN=AgentNext Beta' "$TMP/certs"

"$BUILD_TOOLS/aapt" dump badging "$APK" > "$TMP/badging"
grep -q "package: name='agent.next.app'" "$TMP/badging"
grep -q "sdkVersion:'33'" "$TMP/badging"
grep -q "targetSdkVersion:'36'" "$TMP/badging"
grep -q "launchable-activity: name='agent.next.app.MainActivity'" "$TMP/badging"

"$BUILD_TOOLS/aapt" dump xmltree "$APK" AndroidManifest.xml > "$TMP/manifest"
grep -q 'agent.next.app.PreviewActivity' "$TMP/manifest"
grep -q 'agent.next.app.SettingsActivity' "$TMP/manifest"
# Release-only exported check: PreviewActivity must be exported=false.
awk '/E: activity/{activity=1} activity && /agent.next.app.PreviewActivity/{found=1}
     found && /android:exported/{print; exit}' "$TMP/manifest" > "$TMP/preview_export"
grep -q '0x0' "$TMP/preview_export"

SUM=$(shasum -a 256 "$APK" | awk '{print $1}')
EXPECTED=$(awk '{print $1}' "$ROOT/dist/agent-next-beta-0.1.apk.sha256")
[ "$SUM" = "$EXPECTED" ]

echo "RELEASE PASS"
echo "apk=$APK"
echo "sha256=$SUM"
grep 'Signer #1 certificate DN' "$TMP/certs"
grep -E "package:|sdkVersion|targetSdkVersion|launchable-activity" "$TMP/badging"

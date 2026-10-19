#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-${QUAL_JAVA_HOME:-}}
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "JAVA_HOME (or QUAL_JAVA_HOME) must point to a JDK 21 installation." >&2
  exit 1
fi
export JAVA_HOME
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$ROOT/work/gradle-home"}
export QUAL_JAVA_HOME=${QUAL_JAVA_HOME:-"$JAVA_HOME"}

echo "== host: service/policy =="
sh "$ROOT/tools/test-service.sh"
echo "== host: device-api =="
sh "$ROOT/tools/test-device-api.sh"
echo "== host: brain =="
sh "$ROOT/tools/test-brain.sh"
echo "== host: capability adapter =="
sh "$ROOT/tools/test-capability-adapter.sh"
echo "== host: Shizuku adapter =="
sh "$ROOT/tools/test-shizuku-adapter.sh"

echo "== known defects (must reproduce until M2/M3) =="
sh "$ROOT/tools/test-known-defects.sh"

echo "== build: debug + release manifest =="
( cd "$ROOT" && ./gradlew --offline :app:assembleDebug :app:assembleRelease :app:processReleaseManifest )

RELEASE_MANIFEST="$ROOT/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
DEBUG_MANIFEST="$ROOT/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
[ -f "$RELEASE_MANIFEST" ] || { echo "missing release manifest" >&2; exit 1; }
[ -f "$DEBUG_MANIFEST" ] || { echo "missing debug manifest" >&2; exit 1; }
if grep -q "agent.next.app.ShellActivity" "$RELEASE_MANIFEST"; then
  echo "release manifest must not contain ShellActivity" >&2
  exit 1
fi
if ! grep -q "agent.next.app.ShellActivity" "$DEBUG_MANIFEST"; then
  echo "debug manifest must contain ShellActivity for smoke tests" >&2
  exit 1
fi
# Release entry points stay unexported; debug may override this for local smokes.
if ! awk '/PreviewActivity/{found=1} found && /android:exported/{print; exit}' \
    "$RELEASE_MANIFEST" | grep -q 'android:exported="false"'; then
  echo "release PreviewActivity must be exported=false" >&2
  exit 1
fi

echo "HOST PASS"

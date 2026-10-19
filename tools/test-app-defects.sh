#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-${QUAL_JAVA_HOME:-}}
[ -n "$JAVA_HOME" ] || { echo "JAVA_HOME or QUAL_JAVA_HOME is required" >&2; exit 1; }
SDK=${ANDROID_SDK_ROOT:-}
if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
  SDK=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)
fi
[ -n "$SDK" ] || { echo "ANDROID_SDK_ROOT or local.properties sdk.dir is required" >&2; exit 1; }
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR" >&2; exit 1; }

BUILD="$ROOT/work/app-defects"
rm -rf "$BUILD"
mkdir -p "$BUILD"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 \
  -cp "$ANDROID_JAR" -d "$BUILD" \
  "$ROOT/app/src/main/java/agent/next/app/calendar/CalendarStore.java" \
  "$ROOT/app/src/main/java/agent/next/app/files/FilesStore.java" \
  "$ROOT/tools/app-defects/AppDomainDefectsTest.java"
"$JAVA_HOME/bin/java" -cp "$BUILD:$ANDROID_JAR" AppDomainDefectsTest

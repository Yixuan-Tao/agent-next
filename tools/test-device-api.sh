#!/bin/sh
set -eu

DEVICE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEVICE_JAVA_HOME=${QUAL_JAVA_HOME:-${JAVA_HOME:-}}
DEVICE_JAVAC=javac
DEVICE_JAVA=java
if [ -n "$DEVICE_JAVA_HOME" ]; then
  DEVICE_JAVAC="$DEVICE_JAVA_HOME/bin/javac"
  DEVICE_JAVA="$DEVICE_JAVA_HOME/bin/java"
fi

DEVICE_BUILD="$DEVICE_ROOT/work/device-api-build"
rm -rf "$DEVICE_BUILD/classes"
mkdir -p "$DEVICE_BUILD/classes"
find "$DEVICE_ROOT/device-api/src/main/java" "$DEVICE_ROOT/device-api/src/test/java" \
  -name '*.java' -print > "$DEVICE_BUILD/sources.list"

"$DEVICE_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -d "$DEVICE_BUILD/classes" @"$DEVICE_BUILD/sources.list"
"$DEVICE_JAVA" -cp "$DEVICE_BUILD/classes" agent.next.device.DeviceApiHostTest

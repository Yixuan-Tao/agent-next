#!/bin/sh
set -eu

BRAIN_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BRAIN_JAVA_HOME=${QUAL_JAVA_HOME:-${JAVA_HOME:-}}
BRAIN_JAVAC=javac
BRAIN_JAVA=java
if [ -n "$BRAIN_JAVA_HOME" ]; then
  BRAIN_JAVAC="$BRAIN_JAVA_HOME/bin/javac"
  BRAIN_JAVA="$BRAIN_JAVA_HOME/bin/java"
fi

BRAIN_BUILD="$BRAIN_ROOT/work/brain-build"
rm -rf "$BRAIN_BUILD/classes" "$BRAIN_BUILD/device-api"
mkdir -p "$BRAIN_BUILD/classes" "$BRAIN_BUILD/device-api"
find "$BRAIN_ROOT/device-api/src/main/java" -name '*.java' -print \
  > "$BRAIN_BUILD/device-api-sources.list"
"$BRAIN_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -d "$BRAIN_BUILD/device-api" @"$BRAIN_BUILD/device-api-sources.list"

find "$BRAIN_ROOT/brain/src/main/java" "$BRAIN_ROOT/brain/src/test/java" \
  -name '*.java' -print > "$BRAIN_BUILD/sources.list"
"$BRAIN_JAVAC" --release 17 -encoding UTF-8 -Xlint:all,-options -Werror \
  -cp "$BRAIN_BUILD/device-api" -d "$BRAIN_BUILD/classes" @"$BRAIN_BUILD/sources.list"
"$BRAIN_JAVA" -cp "$BRAIN_BUILD/classes:$BRAIN_BUILD/device-api" \
  agent.next.brain.BrainHostTest

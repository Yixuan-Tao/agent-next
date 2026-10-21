#!/bin/sh
set -eu

VISUAL_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VISUAL_JAVA_HOME=${QUAL_JAVA_HOME:-${JAVA_HOME:-}}
VISUAL_JAVAC=javac
VISUAL_JAVA=java
if [ -n "$VISUAL_JAVA_HOME" ]; then
  VISUAL_JAVAC="$VISUAL_JAVA_HOME/bin/javac"
  VISUAL_JAVA="$VISUAL_JAVA_HOME/bin/java"
fi
VISUAL_BUILD=$(mktemp -d "${TMPDIR:-/tmp}/agent-next-visual-tap.XXXXXX")
trap 'rm -rf "$VISUAL_BUILD"' EXIT HUP INT TERM
find "$VISUAL_ROOT/device-api/src/main/java" "$VISUAL_ROOT/brain/src/main/java" \
  -name '*.java' -print > "$VISUAL_BUILD/sources.list"
"$VISUAL_JAVAC" --release 17 -encoding UTF-8 -Xlint:all,-options -Werror \
  -d "$VISUAL_BUILD/classes" @"$VISUAL_BUILD/sources.list" \
  "$VISUAL_ROOT/app/src/main/java/agent/next/app/ReactiveVisualTapLocator.java" \
  "$VISUAL_ROOT/tools/visual-tap/ReactiveVisualTapLocatorTest.java"
"$VISUAL_JAVA" -cp "$VISUAL_BUILD/classes" agent.next.app.ReactiveVisualTapLocatorTest

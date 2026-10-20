#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-${QUAL_JAVA_HOME:-}}
[ -n "$JAVA_HOME" ] || { echo "JAVA_HOME or QUAL_JAVA_HOME is required" >&2; exit 1; }
OUT="$ROOT/work/files-gui"
rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" -d "$OUT" \
  "$ROOT/app/src/main/java/agent/next/app/skill/FolderName.java" \
  "$ROOT/tools/files-gui/FolderNameTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" FolderNameTest

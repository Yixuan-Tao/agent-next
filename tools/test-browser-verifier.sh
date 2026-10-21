#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TASK_JAVA_HOME=${QUAL_JAVA_HOME:-${JAVA_HOME:-}}
[ -n "$TASK_JAVA_HOME" ] || { echo "JAVA_HOME is required" >&2; exit 1; }
OUT="$ROOT/work/browser-verifier"
mkdir -p "$OUT"
CP="$ROOT/work/brain-build/classes:$ROOT/work/brain-build/device-api"
"$TASK_JAVA_HOME/bin/javac" --release 17 -cp "$CP" -d "$OUT" \
  "$ROOT/app/src/main/java/agent/next/app/skill/BrowserResultVerifier.java" \
  "$ROOT/app/src/main/java/agent/next/app/skill/BrowserTaskPlan.java" \
  "$ROOT/tools/browser-verifier/BrowserResultVerifierTest.java" \
  "$ROOT/tools/browser-verifier/BrowserTaskPlanTest.java"
"$TASK_JAVA_HOME/bin/java" -cp "$OUT:$CP" BrowserResultVerifierTest
"$TASK_JAVA_HOME/bin/java" -cp "$OUT:$CP" BrowserTaskPlanTest

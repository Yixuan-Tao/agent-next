#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-${QUAL_JAVA_HOME:-}}
[ -n "$JAVA_HOME" ] || { echo "JAVA_HOME or QUAL_JAVA_HOME is required" >&2; exit 1; }
export QUAL_JAVA_HOME=${QUAL_JAVA_HOME:-"$JAVA_HOME"}

# Ensure the brain test classes (including KnownDefectsTest) are compiled.
sh "$ROOT/tools/test-brain.sh" >/dev/null
"$JAVA_HOME/bin/java" -cp "$ROOT/work/brain-build/classes:$ROOT/work/brain-build/device-api" \
  agent.next.brain.KnownDefectsTest

sh "$ROOT/tools/test-app-defects.sh"

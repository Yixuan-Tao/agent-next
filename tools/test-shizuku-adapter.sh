#!/bin/sh
set -eu

ADAPTER_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADAPTER_JAVA_HOME=${QUAL_JAVA_HOME:-${JAVA_HOME:-}}
ADAPTER_JAVAC=javac
ADAPTER_JAVA=java
if [ -n "$ADAPTER_JAVA_HOME" ]; then
  ADAPTER_JAVAC="$ADAPTER_JAVA_HOME/bin/javac"
  ADAPTER_JAVA="$ADAPTER_JAVA_HOME/bin/java"
fi
ADAPTER_GRADLE_HOME=${GRADLE_USER_HOME:-$ADAPTER_ROOT/work/gradle-home}
ADAPTER_AAR_DIR=${SHIZUKU_AAR_DIR:-$ADAPTER_ROOT/work/shizuku-libs}
ADAPTER_BUILD="$ADAPTER_ROOT/work/shizuku-adapter-tests"
rm -rf "$ADAPTER_BUILD/classes"
mkdir -p "$ADAPTER_BUILD/libs" "$ADAPTER_BUILD/classes"
ADAPTER_CP="$ADAPTER_ROOT/work/service-policy-build/classes"
for ADAPTER_LIB in api aidl shared; do
  ADAPTER_AAR="$ADAPTER_AAR_DIR/$ADAPTER_LIB-13.1.5.aar"
  if [ ! -f "$ADAPTER_AAR" ]; then
    ADAPTER_AAR=$(find "$ADAPTER_GRADLE_HOME/caches/modules-2/files-2.1/dev.rikka.shizuku/$ADAPTER_LIB/13.1.5" -name "$ADAPTER_LIB-13.1.5.aar" 2>/dev/null | head -1 || true)
  fi
  if [ -z "${ADAPTER_AAR:-}" ] || [ ! -f "$ADAPTER_AAR" ]; then
    echo "Missing Shizuku 13.1.5 $ADAPTER_LIB AAR." >&2
    echo "Set SHIZUKU_AAR_DIR to a directory containing $ADAPTER_LIB-13.1.5.aar," >&2
    echo "or run the documented Gradle build with a populated GRADLE_USER_HOME." >&2
    exit 1
  fi
  unzip -p "$ADAPTER_AAR" classes.jar > "$ADAPTER_BUILD/libs/$ADAPTER_LIB.jar"
  ADAPTER_CP="$ADAPTER_CP:$ADAPTER_BUILD/libs/$ADAPTER_LIB.jar"
done
ADAPTER_AIDL="$ADAPTER_ROOT/capability/build/generated/aidl_source_output_dir/debug/out/agent/phone/ipc/IAgentShell.java"
if [ ! -f "$ADAPTER_AIDL" ]; then
  echo "Missing generated IAgentShell; run the documented Gradle build first." >&2
  exit 1
fi
find "$ADAPTER_ROOT/tools/service-tests/stubs" -name '*.java' -print > "$ADAPTER_BUILD/sources.list"
"$ADAPTER_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -cp "$ADAPTER_CP" -d "$ADAPTER_BUILD/classes" \
  @"$ADAPTER_BUILD/sources.list" "$ADAPTER_AIDL" \
  "$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/UserServiceProtocol.java" \
  "$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/ShizukuBackend.java" \
  "$ADAPTER_ROOT/tools/service-tests/agent/phone/connection/ShizukuBackendTest.java"
"$ADAPTER_JAVA" -cp "$ADAPTER_BUILD/classes:$ADAPTER_CP" agent.phone.connection.ShizukuBackendTest

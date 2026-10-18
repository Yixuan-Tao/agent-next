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

ADAPTER_BUILD="$ADAPTER_ROOT/work/capability-adapter-build"
rm -rf "$ADAPTER_BUILD"
mkdir -p "$ADAPTER_BUILD/device-api" "$ADAPTER_BUILD/device-api-tests" \
  "$ADAPTER_BUILD/adapter" "$ADAPTER_BUILD/tests"

find "$ADAPTER_ROOT/device-api/src/main/java" -name '*.java' -print \
  > "$ADAPTER_BUILD/device-api-sources.list"
"$ADAPTER_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -d "$ADAPTER_BUILD/device-api" @"$ADAPTER_BUILD/device-api-sources.list"

"$ADAPTER_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -cp "$ADAPTER_BUILD/device-api" -d "$ADAPTER_BUILD/device-api-tests" \
  "$ADAPTER_ROOT/device-api/src/test/java/agent/next/device/TestSupport.java" \
  "$ADAPTER_ROOT/device-api/src/test/java/agent/next/device/DeviceApiContract.java"

ADAPTER_MAIN="$ADAPTER_BUILD/adapter-sources.list"
cat > "$ADAPTER_MAIN" <<EOF
$ADAPTER_ROOT/capability/src/main/java/agent/phone/capability/BlockingDeviceApi.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/Capabilities.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/ConnectionDriver.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/ConnectionDriverFactory.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/ConnectionState.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/DisplaySession.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/FrameEvidence.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/executor/ObservationBudget.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/TargetObservation.java
$ADAPTER_ROOT/capability/src/main/java/agent/phone/connection/TargetSession.java
EOF
"$ADAPTER_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -cp "$ADAPTER_BUILD/device-api" -d "$ADAPTER_BUILD/adapter" @"$ADAPTER_MAIN"

ADAPTER_CP="$ADAPTER_BUILD/device-api:$ADAPTER_BUILD/device-api-tests:$ADAPTER_BUILD/adapter"
"$ADAPTER_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -cp "$ADAPTER_CP" -d "$ADAPTER_BUILD/tests" \
  "$ADAPTER_ROOT/capability/src/test/java/agent/phone/connection/FakeConnectionDriver.java" \
  "$ADAPTER_ROOT/capability/src/test/java/agent/phone/capability/CapabilityAdapterHostTest.java"

"$ADAPTER_JAVA" -cp "$ADAPTER_CP:$ADAPTER_BUILD/tests" \
  agent.phone.capability.CapabilityAdapterHostTest

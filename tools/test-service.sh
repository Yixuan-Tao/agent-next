#!/bin/sh
set -eu

SERVICE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SERVICE_JAVA_HOME=${QUAL_JAVA_HOME:-${JAVA_HOME:-}}
SERVICE_JAVAC=javac
SERVICE_JAVA=java
if [ -n "$SERVICE_JAVA_HOME" ]; then
  SERVICE_JAVAC="$SERVICE_JAVA_HOME/bin/javac"
  SERVICE_JAVA="$SERVICE_JAVA_HOME/bin/java"
fi
SERVICE_BUILD="$SERVICE_ROOT/work/service-policy-build"
rm -rf "$SERVICE_BUILD/classes"
mkdir -p "$SERVICE_BUILD/classes"
"$SERVICE_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -d "$SERVICE_BUILD/classes" \
  "$SERVICE_ROOT/device-api/src/main/java/agent/next/device/Text.java" \
  "$SERVICE_ROOT/device-api/src/main/java/agent/next/device/DisplaySpec.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/ClientPolicy.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/DisplayLifecycle.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/ObservationBudget.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/FrameLease.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/FrameLeaseTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/FrameSafetyPolicy.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/FrameSafetyPolicyTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/TargetLifecycle.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/TargetAudioIsolation.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/TargetAudioIsolationTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/TargetTaskMover.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/PrimaryTaskPolicy.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/PrimaryTaskPolicyTest.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/TargetLifecycleTest.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/ObservationBudgetTest.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/DisplayLifecycleTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/executor/ProtocolPolicy.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/executor/ServicePolicyTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/Capabilities.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/ConnectionState.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/DisplaySession.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetObservation.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetSession.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/FrameEvidence.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetTap.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetText.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetSwipe.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetBack.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/FrameEvidenceTest.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/TargetObservationTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/ConnectionCoordinator.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/PreviewRpc.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/PreviewRpcTest.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/ConnectionCoordinatorTest.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/os/Parcelable.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/os/Parcel.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/view/Surface.java"
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.ServicePolicyTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.PreviewRpcTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.ConnectionCoordinatorTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.DisplayLifecycleTest

"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.ObservationBudgetTest

"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.TargetObservationTest

"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.TargetLifecycleTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.TargetAudioIsolationTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.PrimaryTaskPolicyTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.FrameLeaseTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.executor.FrameSafetyPolicyTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.FrameEvidenceTest

# Exercise Android packet boundaries with bounded host-only Bundle/Parcel stand-ins.
"$SERVICE_JAVAC" --release 8 -encoding UTF-8 -Xlint:all,-options -Werror \
  -cp "$SERVICE_BUILD/classes" -d "$SERVICE_BUILD/classes" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/os/Parcelable.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/os/Parcel.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/os/Bundle.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/system/ErrnoException.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/system/OsConstants.java" \
  "$SERVICE_ROOT/tools/service-tests/stubs/android/os/SharedMemory.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetPackets.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/ObservationPackets.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/TargetPacketsTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/FramePackets.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/FramePacketsTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetTapPackets.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/TargetTapPacketsTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetTextPackets.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/TargetTextPacketsTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetSwipePackets.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/TargetSwipePacketsTest.java" \
  "$SERVICE_ROOT/capability/src/main/java/agent/phone/connection/TargetBackPackets.java" \
  "$SERVICE_ROOT/capability/src/test/java/agent/phone/connection/TargetBackPacketsTest.java"
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.TargetPacketsTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.FramePacketsTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.TargetTapPacketsTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.TargetTextPacketsTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.TargetSwipePacketsTest
"$SERVICE_JAVA" -cp "$SERVICE_BUILD/classes" agent.phone.connection.TargetBackPacketsTest

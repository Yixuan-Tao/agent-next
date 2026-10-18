#!/bin/sh
set -eu
probe_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$probe_dir/../qualification/build.env"
mkdir -p "$probe_dir/classes" "$probe_dir/dex"
"$QUAL_JAVA_HOME/bin/javap" -classpath "$QUAL_ANDROID_JAR" android.os.SharedMemory > "$probe_dir/api-signatures.txt"
"$QUAL_JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -cp "$QUAL_ANDROID_JAR" \
  -d "$probe_dir/classes" "$probe_dir/FrameMemoryProbe.java"
"$QUAL_JAVA_HOME/bin/jar" cf "$probe_dir/classes.jar" -C "$probe_dir/classes" .
"$QUAL_JAVA_HOME/bin/java" -cp "$QUAL_D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 36 --lib "$QUAL_ANDROID_JAR" --output "$probe_dir/dex" "$probe_dir/classes.jar"
"$QUAL_JAVA_HOME/bin/jar" cf "$probe_dir/probe.jar" -C "$probe_dir/dex" classes.dex

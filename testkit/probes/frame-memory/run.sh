#!/bin/sh
set -eu
probe_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
probe_adb=${1:?Usage: sh run.sh /absolute/path/to/adb serial}
probe_serial=${2:?Usage: sh run.sh /absolute/path/to/adb serial}
probe_remote=/data/local/tmp/agent-phone-frame-memory-probe.jar
sh "$probe_dir/build.sh"
"$probe_adb" -s "$probe_serial" push "$probe_dir/probe.jar" "$probe_remote"
trap '"$probe_adb" -s "$probe_serial" shell rm -f "$probe_remote" >/dev/null 2>&1 || true' EXIT
"$probe_adb" -s "$probe_serial" shell \
  "CLASSPATH=$probe_remote app_process /system/bin FrameMemoryProbe" > "$probe_dir/run.log" 2>&1
cat "$probe_dir/run.log"

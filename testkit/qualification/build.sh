#!/bin/sh
set -eu

qualification_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
qualification_root=$(CDPATH= cd -- "$qualification_dir/../.." && pwd)
qualification_build=${QUAL_BUILD_DIR:-"$qualification_root/work/qualification/build"}
: "${QUAL_JAVA_HOME:?Set QUAL_JAVA_HOME to a JDK 21 directory}"
: "${QUAL_ANDROID_JAR:?Set QUAL_ANDROID_JAR to API 36 android.jar}"

case "${1:-test}" in
  test|build) ;;
  *) echo 'Usage: build.sh test|build' >&2; exit 1 ;;
esac

mkdir -p "$qualification_build/classes" "$qualification_build/tests"
"$QUAL_JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -encoding UTF-8 \
  -cp "$QUAL_ANDROID_JAR" -d "$qualification_build/classes" \
  "$qualification_dir"/src/agent/qualification/*.java
"$QUAL_JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -encoding UTF-8 \
  -cp "$qualification_build/classes:$QUAL_ANDROID_JAR" -d "$qualification_build/tests" \
  "$qualification_dir"/tests/agent/qualification/*Test.java

for qualification_test in "$qualification_dir"/tests/agent/qualification/*Test.java; do
  qualification_test_name=$(basename "$qualification_test" .java)
  "$QUAL_JAVA_HOME/bin/java" -cp "$qualification_build/classes:$qualification_build/tests" \
    "agent.qualification.$qualification_test_name"
done

if [ "${1:-test}" = build ]; then
  : "${QUAL_D8_JAR:?Set QUAL_D8_JAR to the Android build-tools lib/d8.jar}"
  "$QUAL_JAVA_HOME/bin/jar" cf "$qualification_build/classes.jar" \
    -C "$qualification_build/classes" .
  "$QUAL_JAVA_HOME/bin/java" -cp "$QUAL_D8_JAR" com.android.tools.r8.D8 \
    --release --min-api 36 --lib "$QUAL_ANDROID_JAR" \
    --output "$qualification_build/dex.zip" "$qualification_build/classes.jar"
  cp "$qualification_build/dex.zip" "$qualification_build/qualification.jar"
  echo "Built $qualification_build/qualification.jar"
fi

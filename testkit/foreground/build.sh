#!/bin/sh
set -eu
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${TEST_SDK_TOOLS:?Set TEST_SDK_TOOLS to extracted build-tools directory}"
: "${TEST_ANDROID_JAR:?Set TEST_ANDROID_JAR to API 36 android.jar}"
: "${TEST_JAVA_HOME:?Set TEST_JAVA_HOME}"
: "${TEST_BUILD:?Set TEST_BUILD to an ignored work directory}"
mkdir -p "$TEST_BUILD/classes" "$TEST_BUILD/dex"
"$TEST_JAVA_HOME/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 \
  -cp "$TEST_ANDROID_JAR" -d "$TEST_BUILD/classes" "$test_dir"/src/*.java
"$TEST_JAVA_HOME/bin/jar" cf "$TEST_BUILD/classes.jar" -C "$TEST_BUILD/classes" .
"$TEST_SDK_TOOLS/aapt2" link -I "$TEST_ANDROID_JAR" --manifest "$test_dir/AndroidManifest.xml" \
  --min-sdk-version 36 --target-sdk-version 36 -o "$TEST_BUILD/resources.apk"
"$TEST_JAVA_HOME/bin/java" -cp "$TEST_SDK_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 36 --lib "$TEST_ANDROID_JAR" --output "$TEST_BUILD/dex" "$TEST_BUILD/classes.jar"
cp "$TEST_BUILD/resources.apk" "$TEST_BUILD/unsigned.apk"
(cd "$TEST_BUILD/dex" && "$TEST_JAVA_HOME/bin/jar" uf "$TEST_BUILD/unsigned.apk" classes.dex)
"$TEST_SDK_TOOLS/zipalign" -f 4 "$TEST_BUILD/unsigned.apk" "$TEST_BUILD/aligned.apk"
if [ ! -f "$TEST_BUILD/test.keystore" ]; then
  "$TEST_JAVA_HOME/bin/keytool" -genkeypair -keystore "$TEST_BUILD/test.keystore" \
    -storepass android -keypass android -alias test -keyalg RSA -validity 3650 \
    -dname 'CN=Local Foreground Test' >/dev/null 2>&1
fi
"$TEST_JAVA_HOME/bin/java" -jar "$TEST_SDK_TOOLS/lib/apksigner.jar" sign \
  --ks "$TEST_BUILD/test.keystore" --ks-key-alias test --ks-pass pass:android \
  --key-pass pass:android --out "$test_dir/foreground-test.apk" "$TEST_BUILD/aligned.apk"
"$TEST_JAVA_HOME/bin/java" -jar "$TEST_SDK_TOOLS/lib/apksigner.jar" verify --verbose "$test_dir/foreground-test.apk"

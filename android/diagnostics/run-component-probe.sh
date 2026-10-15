#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
terminal=android/terminal/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
shopt -s nullglob
jars=("${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/modules-2/files-2.1/com.github.mwiede/jsch/2.28.8/*/jsch-2.28.8.jar)
if [[ ${#jars[@]} != 1 || ! -f "$sdk/platforms/android-36/android.jar" || ! -d "$terminal" ]]; then
    echo 'Requires existing SDK 36, JSch 2.28.8 cache and current compiled terminal classes; see report.' >&2
    exit 1
fi
out=android/.work/myct-1p-probe
mkdir -p "$out"
cp_probe="$terminal:$sdk/platforms/android-36/android.jar:${jars[0]}"
javac -cp "$cp_probe" -d "$out" android/diagnostics/ComponentLatencyProbe.java
java -Xms32m -Xmx128m -cp "$out:$cp_probe" ComponentLatencyProbe

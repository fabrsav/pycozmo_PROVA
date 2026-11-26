#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
androidJar=/home/vpsuser/Android/Sdk/platforms/android-34/android.jar
outDir=build/device-checks
mkdir -p "$outDir/classes" "$outDir/dex"
/usr/lib/android-sdk/build-tools/debian/aapt2 link -I "$androidJar" --manifest tests/device/AndroidManifest.xml -o "$outDir/base.apk"
javac --release 11 -encoding UTF-8 -classpath "$androidJar" -d "$outDir/classes" tests/device/DeviceChecks.java
find "$outDir/classes" -name '*.class' -print > "$outDir/classes.txt"
java -cp /home/vpsuser/android-sdk/cmdline-tools/latest/r8.jar com.android.tools.r8.D8 --release --lib "$androidJar" --min-api 29 --output "$outDir/dex" @"$outDir/classes.txt"
(cd "$outDir/dex" && jar uf ../base.apk classes.dex)
zipalign -f 4 "$outDir/base.apk" "$outDir/installed.apk"
apksigner sign --ks .private/debug.keystore --ks-pass pass:android --key-pass pass:android "$outDir/installed.apk"
apksigner verify "$outDir/installed.apk"

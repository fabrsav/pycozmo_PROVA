#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdkRoot="${ANDROID_SDK_ROOT:-/home/vpsuser/Android/Sdk}"
androidJar="$sdkRoot/platforms/android-34/android.jar"
r8Jar="/home/vpsuser/android-sdk/cmdline-tools/latest/r8.jar"
aaptTool="${AAPT2:-/usr/lib/android-sdk/build-tools/debian/aapt2}"
appVersion=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName"))')
test -f "$androidJar"
test -f "$r8Jar"
test -x "$aaptTool"
rm -rf build/gen build/classes build/dex
mkdir -p build/gen build/classes build/dex dist .private assets res/drawable
python3 scripts/extract_headers.py
"$aaptTool" compile --dir res -o build/resources.zip
"$aaptTool" link -I "$androidJar" --manifest AndroidManifest.xml --java build/gen -A assets -o build/base.apk build/resources.zip
find src build/gen -name '*.java' -print > build/sources.txt
javac --release 11 -encoding UTF-8 -classpath "$androidJar" -d build/classes @build/sources.txt
find build/classes -name '*.class' -print > build/classes.txt
java -cp "$r8Jar" com.android.tools.r8.D8 --release --lib "$androidJar" --min-api 29 --output build/dex @build/classes.txt
cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f .private/debug.keystore ]; then
  keytool -genkeypair -keystore .private/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Cozmo S200 Debug' -keyalg RSA -keysize 2048 -validity 10000 -noprompt
fi
apkFile="dist/cozmo-s200-$appVersion.apk"
# Firmare su un output separato, mai in-place: apksigner copia via java.io.tmpdir,
# e su questo host /tmp non e' scrivibile (mount read-only mascherato da permessi 1777).
apksigner sign --ks .private/debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out build/signed.apk build/aligned.apk
apksigner verify --verbose build/signed.apk
cp build/signed.apk "$apkFile"
sha256sum "$apkFile"

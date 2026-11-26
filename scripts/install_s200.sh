#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:-100.94.42.57:5555}"
appVersion=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName"))')
apkFile="dist/cozmo-s200-$appVersion.apk"
model=$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')
maker=$(adb -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r')
api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
abi=$(adb -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')
if [ "$model" != S200 ] || [ "$maker" != DOOGEE ] || [ "$api" -lt 29 ] || [[ "$abi" != *arm64-v8a* ]]; then
  echo "Target diverso dal telefono verificato: $maker $model API=$api ABI=$abi" >&2
  exit 1
fi
apksigner verify "$apkFile"
adb -s "$serial" install --no-incremental -r "$apkFile"
adb -s "$serial" shell dumpsys package dev.s200.cozmo | rg 'versionName=|versionCode='
if [ "${2:-}" != "--no-launch" ]; then
  adb -s "$serial" shell am start -n dev.s200.cozmo/.MainActivity
fi

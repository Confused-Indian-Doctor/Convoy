#!/usr/bin/env bash
# Run inside a booted API 35 x86_64 emulator. Prefer a privately signed release when available.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/smoke
capture_diagnostics() {
  adb logcat -d > build/smoke/logcat.txt || true
  adb exec-out screencap -p > build/smoke/offline-startup.png || true
}
trap capture_diagnostics EXIT
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
apk="build/release/Convoy-${version}.apk"
if [[ ! -f "$apk" ]]; then
  apk=app/build/outputs/apk/debug/app-debug.apk
fi
printf 'Testing APK: %s\n' "$apk" | tee build/smoke/apk.txt
adb install -r "$apk" | tee build/smoke/install.txt
for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION RECORD_AUDIO POST_NOTIFICATIONS BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES; do
  adb shell pm grant com.convoy.offline "android.permission.$permission"
done
adb shell svc wifi disable
adb shell svc data disable
adb logcat -c
adb shell am start -W -n com.convoy.offline/.MainActivity | tee build/smoke/launch.txt
# Allow native map initialization and offline asset extraction to complete.
sleep 20
adb logcat -d > build/smoke/logcat.txt
adb shell pidof com.convoy.offline | tee build/smoke/process.txt
[[ -s build/smoke/process.txt ]] || { echo 'Convoy stopped after launch.' >&2; exit 1; }
if rg -q 'FATAL EXCEPTION|Fatal signal|ANR in com.convoy.offline' build/smoke/logcat.txt; then
  echo 'Crash or ANR detected during offline startup.' >&2
  exit 1
fi
adb shell uiautomator dump /sdcard/convoy-smoke.xml
adb pull /sdcard/convoy-smoke.xml build/smoke/ui.xml
adb shell dumpsys package com.convoy.offline > build/smoke/package.txt
printf 'PASS %s installed and stayed running offline on API 35.\n' "$apk" | tee build/smoke/result.txt

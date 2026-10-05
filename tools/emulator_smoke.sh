#!/usr/bin/env bash
# Run inside a booted API 35 x86_64 emulator. Prefer a privately signed release when available.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/smoke
private_dir="$(mktemp -d "${TMPDIR:-/tmp}/convoy-smoke.XXXXXX")"
remote_ui="/sdcard/convoy-smoke-$$.xml"
setup_open=false
capture_diagnostics() {
  local result=$?
  adb logcat -d > build/smoke/logcat.txt || true
  # The setup dialog contains a generated group key; keep it out of artifacts.
  if [[ "$setup_open" != true ]]; then
    adb exec-out screencap -p > build/smoke/final-state.png || true
  fi
  adb shell rm -f "$remote_ui" >/dev/null 2>&1 || true
  rm -rf "$private_dir"
  return "$result"
}
trap capture_diagnostics EXIT

dump_ui() {
  adb shell uiautomator dump "$remote_ui" >/dev/null
  adb exec-out cat "$remote_ui" > "$private_dir/ui.xml"
}

# Return only coordinates/state, never UI text (which can contain a group key).
locate_ui() {
  python3 - "$private_dir/ui.xml" "$1" "${2:-}" <<'PYUI'
import re
import sys
import xml.etree.ElementTree as ET

nodes = ET.parse(sys.argv[1]).getroot().iter("node")
mode, value = sys.argv[2:]
for node in nodes:
    attrs = node.attrib
    if attrs.get("enabled") == "false":
        continue
    text = attrs.get("text", "")
    matches = (
        (mode == "text" and text == value)
        or (mode == "positive" and text == value and attrs.get("resource-id") == "android:id/button1")
        or (mode == "name" and attrs.get("class") == "android.widget.EditText")
        or (mode == "hotspot" and attrs.get("class") == "android.widget.CheckBox" and text == value)
        or (mode == "gps" and text.startswith("GPS ·"))
        or (mode == "scroll" and attrs.get("class") == "android.widget.ScrollView")
    )
    if not matches:
        continue
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attrs.get("bounds", ""))
    if not bounds:
        continue
    left, top, right, bottom = map(int, bounds.groups())
    if right <= left or bottom <= top:
        continue
    x, y = (left + right) // 2, (top + bottom) // 2
    if mode == "scroll":
        height = bottom - top
        print(x, top + height * 4 // 5, x, top + height // 5)
    else:
        print(x, y, attrs.get("checked", "false"))
    sys.exit(0)
sys.exit(1)
PYUI
}

wait_control() {
  local mode=$1 value=$2 description=$3 scroll=${4:-false}
  local deadline=$((SECONDS + 15)) found swipe x1 y1 x2 y2
  while ((SECONDS < deadline)); do
    if dump_ui && found="$(locate_ui "$mode" "$value" 2>/dev/null)"; then
      printf '%s\n' "$found"
      return 0
    fi
    if [[ "$scroll" == true ]] && swipe="$(locate_ui scroll 2>/dev/null)"; then
      read -r x1 y1 x2 y2 <<< "$swipe"
      adb shell input swipe "$x1" "$y1" "$x2" "$y2" 300
    fi
    sleep 1
  done
  printf 'Missing required UI control after 15 seconds: %s\n' "$description" >&2
  return 1
}

tap_control() {
  local coordinates x y checked
  coordinates="$(wait_control "$@")"
  read -r x y checked <<< "$coordinates"
  adb shell input tap "$x" "$y"
}

assert_running() {
  local phase=$1
  if ! adb shell pidof com.convoy.offline > "build/smoke/process-${phase}.txt" || [[ ! -s "build/smoke/process-${phase}.txt" ]]; then
    printf 'Convoy stopped during %s.\n' "$phase" >&2
    return 1
  fi
  adb logcat -d > build/smoke/logcat.txt
  if rg -q 'FATAL EXCEPTION|Fatal signal|ANR in com.convoy.offline' build/smoke/logcat.txt; then
    printf 'Crash or ANR detected during %s.\n' "$phase" >&2
    return 1
  fi
}

wait_service() {
  local expected=$1 deadline=$((SECONDS + 15)) present
  while ((SECONDS < deadline)); do
    adb shell dumpsys activity services com.convoy.offline > "$private_dir/services.txt"
    present=false
    if rg -q 'ServiceRecord\{[^}]*com\.convoy\.offline/\.ConvoyService' "$private_dir/services.txt"; then
      present=true
    fi
    if [[ "$expected" == stopped && "$present" == false ]]; then return 0; fi
    if [[ "$expected" == started && "$present" == true ]] && rg -q 'isForeground=true' "$private_dir/services.txt"; then return 0; fi
    sleep 1
  done
  printf 'Convoy foreground service did not reach state "%s" within 15 seconds.\n' "$expected" >&2
  return 1
}
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
assert_running offline-startup
cp build/smoke/process-offline-startup.txt build/smoke/process.txt
adb exec-out screencap -p > build/smoke/offline-startup.png
dump_ui
cp "$private_dir/ui.xml" build/smoke/ui.xml
adb shell dumpsys package com.convoy.offline > build/smoke/package.txt

# Start through the activity: the service intentionally cannot be started by adb.
adb shell cmd location set-location-enabled true
setup_open=true
tap_control text 'Start trip' 'Start trip button'
tap_control name '' 'name field'
adb shell input keycombination 113 29 # Select any previously saved name (Ctrl+A).
adb shell input text ConvoySmoke
adb shell dumpsys input_method > "$private_dir/input-method.txt"
if rg -q 'mInputShown=true|mIsInputViewShown=true|isInputViewShown=true' "$private_dir/input-method.txt"; then
  adb shell input keyevent 4 # Dismiss a visible keyboard while keeping the dialog.
fi
hotspot="$(wait_control hotspot 'Create a local Wi-Fi hotspot when hosting' 'automatic hotspot checkbox' true)"
read -r x y checked <<< "$hotspot"
if [[ "$checked" == true ]]; then adb shell input tap "$x" "$y"; fi
hotspot="$(wait_control hotspot 'Create a local Wi-Fi hotspot when hosting' 'automatic hotspot checkbox' true)"
read -r x y checked <<< "$hotspot"
[[ "$checked" == false ]] || { echo 'Could not disable the automatic emulator hotspot.' >&2; exit 1; }
tap_control positive Continue 'Continue button'
wait_service started
wait_control text 'End trip' 'active trip button' >/dev/null
setup_open=false
assert_running trip-started
printf 'PASS trip entered a foreground service through the UI.\n' > build/smoke/service-start.txt

tap_control text Map 'Map tab'
adb emu geo fix -2.7541 52.7078 >/dev/null
wait_control gps '' 'fresh GPS status after Shrewsbury location injection' >/dev/null
assert_running live-gps
adb exec-out screencap -p > build/smoke/live-gps.png
adb logcat -d > build/smoke/live-gps-logcat.txt
dump_ui
cp "$private_dir/ui.xml" build/smoke/live-gps-ui.xml

tap_control text 'End trip' 'End trip button'
tap_control positive 'End trip' 'End trip confirmation button'
wait_service stopped
assert_running trip-stopped
printf 'PASS ending the trip stopped the service while the offline app stayed running.\n' > build/smoke/service-stop.txt
printf 'PASS %s installed offline, started a foreground trip, received GPS and stopped the trip on API 35.\n' "$apk" | tee build/smoke/result.txt

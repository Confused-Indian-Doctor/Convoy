#!/usr/bin/env bash
# Run inside a booted API 35 x86_64 emulator. Prefer a privately signed release when available.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/smoke
private_dir="$(mktemp -d "${TMPDIR:-/tmp}/convoy-smoke.XXXXXX")"
remote_ui="/sdcard/convoy-smoke-$$.xml"
setup_open=false
keep_gps_fresh=false
firewall_enabled=false
offline_chain="CVY_SMOKE_$$"
capture_diagnostics() {
  local result=$?
  adb logcat -d > build/smoke/logcat.txt || true
  # The setup dialog contains a generated group key; keep it out of artifacts.
  if [[ "$setup_open" != true ]]; then
    adb exec-out screencap -p > build/smoke/final-state.png || true
  elif ((result != 0)); then
    dump_ui || true
    if [[ -s "$private_dir/ui.xml" ]]; then
      sanitize_setup_ui || true
    fi
  fi
  adb shell rm -f "$remote_ui" >/dev/null 2>&1 || true
  if [[ "$firewall_enabled" == true ]]; then
    for firewall in iptables ip6tables; do
      adb shell "$firewall" -D OUTPUT -j "$offline_chain" >/dev/null 2>&1 || true
      adb shell "$firewall" -F "$offline_chain" >/dev/null 2>&1 || true
      adb shell "$firewall" -X "$offline_chain" >/dev/null 2>&1 || true
    done
  fi
  rm -rf "$private_dir"
  return "$result"
}
trap capture_diagnostics EXIT

dump_ui() {
  local coordinates x y
  adb shell uiautomator dump "$remote_ui" >/dev/null
  adb exec-out cat "$remote_ui" > "$private_dir/ui.xml"
  coordinates="$(launcher_anr_close)" || return $?
  if [[ -n "$coordinates" ]]; then
    read -r x y <<< "$coordinates"
    adb shell input tap "$x" "$y"
    printf 'Dismissed Pixel Launcher ANR using android:id/aerr_close.\n' >> build/smoke/launcher-anr-dismissals.txt
    sleep 1
    adb shell uiautomator dump "$remote_ui" >/dev/null
    adb exec-out cat "$remote_ui" > "$private_dir/ui.xml"
    coordinates="$(launcher_anr_close)" || return $?
    if [[ -n "$coordinates" ]]; then
      echo 'Pixel Launcher ANR dialog remained after Close app.' >&2
      return 1
    fi
  fi
}

launcher_anr_close() {
  python3 - "$private_dir/ui.xml" <<'PYANR'
import re
import sys
import xml.etree.ElementTree as ET

nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
titles = [n.get("text", "").replace("’", "'") for n in nodes
          if n.get("package") == "android" and n.get("resource-id") == "android:id/alertTitle"]
if "Convoy isn't responding" in titles:
    sys.exit("Convoy ANR dialog detected; refusing to dismiss it.")
if "Pixel Launcher isn't responding" not in titles:
    sys.exit(0)
for node in nodes:
    if (node.get("package") != "android" or node.get("resource-id") != "android:id/aerr_close"
            or node.get("text", "").casefold() != "close app" or node.get("enabled") != "true"):
        continue
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if bounds:
        left, top, right, bottom = map(int, bounds.groups())
        if right > left and bottom > top:
            print((left + right) // 2, (top + bottom) // 2)
            sys.exit(0)
sys.exit("Pixel Launcher ANR has no enabled Close app control.")
PYANR
}

sanitize_setup_ui() {
  python3 - "$private_dir/ui.xml" build/smoke/setup-ui-sanitized.xml <<'PYSANITIZE'
import sys
import xml.etree.ElementTree as ET

tree = ET.parse(sys.argv[1])
nodes = list(tree.getroot().iter("node"))
edits = [node for node in nodes if node.get("class", "").endswith("EditText")]
with open(sys.argv[2].replace("setup-ui-sanitized.xml", "setup-field-lengths.txt"), "w") as lengths:
    for index, node in enumerate(edits, 1):
        lengths.write(f"field {index}: length={len(node.get('text', ''))}, focused={node.get('focused', 'false')}\n")
values = {node.get(attribute, "") for node in edits for attribute in ("text", "content-desc", "hint")}
values.discard("")
for node in edits:
    for attribute in ("text", "content-desc", "hint"):
        node.attrib.pop(attribute, None)
# Remove copies of field values from any inherited accessibility descriptions.
for node in nodes:
    for attribute, content in list(node.attrib.items()):
        for value in values:
            content = content.replace(value, "[redacted]")
        node.set(attribute, content)
tree.write(sys.argv[2], encoding="utf-8", xml_declaration=True)
PYSANITIZE
}

# Return only coordinates/state, never UI text (which can contain a group key).
locate_ui() {
  python3 - "$private_dir/ui.xml" "$1" "${2:-}" <<'PYUI'
import re
import sys
import xml.etree.ElementTree as ET

nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
mode, value = sys.argv[2:]
if mode == "setup_closed":
    if any(n.get("text") == "Bring your crew together" for n in nodes):
        sys.exit(1)
    print("0 0 false")
    sys.exit(0)
first_edit = next((n for n in nodes if n.get("class") == "android.widget.EditText"), None)
for node in nodes:
    attrs = node.attrib
    if attrs.get("enabled") == "false":
        continue
    text = attrs.get("text", "")
    matches = (
        (mode == "text" and text == value)
        or (mode == "positive" and text.casefold() == value.casefold() and attrs.get("resource-id") == "android:id/button1")
        or (mode == "edit" and attrs.get("class") == "android.widget.EditText")
        or (mode == "edit_focused" and node is first_edit and attrs.get("focused") == "true")
        or (mode == "edit_text" and node is first_edit and text == value)
        or (mode == "prefix" and text.startswith(value))
        or (mode == "hotspot" and attrs.get("class") == "android.widget.CheckBox" and text == value)
        or (mode == "gps" and text.startswith("GPS ·"))
        or (mode == "eta" and re.fullmatch(r"\d+ min|\d+ h \d+ min", text))
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

refresh_gps() {
  if [[ "$keep_gps_fresh" == true ]]; then
    adb emu geo fix -2.7541 52.7078 >/dev/null
  fi
}

wait_control() {
  local mode=$1 value=$2 description=$3 scroll=${4:-false}
  local deadline=$((SECONDS + 15)) found swipe x1 y1 x2 y2
  while ((SECONDS < deadline)); do
    refresh_gps
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

dismiss_keyboard() {
  adb shell dumpsys input_method > "$private_dir/input-method.txt"
  if rg -q 'mInputShown=true|mIsInputViewShown=true|isInputViewShown=true' "$private_dir/input-method.txt"; then
    adb shell input keyevent 4 # Dismiss a visible keyboard while keeping the dialog.
  fi
}

tap_control() {
  local coordinates x y checked
  coordinates="$(wait_control "$@")"
  read -r x y checked <<< "$coordinates"
  adb shell input tap "$x" "$y"
}

enter_smoke_name() {
  local attempt
  tap_control edit '' 'name field'
  wait_control edit_focused '' 'focused name field' >/dev/null
  sleep 1 # Let the first keyboard/focus transition finish before injecting keys.
  for attempt in 1 2; do
    adb shell input keycombination 113 29
    adb shell input text ConvoySmoke
    sleep 1
    if dump_ui && locate_ui edit_text ConvoySmoke >/dev/null; then return 0; fi
  done
  echo 'Could not verify ConvoySmoke in the first name field after one input retry.' >&2
  return 1
}

submit_setup() {
  local deadline=$((SECONDS + 15)) coordinates x y checked attempt
  for attempt in 1 2 3; do
    if ((SECONDS >= deadline)); then break; fi
    dump_ui
    if locate_ui setup_closed >/dev/null; then return 0; fi
    locate_ui edit_text ConvoySmoke >/dev/null || {
      echo 'The verified smoke name changed before Continue.' >&2; return 1;
    }
    coordinates="$(locate_ui positive Continue)" || {
      echo 'The setup dialog has no enabled Continue button.' >&2; return 1;
    }
    read -r x y checked <<< "$coordinates"
    adb shell input tap "$x" "$y"
    sleep 1
    dump_ui
    if locate_ui setup_closed >/dev/null; then return 0; fi
  done
  echo 'Continue did not dismiss the verified trip setup dialog within 15 seconds.' >&2
  return 1
}

assert_running() {
  local phase=$1 convoy_pid
  if ! adb shell pidof com.convoy.offline > "build/smoke/process-${phase}.txt" || [[ ! -s "build/smoke/process-${phase}.txt" ]]; then
    printf 'Convoy stopped during %s.\n' "$phase" >&2
    return 1
  fi
  adb logcat -d > build/smoke/logcat.txt
  if rg -q 'ANR in com.convoy.offline' build/smoke/logcat.txt; then
    printf 'Convoy ANR detected during %s.\n' "$phase" >&2
    return 1
  fi
  read -r convoy_pid _ < "build/smoke/process-${phase}.txt"
  adb logcat -d --pid="$convoy_pid" > build/smoke/convoy-logcat.txt
  if rg -q 'FATAL EXCEPTION|Fatal signal' build/smoke/convoy-logcat.txt; then
    printf 'Convoy crash detected during %s.\n' "$phase" >&2
    return 1
  fi
  if rg -q 'Mbgl.*\[Style\]: Failed to load source (basemap|overture):' build/smoke/convoy-logcat.txt; then
    printf 'Convoy offline map source failed during %s; see convoy-logcat.txt.\n' "$phase" >&2
    return 1
  fi
}

verify_offline_network() {
  local phase=$1 firewall family destination probe
  adb shell ip address show > "build/smoke/network-addresses-${phase}.txt"
  adb shell ip route show table all > "build/smoke/network-routes-${phase}.txt"
  adb shell settings get global airplane_mode_on > "build/smoke/airplane-mode-${phase}.txt"
  [[ "$(tr -d '\r\n' < "build/smoke/airplane-mode-${phase}.txt")" == 1 ]] || {
    echo 'Airplane mode was not enabled for offline validation.' >&2; return 1;
  }
  : > "build/smoke/network-firewall-${phase}.txt"
  for firewall in iptables ip6tables; do
    adb shell "$firewall" -C OUTPUT -j "$offline_chain"
    adb shell "$firewall" -C "$offline_chain" -j REJECT
    printf '%s\n' "$firewall" >> "build/smoke/network-firewall-${phase}.txt"
    adb shell "$firewall" -S "$offline_chain" >> "build/smoke/network-firewall-${phase}.txt"
  done
  # Direct addresses avoid mistaking a failed DNS lookup for an offline device.
  for family in 4 6; do
    destination=1.1.1.1
    if [[ "$family" == 6 ]]; then destination=2606:4700:4700::1111; fi
    probe="build/smoke/external-probe-ipv${family}-${phase}.txt"
    if adb shell ping "-$family" -c 1 -W 2 "$destination" > "$probe" 2>&1; then
      printf 'External IPv%s connectivity remained available during %s.\n' "$family" "$phase" >&2
      return 1
    fi
    if rg -qi 'not found|invalid option|unknown option|usage:' "$probe"; then
      printf 'Could not execute the external IPv%s connectivity probe; see %s.\n' "$family" "$probe" >&2
      return 1
    fi
  done
  for firewall in iptables ip6tables; do
    adb shell "$firewall" -L "$offline_chain" -n -v >> "build/smoke/network-firewall-${phase}.txt"
  done
  printf 'PASS outbound IPv4/IPv6 rejected, loopback/ADB retained, and both external probes failed.\n' > "build/smoke/network-proof-${phase}.txt"
}

disable_external_network() {
  adb root | tee build/smoke/adb-root.txt
  timeout 20 adb wait-for-device
  [[ "$(adb shell id -u | tr -d '\r\n')" == 0 ]] || {
    echo 'Offline firewall validation requires the rootable Google APIs emulator image.' >&2; return 1;
  }
  adb shell cmd connectivity airplane-mode enable
  adb shell svc wifi disable
  adb shell svc data disable
  firewall_enabled=true
  for firewall in iptables ip6tables; do
    adb shell "$firewall" -N "$offline_chain"
    adb shell "$firewall" -A "$offline_chain" -o lo -j RETURN
    # Emulator ADB can use TCP port 5555; permit only its reply transport.
    adb shell "$firewall" -A "$offline_chain" -p tcp --sport 5555 -j RETURN
    adb shell "$firewall" -A "$offline_chain" -j REJECT
    adb shell "$firewall" -I OUTPUT 1 -j "$offline_chain"
  done
  verify_offline_network initial
}

wait_service() {
  local expected=$1 deadline=$((SECONDS + 15)) present
  while ((SECONDS < deadline)); do
    refresh_gps
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
disable_external_network
adb logcat -c
adb shell am start -W -n com.convoy.offline/.MainActivity | tee build/smoke/launch.txt
# Allow native map initialization and offline asset extraction to complete.
sleep 20
assert_running offline-startup
cp build/smoke/process-offline-startup.txt build/smoke/process.txt
dump_ui
adb exec-out screencap -p > build/smoke/offline-startup.png
cp "$private_dir/ui.xml" build/smoke/ui.xml
adb shell dumpsys package com.convoy.offline > build/smoke/package.txt

# Start through the activity: the service intentionally cannot be started by adb.
adb shell cmd location set-location-enabled true
setup_open=true
tap_control text 'Start trip' 'Start trip button'
enter_smoke_name
dismiss_keyboard
hotspot="$(wait_control hotspot 'Create a local Wi-Fi hotspot when hosting' 'automatic hotspot checkbox' true)"
read -r x y checked <<< "$hotspot"
if [[ "$checked" == true ]]; then adb shell input tap "$x" "$y"; fi
hotspot="$(wait_control hotspot 'Create a local Wi-Fi hotspot when hosting' 'automatic hotspot checkbox' true)"
read -r x y checked <<< "$hotspot"
[[ "$checked" == false ]] || { echo 'Could not disable the automatic emulator hotspot.' >&2; exit 1; }
submit_setup
wait_service started
keep_gps_fresh=true
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

# Exercise the actual bundled place index and offline road graph with data off.
tap_control text 'Search offline places' 'offline place search button'
tap_control edit '' 'offline search field'
adb shell input text Shrewsbury%sCastle
dismiss_keyboard
tap_control positive Search 'offline Search button'
tap_control prefix 'Shrewsbury Castle' 'Shrewsbury Castle offline search result'
tap_control text 'Navigate here' 'Navigate here button' true
wait_control eta '' 'numeric ETA from a calculated offline Castle route' >/dev/null
assert_running offline-route
adb exec-out screencap -p > build/smoke/offline-route.png
adb logcat -d > build/smoke/offline-route-logcat.txt
dump_ui
cp "$private_dir/ui.xml" build/smoke/offline-route-ui.xml
printf 'PASS offline Castle search and road routing produced a numeric ETA with Wi-Fi/mobile data disabled.\n' > build/smoke/offline-route.txt

# Stop only this temporary navigation target; leave any saved trip plan intact.
tap_control text Stop 'Stop navigation button'
tap_control positive 'Stop route' 'Stop route confirmation button'
tap_control text 'End trip' 'End trip button'
tap_control positive 'End trip' 'End trip confirmation button'
keep_gps_fresh=false
wait_service stopped
assert_running trip-stopped
verify_offline_network final
printf 'PASS ending the trip stopped the service while the offline app stayed running.\n' > build/smoke/service-stop.txt
printf 'PASS %s installed with external networking blocked, started a foreground trip, received GPS, searched and routed offline, and stopped the trip on API 35.\n' "$apk" | tee build/smoke/result.txt

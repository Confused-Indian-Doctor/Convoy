#!/usr/bin/env bash
# Run inside a booted API 35 x86_64 emulator. Prefer a privately signed release when available.
set -euo pipefail
cd "$(dirname "$0")/.."
for dependency in adb python3 grep timeout; do
  command -v "$dependency" >/dev/null || {
    printf 'Required smoke dependency is missing: %s\n' "$dependency" >&2
    exit 1
  }
done
mkdir -p build/smoke
private_dir="$(mktemp -d "${TMPDIR:-/tmp}/convoy-smoke.XXXXXX")"
remote_ui="/sdcard/convoy-smoke-$$.xml"
setup_open=false
keep_gps_fresh=false
smoke_gps_longitude=-2.7541
smoke_gps_latitude=52.7078
firewall_enabled=false
offline_chain="CVY_SMOKE_$$"
capture_diagnostics() {
  local result=$?
  if [[ "$setup_open" == true ]]; then dump_ui >/dev/null 2>&1 && sanitize_setup_ui >/dev/null 2>&1 || true; fi
  record_logcat build/smoke/logcat.txt || true
  # Invite QR pixels, share previews and advanced group-key fields grant access.
  # Keep every such window out of public diagnostic screenshots.
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

capture_ui() {
  rm -f "$private_dir/ui.xml" "$private_dir/ui-next.xml"
  adb shell rm -f "$remote_ui" >/dev/null || return 1
  if ! timeout 15 adb shell uiautomator dump "$remote_ui" > "$private_dir/uiautomator-output.txt" 2>&1; then
    echo 'uiautomator did not complete a fresh UI capture within 15 seconds.' >&2
    return 1
  fi
  if grep -Eiq 'ERROR:|could not get idle state' "$private_dir/uiautomator-output.txt"; then
    echo 'uiautomator reported an error instead of a fresh UI hierarchy.' >&2
    return 1
  fi
  if ! adb exec-out cat "$remote_ui" > "$private_dir/ui-next.xml" 2> "$private_dir/ui-read-error.txt" || [[ ! -s "$private_dir/ui-next.xml" ]]; then
    echo 'uiautomator did not produce a fresh, nonempty UI hierarchy.' >&2
    return 1
  fi
  python3 - "$private_dir/ui-next.xml" <<'PYFRESH'
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except (OSError, ET.ParseError):
    sys.exit("The fresh UI hierarchy was not valid XML.")
if root.tag != "hierarchy" or not list(root.iter("node")):
    sys.exit("The fresh UI hierarchy contained no accessible nodes.")
PYFRESH
  [[ $? == 0 ]] || return 1
  mv "$private_dir/ui-next.xml" "$private_dir/ui.xml"
}

dump_ui() {
  local coordinates x y
  capture_ui || return 1
  coordinates="$(launcher_anr_close)" || return $?
  if [[ -n "$coordinates" ]]; then
    read -r x y <<< "$coordinates"
    adb shell input tap "$x" "$y"
    printf 'Dismissed Pixel Launcher ANR using android:id/aerr_close.\n' >> build/smoke/launcher-anr-dismissals.txt
    sleep 1
    capture_ui || return 1
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
  python3 - "$private_dir/ui.xml" build/smoke/setup-ui-sanitized.xml "$private_dir/redaction-values.json" <<'PYSANITIZE'
import re
import sys
import json
from pathlib import Path
import xml.etree.ElementTree as ET

tree = ET.parse(sys.argv[1])
nodes = list(tree.getroot().iter("node"))
edits = [node for node in nodes if node.get("class", "").endswith("EditText")]
with open(sys.argv[2].replace("setup-ui-sanitized.xml", "setup-field-lengths.txt"), "w") as lengths:
    for index, node in enumerate(edits, 1):
        lengths.write(f"field {index}: length={len(node.get('text', ''))}, focused={node.get('focused', 'false')}\n")
values = {node.get(attribute, "") for node in edits for attribute in ("text", "content-desc", "hint")}
values.discard("")
previous=json.loads(Path(sys.argv[3]).read_text()) if Path(sys.argv[3]).exists() else []
Path(sys.argv[3]).write_text(json.dumps(sorted(set(previous)|values)))
for node in edits:
    for attribute in ("text", "content-desc", "hint"):
        node.attrib.pop(attribute, None)
# Remove copies of field values from any inherited accessibility descriptions.
for node in nodes:
    for attribute, content in list(node.attrib.items()):
        for value in values:
            content = content.replace(value, "[redacted]")
        content = re.sub(r"convoy://join/[A-Za-z0-9_-]+", "[redacted invite]", content)
        content = re.sub(r"Q1ZJMQ[A-Za-z0-9_-]{100,1594}", "[redacted invite]", content)
        node.set(attribute, content)
tree.write(sys.argv[2], encoding="utf-8", xml_declaration=True)
PYSANITIZE
}

# Android may record an incoming VIEW URI in system ActivityTaskManager logs.
# Preserve crash diagnostics while removing bearer invites from public artifacts.
record_logcat() {
  local destination=$1
  shift
  adb logcat -d "$@" | python3 -c 'import re,sys,json
from pathlib import Path
value=sys.stdin.read()
p=Path(sys.argv[1])
if p.is_file():
 for secret in json.loads(p.read_text()):
  if secret:value=value.replace(secret,"[redacted input]")
value=re.sub(r"convoy://join/[A-Za-z0-9_-]+", "[redacted convoy invite]", value)
value=re.sub(r"Q1ZJMQ[A-Za-z0-9_-]{100,1594}", "[redacted convoy invite]", value)
sys.stdout.write(value)' "$private_dir/redaction-values.json" > "$destination"
}

assert_consumer_connection_ui() {
  local phase=$1
  dump_ui
  python3 - "$private_dir/ui.xml" "$phase" <<'PYCONSUMER'
import re
import sys
import xml.etree.ElementTree as ET

nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
for node in nodes:
    if node.get("class", "").endswith("EditText"):
        sys.exit("Consumer onboarding unexpectedly contains a manual input field.")
    for field in ("text", "content-desc", "hint"):
        value = node.get(field, "")
        if re.search(r"\b(?:IP address|host IP|group key|encryption key|TCP port|hotspot password|session ID)\b", value, re.I):
            sys.exit("Consumer onboarding unexpectedly exposes technical connection fields.")
        if "convoy://join/" in value or re.search(r"Q1ZJMQ[A-Za-z0-9_-]{100,1594}", value):
            sys.exit("Invitation credentials leaked into accessibility text.")
print("PASS consumer onboarding has no IP, port, password, key or invitation fields:", sys.argv[2])
PYCONSUMER
}

# Return only coordinates/state, never UI text (which can contain an invite/key).
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
        or (mode == "title" and text == value and attrs.get("resource-id") == "android:id/alertTitle")
        or (mode == "positive" and text.casefold() == value.casefold() and attrs.get("resource-id") == "android:id/button1")
        or (mode == "edit" and attrs.get("class") == "android.widget.EditText")
        or (mode == "edit_focused" and node is first_edit and attrs.get("focused") == "true")
        or (mode == "edit_text" and node is first_edit and re.sub(r"\s+", "", text).casefold() == value.casefold())
        or (mode == "prefix" and text.startswith(value))
        or (mode == "description" and attrs.get("content-desc", "") == value)
        or (mode == "hotspot" and attrs.get("class") == "android.widget.CheckBox" and text == value)
        or (mode == "gps" and (text.startswith("GPS ·") or text.startswith("GPS •")))
        or (mode == "gpx_progress" and text.startswith("Following GPX track")
            and " remaining" in text and " off route" in text)
        or (mode == "transfer" and re.fullmatch(r"\d+(?:\.\d+)? (?:B|KiB|MiB|GiB) / .* · \d+%", text)
            and not re.match(r"0(?:\.0)? (?:B|KiB|MiB|GiB) /", text)
            and not text.endswith(" · 100%"))
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
    adb emu geo fix "$smoke_gps_longitude" "$smoke_gps_latitude" >/dev/null
  fi
}

wait_control() {
  local mode=$1 value=$2 description=$3 scroll=${4:-false}
  local allowed_seconds=${5:-15}
  local deadline=$((SECONDS + allowed_seconds)) found swipe x1 y1 x2 y2
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
  printf 'Missing required UI control after %s seconds: %s\n' "$allowed_seconds" "$description" >&2
  return 1
}

dismiss_keyboard() {
  local title=$1
  # Called only after tapping and typing into a known EditText. One Back closes
  # its keyboard; do not depend on version-specific dumpsys field formatting.
  adb shell input keyevent 4
  sleep 1
  dump_ui
  locate_ui title "$title" >/dev/null || {
    printf 'Input dialog "%s" closed while dismissing its keyboard.\n' "$title" >&2
    return 1
  }
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

assert_running() {
  local phase=$1 convoy_pid
  if ! adb shell pidof com.convoy.offline > "build/smoke/process-${phase}.txt" || [[ ! -s "build/smoke/process-${phase}.txt" ]]; then
    printf 'Convoy stopped during %s.\n' "$phase" >&2
    return 1
  fi
  record_logcat build/smoke/logcat.txt
  if grep -Eq 'ANR in com.convoy.offline' build/smoke/logcat.txt; then
    printf 'Convoy ANR detected during %s.\n' "$phase" >&2
    return 1
  fi
  read -r convoy_pid _ < "build/smoke/process-${phase}.txt"
  record_logcat build/smoke/convoy-logcat.txt --pid="$convoy_pid"
  if grep -Eq 'FATAL EXCEPTION|Fatal signal' build/smoke/convoy-logcat.txt; then
    printf 'Convoy crash detected during %s.\n' "$phase" >&2
    return 1
  fi
  if grep -Eq 'Mbgl.*\[Style\]: Failed to load source (basemap|overture):' build/smoke/convoy-logcat.txt; then
    printf 'Convoy offline map source failed during %s; see convoy-logcat.txt.\n' "$phase" >&2
    return 1
  fi
}

verify_offline_network() {
  local phase=$1 firewall family destination probe probe_command
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
    probe_command=ping
    if [[ "$family" == 6 ]]; then destination=2606:4700:4700::1111; probe_command=ping6; fi
    probe="build/smoke/external-probe-ipv${family}-${phase}.txt"
    if adb shell "$probe_command" -c 1 -W 2 "$destination" > "$probe" 2>&1; then
      printf 'External IPv%s connectivity remained available during %s.\n' "$family" "$phase" >&2
      return 1
    fi
    if grep -Eiq 'not found|invalid option|unknown option|usage:' "$probe"; then
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
    if grep -Eq 'ServiceRecord\{[^}]*com\.convoy\.offline/\.ConvoyService' "$private_dir/services.txt"; then
      present=true
    fi
    if [[ "$expected" == stopped && "$present" == false ]]; then return 0; fi
    if [[ "$expected" == started && "$present" == true ]] && grep -Eq 'isForeground=true' "$private_dir/services.txt"; then return 0; fi
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
adb logcat -c
adb shell cmd connectivity airplane-mode disable
adb shell svc wifi enable
adb shell svc data enable
adb shell am start -W -n com.convoy.offline/.MainActivity | tee build/smoke/launch.txt
# Allow native map initialization and offline asset extraction to complete.
sleep 20

# Exercise the real hosted release before blocking all external networking. Throttling makes
# progress/cancellation observable without downloading an unnecessarily large regional pack.
wait_control text 'convoy' 'Home dashboard before downloads' >/dev/null
wait_service stopped
tap_control text 'Offline Maps' 'UK offline-map downloads' true
wait_control text Scotland 'compact Scotland region row' >/dev/null
dump_ui
python3 - "$private_dir/ui.xml" build/smoke/compact-map-rows.txt <<'PYROWS'
import sys
import xml.etree.ElementTree as ET
names = {"Scotland", "Northern England", "Southern England", "Wales", "Northern Ireland"}
nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
visible = {node.get("text") for node in nodes if node.get("text") in names and node.get("bounds") != "[0,0][0,0]"}
if len(visible) < 3:
    sys.exit("Fewer than three compact UK regional rows are visible together.")
with open(sys.argv[2], "w") as output:
    output.write("PASS several UK map regions are visible in the compact list: " + str(len(visible)) + "\n")
PYROWS
# The emulator's modem bandwidth limit does not limit its separate virtual Wi-Fi link.
# Use cellular for this observation, then restore full-speed Wi-Fi for complete installation.
adb shell svc wifi disable
adb shell svc data enable
adb emu network speed edge > build/smoke/download-network-throttle.txt
sleep 4
adb shell dumpsys connectivity > build/smoke/download-cellular-connectivity.txt
tap_control description 'Download Northern Ireland' 'real configured Northern Ireland source' true
tap_control positive Download 'confirm real regional download'
wait_control transfer '' 'actual nonzero regional download bytes' true 90 >/dev/null
dump_ui
cp "$private_dir/ui.xml" build/smoke/regional-download-progress.xml
adb shell input keyevent 3
sleep 3
adb shell dumpsys activity services com.convoy.offline > build/smoke/download-background-service.txt
grep -q 'androidx.work.impl.foreground.SystemForegroundService' build/smoke/download-background-service.txt || {
  echo 'Map download did not retain its foreground worker while the app was backgrounded.' >&2; exit 1;
}
adb shell am start -W -n com.convoy.offline/.MainActivity >/dev/null
wait_control transfer '' 'download progress after backgrounding' true 30 >/dev/null
tap_control description 'Cancel Northern Ireland' 'cancel incomplete regional download' true
wait_control prefix 'Download cancelled' 'cancelled regional transfer' true >/dev/null
wait_control description 'Retry Northern Ireland' 'cancelled pack offers retry' true >/dev/null
tap_control description 'Retry Northern Ireland' 'retry cancelled real download' true
tap_control positive Download 'confirm retry'
wait_control transfer '' 'actual bytes on regional retry' true 90 >/dev/null
tap_control description 'Cancel Northern Ireland' 'cancel retry safely' true
wait_control prefix 'Download cancelled' 'retry cancelled without installing partial data' true >/dev/null
adb emu network speed full >/dev/null
adb shell svc wifi enable
tap_control description 'Retry Northern Ireland' 'complete real regional map after retry' true
tap_control positive Download 'confirm complete regional download'
wait_control description 'Open Northern Ireland' 'complete verified regional PMTiles installed' true 180 >/dev/null
tap_control description 'Download United Kingdom overview' 'real small UK overview source' true
tap_control positive Download 'confirm UK overview download'
wait_control description 'Open United Kingdom overview' 'verified overview installed by WorkManager' true 120 >/dev/null
dump_ui
cp "$private_dir/ui.xml" build/smoke/overview-installed.xml
record_logcat build/smoke/download-logcat.txt
wait_service stopped
assert_running real-map-download
adb emu network speed full >/dev/null
adb shell input keyevent 4
wait_control text 'convoy' 'Home after installed overview' >/dev/null
printf 'PASS actual hosted regional bytes/progress, background worker, cancel/retry and complete SHA-256-validated Northern Ireland plus UK overview installation.\n' > build/smoke/map-download.txt

# A fresh process now has no external IPv4/IPv6 access. All remaining regressions run offline.
disable_external_network
adb shell am force-stop com.convoy.offline
adb logcat -c
adb shell am start -W -n com.convoy.offline/.MainActivity >/dev/null
sleep 5
assert_running offline-startup
cp build/smoke/process-offline-startup.txt build/smoke/process.txt
dump_ui
adb exec-out screencap -p > build/smoke/offline-startup.png
cp "$private_dir/ui.xml" build/smoke/ui.xml
adb shell dumpsys package com.convoy.offline > build/smoke/package.txt

# Dashboard navigation and local map GPS must work before any convoy exists.
wait_control text 'convoy' 'Home dashboard' >/dev/null
wait_service stopped
for permission in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb shell pm revoke com.convoy.offline "android.permission.$permission"
  adb shell pm set-permission-flags com.convoy.offline "android.permission.$permission" user-set user-fixed
done
adb shell am start -W -n com.convoy.offline/.MainActivity >/dev/null
wait_control text 'convoy' 'Home after location permission revocation' >/dev/null
tap_control text Map 'Map with permission denied'
wait_control text 'Location permission required' 'honest location denial state' >/dev/null
wait_service stopped
adb shell input keyevent 4
wait_control text 'convoy' 'Back from denied map' >/dev/null
for permission in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb shell pm clear-permission-flags com.convoy.offline "android.permission.$permission" user-set user-fixed
  adb shell pm grant com.convoy.offline "android.permission.$permission"
done
tap_control text Map 'Map destination'
adb shell cmd location set-location-enabled true
adb emu geo fix -2.7541 52.7078 >/dev/null
keep_gps_fresh=true
wait_control gps '' 'map-only GPS fix' >/dev/null
wait_service stopped
adb shell input keyevent 4
wait_control text 'convoy' 'Back Map to Home' >/dev/null
tap_control text Routes 'Routes destination'
wait_control text 'Import GPX' 'GPX import button' >/dev/null
tap_control text 'Import GPX' 'system GPX picker'
sleep 2
adb shell dumpsys activity activities > "$private_dir/picker.txt"
grep -Eiq 'documentsui|DocumentsActivity' "$private_dir/picker.txt" || { echo 'GPX document picker did not open.' >&2; exit 1; }
# DocumentsUI may consume Back to leave its initial folder before closing.
for attempt in 1 2 3 4; do
  adb shell input keyevent 4
  sleep 1
  if dump_ui && locate_ui text 'Import GPX' >/dev/null; then break; fi
done
wait_control text 'Import GPX' 'Routes restored after picker' >/dev/null
adb shell input keyevent 4
wait_control text 'convoy' 'Back Routes to Home' >/dev/null
tap_control text Crew 'Crew destination'
wait_service stopped
adb shell input keyevent 4
wait_control text 'convoy' 'Back Crew to Home' >/dev/null
adb shell input keyevent 4
sleep 1
adb shell am start -W -n com.convoy.offline/.MainActivity >/dev/null
wait_control text 'convoy' 'Home restored after normal Back' >/dev/null
printf 'PASS Home, map-only GPS, document picker and main-screen Back.\n' > build/smoke/home-navigation.txt

# Consumer joining offers QR, links and authenticated discovery; technical fields stay in Advanced.
tap_control text 'Join Convoy' 'consumer Join Convoy action' true
wait_control title 'Join Convoy' 'consumer join menu' >/dev/null
wait_control text 'Scan QR' 'QR scanner action' >/dev/null
wait_control text 'Open Invite' 'shared invite action' >/dev/null
wait_control text 'Nearby Convoys' 'LAN discovery action' >/dev/null
assert_consumer_connection_ui join-menu > build/smoke/consumer-join.txt
tap_control text 'Nearby Convoys' 'nearby session browser'
wait_control title 'Nearby Convoys' 'nearby discovery screen' >/dev/null
assert_consumer_connection_ui nearby >> build/smoke/consumer-join.txt
wait_service stopped
adb shell input keyevent 4
wait_control text 'convoy' 'Home after nearby discovery' >/dev/null
tap_control text 'Join Convoy' 'join menu for QR scanner'
setup_open=true
tap_control text 'Scan QR' 'open camera scanner'
sleep 2
if dump_ui && locate_ui text 'While using the app' >/dev/null; then
  tap_control text 'While using the app' 'camera permission only when scanning'
fi
wait_control prefix 'Scan a Convoy invite' 'ZXing camera scanner prompt' false 30 >/dev/null
adb shell dumpsys activity activities > "$private_dir/scanner.txt"
grep -q 'com.journeyapps.barcodescanner.CaptureActivity' "$private_dir/scanner.txt" || {
  echo 'QR camera scanner activity did not open.' >&2; exit 1;
}
adb shell input keyevent 4
wait_control text 'convoy' 'Home after scanner Back' >/dev/null
setup_open=false
tap_control text 'Join Convoy' 'join menu for Advanced compatibility'
setup_open=true
tap_control text Advanced 'Advanced manual compatibility controls'
wait_control title 'Advanced connection' 'manual controls confined to Advanced' >/dev/null
sanitize_setup_ui
adb shell input keyevent 4
wait_control text 'convoy' 'Home after Advanced Back' >/dev/null
setup_open=false
printf 'PASS QR camera entry, nearby discovery UI and isolated Advanced connection. No real-phone scan or LAN interoperability is claimed.\n' >> build/smoke/consumer-join.txt

# GPX works on a real downloaded regional basemap without Shrewsbury search/routing coverage.
adb shell mkdir -p /sdcard/Download
adb push tests/fixtures/belfast-regional-route.gpx /sdcard/Download/ConvoySmokeBelfast.gpx >/dev/null
tap_control text Routes 'Routes for regional GPX'
tap_control text 'Import GPX' 'document provider for Belfast GPX'
tap_control text 'ConvoySmokeBelfast.gpx' 'Belfast GPX document' true
wait_control text 'Convoy Belfast GPX' 'named multi-segment Belfast route imported' >/dev/null
tap_control text 'Follow route' 'follow exact regional GPX'
smoke_gps_longitude=-5.9322
smoke_gps_latitude=54.5993
adb emu geo fix "$smoke_gps_longitude" "$smoke_gps_latitude" >/dev/null
wait_control gps '' 'standalone GPS over downloaded Northern Ireland' >/dev/null
wait_control description 'Offline map source: Northern Ireland' 'actual regional MapLibre source selected' false 30 >/dev/null
wait_control gpx_progress '' 'offline regional GPX progress and remaining distance' false 30 >/dev/null
wait_service stopped
assert_running regional-gpx
dump_ui
cp "$private_dir/ui.xml" build/smoke/belfast-regional-gpx.xml
adb exec-out screencap -p > build/smoke/belfast-regional-gpx.png
tap_control description 'Open GPX route details' 'regional GPX detail and rejoin controls'
tap_control text 'Rejoin route' 'honest unavailable Belfast road-routing coverage' true
wait_control prefix 'GPX track is' 'distance/direction to GPX without fabricating a road route' >/dev/null
dump_ui
python3 - "$private_dir/ui.xml" <<'PYREGIONAL'
import re
import sys
import xml.etree.ElementTree as ET
texts = [node.get("text", "") for node in ET.parse(sys.argv[1]).getroot().iter("node")]
if not any("Offline routing data is not installed for this area" in text for text in texts):
    sys.exit("Regional GPX did not explain the absence of driving-routing data.")
if any(re.fullmatch(r"\d+ min|\d+ h \d+ min", text) for text in texts):
    sys.exit("An unsupported GPX-only ETA appeared outside routing coverage.")
PYREGIONAL
tap_control positive OK 'close regional routing-coverage explanation'
wait_service stopped
adb shell input keyevent 4
wait_control text 'convoy' 'Home after standalone regional GPX' >/dev/null
printf 'PASS real Northern Ireland MapLibre source, two-segment GPX with waypoint, standalone GPS/progress and honest unavailable road routing, all with external networking blocked.\n' > build/smoke/regional-gpx.txt

# Import an actual original through the Android document provider, then follow it.
adb shell mkdir -p /sdcard/Download
adb push tests/fixtures/smoke-route.gpx /sdcard/Download/ConvoySmoke.gpx >/dev/null
tap_control text Routes 'Routes for real GPX import'
tap_control text 'Import GPX' 'GPX picker for fixture'
tap_control text 'ConvoySmoke.gpx' 'local GPX document' true
wait_control text 'Convoy smoke GPX' 'imported named GPX detail' >/dev/null
smoke_gps_longitude=-2.7541
smoke_gps_latitude=52.7078
adb emu geo fix "$smoke_gps_longitude" "$smoke_gps_latitude" >/dev/null
tap_control text 'Follow route' 'offline GPX following'
wait_control prefix 'Following GPX track' 'GPX status on Map' >/dev/null
wait_service stopped
adb exec-out screencap -p > build/smoke/gpx-following.png
printf 'PASS real Android-provider GPX import and map following without convoy.\n' > build/smoke/gpx-import.txt
adb shell input keyevent 4
wait_control text 'convoy' 'Home before checking saved route library' >/dev/null
tap_control text Routes 'multiple offline GPX route library'
wait_control text 'Convoy smoke GPX' 'Shrewsbury route remains saved' true >/dev/null
wait_control text 'Convoy Belfast GPX' 'regional route remains independently saved' true >/dev/null
adb shell input keyevent 4
wait_control text 'convoy' 'Home after route-library verification' >/dev/null
tap_control text Map 'restore followed Shrewsbury GPX'
wait_control prefix 'Following GPX track' 'Shrewsbury GPX remains active after library browsing' >/dev/null
# Import real bundled tile content as a separate custom pack through the same picker.
python3 - "$apk" "$private_dir/ConvoySmokeMap.pmtiles" <<'PYMAP'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    with archive.open("assets/shrewsbury.pmtiles") as source, open(sys.argv[2], "wb") as destination:
        import shutil
        shutil.copyfileobj(source, destination)
PYMAP
adb push "$private_dir/ConvoySmokeMap.pmtiles" /sdcard/Download/ConvoySmokeMap.pmtiles >/dev/null
adb shell input keyevent 4
wait_control text 'convoy' 'Home before map import' >/dev/null
tap_control text 'Offline Maps' 'Offline Maps dashboard destination' true
tap_control text 'Advanced · custom maps' 'advanced custom-map controls' true
tap_control text 'Import custom UK PMTiles' 'custom map import' true
tap_control text 'ConvoySmokeMap.pmtiles' 'real PMTiles document' true
wait_control text 'ConvoySmokeMap' 'imported map pack persisted' true >/dev/null
adb shell input keyevent 4
wait_control text 'convoy' 'Back Offline Maps to Home' >/dev/null
tap_control text Map 'Map with imported pack'
wait_control prefix 'Following GPX track' 'GPX retained after map import' >/dev/null
wait_service stopped
assert_running imported-map
printf 'PASS actual PMTiles document import; GPX and bundled road data preserved.\n' > build/smoke/map-import.txt



# Start through the activity: a saved profile replaces all technical setup fields.
adb shell cmd location set-location-enabled true
adb shell input keyevent 4
wait_control text 'convoy' 'Home before starting secure convoy' >/dev/null
# The rootable emulator is deliberately disconnected from Wi-Fi for these offline checks.
# Select the consumer LAN/router preference; hotspot interoperability needs physical devices.
tap_control text Guide 'network preference in Guide'
tap_control text 'Convoy network preference' 'consumer transport preference' true
tap_control text 'Existing Wi-Fi / travel router / vehicle mesh' 'LAN mode for offline emulator host'
adb shell input keyevent 4
wait_control text 'convoy' 'Home after saved LAN preference' >/dev/null
setup_open=true
tap_control text 'Start Convoy' 'one-tap Start Convoy' true
sleep 1
if dump_ui && locate_ui title 'Your driver profile' >/dev/null; then
  enter_smoke_name
  dismiss_keyboard 'Your driver profile'
  tap_control positive Save 'save driver profile once'
fi
wait_service started
keep_gps_fresh=true
wait_control title 'Convoy ready' 'host invitation screen' >/dev/null
wait_control description 'Scan to join this convoy' 'generated invitation QR view' true 30 >/dev/null
wait_control text 'Share Invite' 'Android share action' true >/dev/null
assert_consumer_connection_ui host-invite > build/smoke/consumer-host.txt
tap_control text 'Share Invite' 'open Android invitation share sheet' true
sleep 2
adb shell dumpsys activity activities > "$private_dir/share.txt"
grep -Eiq 'ChooserActivity|ResolverActivity' "$private_dir/share.txt" || {
  echo 'Android invitation share sheet did not open.' >&2; exit 1;
}
adb shell input keyevent 4
wait_control title 'Convoy ready' 'return from invitation sharing' >/dev/null
tap_control text 'Stop accepting new drivers' 'pause new-driver admission' true
wait_control text 'Accept new drivers' 'paused admission keeps existing session' true >/dev/null
wait_service started
tap_control text 'Accept new drivers' 'reopen admission' true
wait_control text 'Stop accepting new drivers' 'admission reopened' true >/dev/null
tap_control text Close 'close sensitive host QR'
wait_control text 'convoy' 'Home with active convoy' >/dev/null
wait_control text 'Show Invite' 'reopen host invite from Home' true >/dev/null
setup_open=false
assert_running trip-started
printf 'PASS secure convoy started through saved-profile UI; QR/share/admission controls and foreground service are active.\n' > build/smoke/service-start.txt
tap_control text Crew 'existing Crew destination'
wait_control text 'Invite drivers' 'host invite available from Crew' true >/dev/null
adb shell input keyevent 4
wait_control text 'convoy' 'Back from active Crew to Home' >/dev/null
wait_service started

tap_control text Map 'Map tab'
wait_control prefix 'Following GPX track' 'GPX retained while secure convoy starts' >/dev/null
adb emu geo fix -2.7541 52.7078 >/dev/null
wait_control gps '' 'fresh GPS status after Shrewsbury location injection' >/dev/null
assert_running live-gps
adb exec-out screencap -p > build/smoke/live-gps.png
record_logcat build/smoke/live-gps-logcat.txt
dump_ui
cp "$private_dir/ui.xml" build/smoke/live-gps-ui.xml

# Exercise the actual bundled place index and offline road graph with data off.
tap_control text 'Search offline places' 'offline place search button'
tap_control edit '' 'offline search field'
adb shell input text Shrewsbury%sCastle
dismiss_keyboard 'Search offline places'
tap_control positive Search 'offline Search button'
tap_control prefix 'Shrewsbury Castle' 'Shrewsbury Castle offline search result'
tap_control text 'Navigate here' 'Navigate here button' true
wait_control eta '' 'numeric ETA from a calculated offline Castle route' >/dev/null
assert_running offline-route
adb exec-out screencap -p > build/smoke/offline-route.png
record_logcat build/smoke/offline-route-logcat.txt
dump_ui
cp "$private_dir/ui.xml" build/smoke/offline-route-ui.xml
printf 'PASS offline Castle search and road routing produced a numeric ETA with Wi-Fi/mobile data disabled.\n' > build/smoke/offline-route.txt

# Stop only this temporary navigation target; leave any saved trip plan intact.
tap_control text Stop 'Stop navigation button'
tap_control positive 'Stop route' 'Stop route confirmation button'
adb shell input keyevent 4
wait_control text 'convoy' 'Home before ending convoy' >/dev/null
tap_control text 'End Convoy' 'End Convoy action' true
tap_control positive 'End Convoy' 'End Convoy confirmation'
keep_gps_fresh=false
wait_service stopped
wait_control text 'Start Convoy' 'inactive Home offers starting a new session' true >/dev/null
tap_control text Map 'offline Map after convoy ends'
assert_running trip-stopped
verify_offline_network final
printf 'PASS ending the trip stopped the service while the offline app stayed running.\n' > build/smoke/service-stop.txt
api_level="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')"
printf 'PASS %s on API %s: real hosted maps download/background/cancel/retry, QR/share consumer onboarding, local GPS/GPX/Back and convoy/routing regressions with external networking blocked.\n' "$apk" "$api_level" | tee build/smoke/result.txt

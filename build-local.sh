#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Set ANDROID_HOME or ANDROID_SDK_ROOT to an Android SDK with API 35 and build-tools 35.0.0}"
if [[ -f gradle/wrapper/gradle-wrapper.jar && -f gradlew ]]; then
  gradle_command=(./gradlew)
else
  command -v gradle >/dev/null || { echo 'Gradle wrapper missing; install Gradle 8.10.2 and Java 17.' >&2; exit 1; }
  gradle_command=(gradle)
fi
export ANDROID_HOME="$sdk"
skip_region=false
skip_tests=false
for option in "$@"; do
  case "$option" in
    --skip-region) skip_region=true ;;
    --skip-tests) skip_tests=true ;;
    *) echo "Unknown option: $option" >&2; exit 1 ;;
  esac
done
if [[ "$skip_region" == false ]]; then
  bash tools/fetch_shrewsbury_region.sh
fi
if [[ "$skip_tests" == false ]]; then
  bash tools/run_invite_tests.sh
  bash tools/run_discovery_tests.sh
  bash tools/run_map_download_tests.sh
  bash tools/run_protocol_tests.sh
  bash tools/run_local_tiles_tests.sh
  bash tools/run_gpx_tests.sh
  bash tools/run_map_pack_tests.sh app/src/main/assets/shrewsbury.pmtiles app/src/main/assets/overture-shrewsbury.pmtiles
  bash tools/run_location_tests.sh
  bash tools/run_navigation_tests.sh
  bash tools/run_trip_plan_tests.sh
  bash tools/run_routing_tests.sh app/src/main/assets/route.graph
  python3 -m unittest discover -s tests -p 'test_*.py'
  python3 tools/run_pmtiles_validation_tests.py 2>&1 | tee build/pmtiles-validation-tests.txt
fi
mkdir -p build/release
"${gradle_command[@]}" --no-daemon --stacktrace :app:assembleRelease :app:assembleDebug :app:lintRelease 2>&1 | tee build/android-build.txt
bash tools/run_qr_tests.sh
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
[[ -n "$version" ]] || { echo 'Missing Gradle versionName.' >&2; exit 1; }
unsigned="build/release/Convoy-${version}-aligned-unsigned.apk"
"$sdk/build-tools/35.0.0/zipalign" -f -P 16 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk "$unsigned"
"$sdk/build-tools/35.0.0/zipalign" -c -P 16 4 "$unsigned"
python3 tools/verify_apk.py "$unsigned" | tee build/release/packaging-checks.txt
python3 tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk | tee build/release/debug-packaging-checks.txt
"$sdk/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk > build/release/debug-signing-verification.txt
"$sdk/build-tools/35.0.0/aapt2" dump badging "$unsigned" > build/release/apk-badging.txt
version_code="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle)"
python3 - "$version_code" "$version" <<'PYVERSION'
from pathlib import Path
import sys
expected = f"package: name='com.convoy.offline' versionCode='{sys.argv[1]}' versionName='{sys.argv[2]}'"
assert Path("build/release/apk-badging.txt").read_text().startswith(expected), "APK package/version differs from the update-compatible Gradle configuration"
print("PASS APK package and version metadata")
PYVERSION
sha256sum "$unsigned" | tee build/release/unsigned.sha256
if [[ -n "${CONVOY_KEYSTORE_PATH:-}${CONVOY_KEYSTORE_BASE64:-}" ]]; then
  bash tools/sign_release.sh "$unsigned" "build/release/Convoy-${version}.apk" | tee build/release/signing-verification.txt
else
  echo 'Release assembled and verified. Signing requires the existing private Convoy key; unsigned APKs cannot be installed.'
fi

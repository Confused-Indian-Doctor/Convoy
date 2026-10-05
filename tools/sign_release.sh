#!/usr/bin/env bash
# Only the existing Convoy signing identity is accepted. No key is generated here.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${CONVOY_KEYSTORE_PASSWORD:?Set the existing private keystore password}"
: "${CONVOY_KEY_ALIAS:?Set the existing private signing alias}"
: "${CONVOY_KEY_PASSWORD:?Set the existing private key password}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
input="${1:?Pass an aligned release APK}"
output="${2:?Pass the signed release output path}"
key_path="${CONVOY_KEYSTORE_PATH:-}"
private_dir=''
cleanup() { if [[ -n "$private_dir" ]]; then rm -rf "$private_dir"; fi; }
trap cleanup EXIT
if [[ -z "$key_path" ]]; then
  : "${CONVOY_KEYSTORE_BASE64:?Set CONVOY_KEYSTORE_PATH or CONVOY_KEYSTORE_BASE64 to the existing private key}"
  umask 077
  private_dir="$(mktemp -d "${TMPDIR:-/tmp}/convoy-sign.XXXXXX")"
  key_path="$private_dir/existing-key.jks"
  printf '%s' "$CONVOY_KEYSTORE_BASE64" | base64 --decode > "$key_path"
fi
[[ -f "$key_path" ]] || { echo 'The private keystore file does not exist.' >&2; exit 1; }
"$sdk/build-tools/35.0.0/apksigner" sign \
  --ks "$key_path" --ks-key-alias "$CONVOY_KEY_ALIAS" \
  --ks-pass env:CONVOY_KEYSTORE_PASSWORD --key-pass env:CONVOY_KEY_PASSWORD \
  --out "$output" "$input"
"$sdk/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$output"
"$sdk/build-tools/35.0.0/zipalign" -c -P 16 4 "$output"
sha256sum "$output"

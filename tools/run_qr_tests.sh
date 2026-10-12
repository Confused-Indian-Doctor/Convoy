#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Android assemble resolves this exact scanner dependency; use those same bytes for the host check.
qr_core_jar="${1:-}"
if [[ -z "$qr_core_jar" ]]; then
  qr_cache_root="${GRADLE_USER_HOME:-$HOME/.gradle}"
  qr_core_jar="$(rg --files "$qr_cache_root/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3" 2>/dev/null | rg '/core-3\.5\.3\.jar$' | head -1 || true)"
fi
[[ -f "$qr_core_jar" ]] || { echo 'Assemble the Android app first, or pass the resolved ZXing core 3.5.3 jar.' >&2; exit 1; }
mkdir -p build/qr-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -cp "$qr_core_jar" -d build/qr-test-classes \
  app/src/main/java/com/convoy/offline/{Wire,ConvoyInvite,ConvoySession}.java tests/ConvoyQrTest.java
java -cp "build/qr-test-classes:$qr_core_jar" com.convoy.offline.ConvoyQrTest | tee build/qr-tests.txt

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Android assemble resolves this exact scanner dependency; use those same bytes for the host check.
qr_core_jar="${1:-}"
if [[ -z "$qr_core_jar" ]]; then
  qr_cache_root="${GRADLE_USER_HOME:-$HOME/.gradle}"
  qr_core_jar="$(python3 - "$qr_cache_root" <<'PYJAR'
from pathlib import Path
import sys
root = Path(sys.argv[1]) / 'caches/modules-2/files-2.1/com.google.zxing/core/3.5.4'
print(next((str(p) for p in root.glob('*/core-3.5.4.jar') if p.is_file()), ''))
PYJAR
)"
fi
[[ -f "$qr_core_jar" ]] || { echo 'Assemble the Android app first, or pass the resolved ZXing core 3.5.4 jar.' >&2; exit 1; }
mkdir -p build/qr-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -cp "$qr_core_jar" -d build/qr-test-classes \
  app/src/main/java/com/convoy/offline/{Wire,ConvoyInvite,ConvoySession,ConvoyQr}.java tests/ConvoyQrTest.java
java -cp "build/qr-test-classes:$qr_core_jar" com.convoy.offline.ConvoyQrTest | tee build/qr-tests.txt

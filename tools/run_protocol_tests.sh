#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -d build/test-classes \
  app/src/main/java/com/convoy/offline/Wire.java tests/WireTest.java
java -cp build/test-classes com.convoy.offline.WireTest 2>&1 | tee build/protocol-tests.txt

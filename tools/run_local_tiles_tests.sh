#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/local-tiles-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -d build/local-tiles-test-classes \
  app/src/main/java/com/convoy/offline/LocalTiles.java tests/LocalTilesTest.java
java -cp build/local-tiles-test-classes com.convoy.offline.LocalTilesTest | tee build/local-tiles-tests.txt

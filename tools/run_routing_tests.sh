#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/routing-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -d build/routing-test-classes \
  app/src/main/java/com/convoy/offline/OfflineRouter.java tests/OfflineRouterTest.java
java -cp build/routing-test-classes com.convoy.offline.OfflineRouterTest "$@" 2>&1 | tee build/routing-tests.txt

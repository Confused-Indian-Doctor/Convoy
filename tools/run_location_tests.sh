#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/location-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d build/location-test-classes \
  app/src/main/java/com/convoy/offline/LocationPolicy.java tests/LocationPolicyTest.java
java -cp build/location-test-classes com.convoy.offline.LocationPolicyTest | tee build/location-tests.txt

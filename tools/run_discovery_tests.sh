#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/discovery-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d build/discovery-test-classes \
  app/src/main/java/com/convoy/offline/LanAdvertisement.java \
  app/src/main/java/com/convoy/offline/HotspotNetwork.java \
  app/src/main/java/com/convoy/offline/DiscoveryBackoff.java tests/LanDiscoveryTest.java
java -cp build/discovery-test-classes com.convoy.offline.LanDiscoveryTest | tee build/discovery-tests.txt

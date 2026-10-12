#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/map-pack-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d build/map-pack-test-classes app/src/main/java/com/convoy/offline/{MapPack,MapPackCatalog,MapPackStore,MapPackSource,MapPackManifest,MapDownloadEngine,StrictJson,OfflineCoverage,PmtilesArchive}.java tests/MapPackTest.java
java -cp build/map-pack-test-classes com.convoy.offline.MapPackTest "$@" | tee build/map-pack-tests.txt

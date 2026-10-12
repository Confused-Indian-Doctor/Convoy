#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/map-download-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d build/map-download-test-classes app/src/main/java/com/convoy/offline/{MapPack,MapPackCatalog,MapPackStore,MapPackSource,MapPackManifest,MapDownloadEngine,StrictJson,OfflineCoverage,PmtilesArchive}.java tests/MapDownloadTest.java
java -cp build/map-download-test-classes com.convoy.offline.MapDownloadTest app/src/main/assets/uk-map-manifest.json | tee build/map-download-tests.txt

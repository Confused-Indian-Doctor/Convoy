#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/gpx-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d build/gpx-test-classes app/src/main/java/com/convoy/offline/{GpxRoute,GpxParser,RouteLibrary}.java tests/GpxTest.java
java -cp build/gpx-test-classes com.convoy.offline.GpxTest | tee build/gpx-tests.txt

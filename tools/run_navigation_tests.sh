#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/navigation-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then
 compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -d build/navigation-test-classes \
 app/src/main/java/com/convoy/offline/NavigationState.java tests/NavigationStateTest.java
java -cp build/navigation-test-classes com.convoy.offline.NavigationStateTest | tee build/navigation-tests.txt

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/trip-plan-test-classes
compiler=(javac)
if ! command -v javac >/dev/null; then
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -d build/trip-plan-test-classes \
  tests/stubs/android/content/Context.java tests/stubs/android/content/SharedPreferences.java \
  app/src/main/java/com/convoy/offline/TripPlan.java tests/TripPlanTest.java
java -cp build/trip-plan-test-classes com.convoy.offline.TripPlanTest | tee build/trip-plan-tests.txt

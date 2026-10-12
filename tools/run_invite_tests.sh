#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-invite-classes
compiler=(javac)
if ! command -v javac >/dev/null; then
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -encoding UTF-8 -d build/test-invite-classes \
  app/src/main/java/com/convoy/offline/Wire.java \
  app/src/main/java/com/convoy/offline/ConvoyInvite.java app/src/main/java/com/convoy/offline/AdmissionPrivacy.java \
  app/src/main/java/com/convoy/offline/ConvoySession.java \
  app/src/main/java/com/convoy/offline/InviteHandshake.java \
  tests/WireTest.java tests/ConvoyInviteTest.java tests/AdmissionPrivacyTest.java
java -cp build/test-invite-classes com.convoy.offline.ConvoyInviteTest 2>&1 | tee build/invite-tests.txt

java -cp build/test-invite-classes com.convoy.offline.AdmissionPrivacyTest 2>&1 | tee build/admission-privacy-tests.txt

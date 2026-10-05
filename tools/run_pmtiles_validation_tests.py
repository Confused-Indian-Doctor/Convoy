#!/usr/bin/env python3
"""Compile and test the production PMTiles validator without needing an Android SDK."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    source = (ROOT / "app/src/main/java/com/convoy/offline/MainActivity.java").read_text()
    start = source.index(" private void validatePmtiles(")
    end = source.index(" private void searchPlaces()", start)
    # Use the exact Android-free production method instead of a second validation implementation.
    method = source[start:end].replace(
        "private void validatePmtiles", "public static void validatePmtiles", 1
    )
    with tempfile.TemporaryDirectory(prefix="convoy-pmtiles-compiler-") as temporary:
        directory = Path(temporary)
        harness = directory / "PmtilesHeaderHarness.java"
        harness.write_text("import java.io.*;\npublic final class PmtilesHeaderHarness {\n" + method + "}\n")
        classes = directory / "classes"
        classes.mkdir()
        subprocess.run([
            "java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(classes),
            str(harness), str(ROOT / "tests/PmtilesValidationTest.java"),
        ], check=True)
        subprocess.run(["java", "-cp", str(classes), "PmtilesValidationTest"], check=True)


if __name__ == "__main__":
    main()

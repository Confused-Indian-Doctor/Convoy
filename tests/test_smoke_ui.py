"""Check the actual emulator UI matcher against Android dialog transitions."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SOURCE = Path(__file__).parents[1] / "tools/emulator_smoke.sh"
MATCHER = SOURCE.read_text(encoding="utf-8").split("<<'PYUI'\n", 1)[1].split("\nPYUI", 1)[0]


class SmokeUiTest(unittest.TestCase):
    def match(self, mode, value, *nodes):
        hierarchy = ET.Element("hierarchy")
        for attrs in nodes:
            ET.SubElement(hierarchy, "node", {"enabled": "true", "bounds": "[0,0][100,100]", **attrs})
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "ui.xml"
            ET.ElementTree(hierarchy).write(fixture, encoding="utf-8")
            result = subprocess.run([sys.executable, "-c", MATCHER, str(fixture), mode, value], capture_output=True)
            self.assertIn(result.returncode, (0, 1), result.stderr.decode())
            return result.returncode == 0

    def map_title(self, package="com.convoy.offline"):
        return {"text": "Map", "class": "android.widget.TextView", "package": package}

    def test_screen_waits_for_dialog_to_close(self):
        self.assertFalse(self.match("screen", "Map", self.map_title(), {"resource-id": "android:id/alertTitle", "text": "Convoy"}))
        self.assertTrue(self.match("screen", "Map", self.map_title()))

    def test_screen_requires_own_activity_heading(self):
        self.assertFalse(self.match("screen", "Map", self.map_title("other.app")))
        self.assertFalse(self.match("screen", "Map", {"text": "Map", "class": "android.widget.Button", "package": "com.convoy.offline"}))

    def test_negative_button_accepts_android_all_caps(self):
        self.assertTrue(self.match("negative", "Close", {"text": "CLOSE", "resource-id": "android:id/button2"}))
        self.assertTrue(self.match("negative", "Close", {"text": "Close", "resource-id": "android:id/button2"}))

    def test_negative_button_does_not_tap_another_action(self):
        self.assertFalse(self.match("negative", "Close", {"text": "Close", "resource-id": "android:id/button1"}))
        self.assertFalse(self.match("negative", "Close", {"text": "Close", "resource-id": ""}))

    def test_disabled_close_is_not_actionable(self):
        self.assertFalse(self.match("negative", "Close", {"text": "CLOSE", "resource-id": "android:id/button2", "enabled": "false"}))


if __name__ == "__main__":
    unittest.main()

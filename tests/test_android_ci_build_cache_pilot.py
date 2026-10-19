"""Guard measured opt-in Gradle task-output-cache pilot on the Android gate."""
from pathlib import Path
import unittest

CHECK = Path(__file__).resolve().parents[1] / ".github/workflows/check.yml"


class AndroidBuildCachePilotTests(unittest.TestCase):
    def test_cache_is_only_requested_for_existing_unit_test_task(self):
        s = CHECK.read_text(encoding="utf-8")
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            s,
        )
        self.assertIn("name: Android · test and build", s)
        self.assertNotIn(":app:assembleDevDebug", s)
        self.assertIn("  pull_request:", s)
        self.assertIn("    branches: [main]", s)

    def test_ci_still_keeps_serial_queue(self):
        s = CHECK.read_text(encoding="utf-8")
        self.assertIn("      queue: max", s)
        self.assertIn("      cancel-in-progress: false", s)


if __name__ == "__main__":
    unittest.main()

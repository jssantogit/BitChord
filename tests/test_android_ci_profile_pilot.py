"""Non-required Android task-profile pilot must not weaken protected CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PILOT = ROOT / ".github/workflows/android-ci-critical-path-profile.yml"
REQUIRED = ROOT / ".github/workflows/check.yml"


class AndroidCriticalPathProfilePilotTests(unittest.TestCase):
    def test_pilot_is_explicitly_scoped_and_read_only(self):
        workflow = PILOT.read_text(encoding="utf-8")
        self.assertIn("name: Android CI Critical Path Profile Pilot", workflow)
        self.assertIn("  pull_request:", workflow)
        self.assertIn("      - '.github/workflows/android-ci-critical-path-profile.yml'", workflow)
        self.assertNotIn("  push:", workflow)
        self.assertNotIn("  schedule:", workflow)
        self.assertIn("  contents: read", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertNotIn("pull-requests: write", workflow)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", workflow)
        self.assertIn("startsWith(github.head_ref, 'infra/android-ci-')", workflow)

    def test_pilot_profiles_the_full_existing_android_test_target_without_build_upload(self):
        workflow = PILOT.read_text(encoding="utf-8")
        self.assertIn("cache-read-only: true", workflow)
        self.assertIn(
            "run: ./gradlew --build-cache --profile -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            workflow,
        )
        self.assertIn("path: build/reports/profile/*.html", workflow)
        self.assertIn("retention-days: 3", workflow)
        self.assertNotIn(":app:assembleDevDebug", workflow)
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("environment: integration-apply", workflow)

    def test_required_android_ci_is_not_replaced_or_weakened(self):
        required = REQUIRED.read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", required)
        self.assertIn("  pull_request:", required)
        self.assertIn("    branches: [main]", required)
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            required,
        )
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", required)
        self.assertNotIn("--configuration-cache", required)
        self.assertNotIn("--profile", required)


if __name__ == "__main__":
    unittest.main()

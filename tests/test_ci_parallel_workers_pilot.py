"""Optional Gradle parallelism must never weaken the protected Android gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PILOT = ROOT / ".github/workflows/android-ci-parallel-workers-pilot.yml"
REQUIRED = ROOT / ".github/workflows/check.yml"


class AndroidParallelWorkerPilotContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = PILOT.read_text(encoding="utf-8")

    def test_optional_same_repo_scope_read_only_and_no_main_or_dispatch(self):
        s = self.workflow
        self.assertIn("name: Android CI Parallel Workers Pilot", s)
        self.assertIn("  pull_request:", s)
        self.assertIn("      - '.github/workflows/android-ci-parallel-workers-pilot.yml'", s)
        self.assertNotIn("\n  push:", s)
        self.assertNotIn("workflow_dispatch:", s)
        self.assertNotIn("  schedule:", s)
        self.assertIn("  contents: read", s)
        self.assertNotIn("contents: write", s)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", s)
        self.assertIn("github.head_ref == 'infra/android-ci-parallel-workers-pilot-v0'", s)
        self.assertIn("cache-read-only: true", s)
        self.assertNotIn("secrets.", s)
        self.assertNotIn("upload-artifact", s)
        self.assertNotIn("integration-apply", s)

    def test_controlled_matrix_and_identical_full_test_target(self):
        s = self.workflow
        self.assertIn("fail-fast: false", s)
        self.assertIn("mode: [sequential, parallel_two]", s)
        self.assertIn("sequential)", s)
        self.assertIn("parallel_two)", s)
        self.assertIn("--parallel --max-workers=2", s)
        self.assertIn("--max-workers=2", s)
        self.assertIn("--build-cache", s)
        self.assertIn(":app:testDevDebugUnitTest", s)
        self.assertIn("GRADLE_PARALLEL_PILOT_RESULT", s)
        self.assertIn("GITHUB_STEP_SUMMARY", s)
        self.assertNotIn("testDevDebugUnitTest --tests", s)
        self.assertNotIn("--configuration-cache", s)
        self.assertNotIn("assembleDevDebug", s)

    def test_required_full_android_gate_unchanged(self):
        s = REQUIRED.read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", s)
        self.assertIn("    branches: [main]", s)
        self.assertIn("  pull_request:", s)
        self.assertIn("run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest", s)
        self.assertNotIn("--parallel", s)
        self.assertNotIn("--max-workers=2", s)


if __name__ == "__main__":
    unittest.main()

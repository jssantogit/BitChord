"""Incremental Kotlin/KSP A/B never alters required CI or application behavior."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PILOT = ROOT / ".github/workflows/android-ci-kotlin-ksp-incremental-pilot.yml"
REQUIRED = ROOT / ".github/workflows/check.yml"


class KotlinKspIncrementalPilotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = PILOT.read_text(encoding="utf-8")

    def test_only_internal_infrastructure_prs_may_trigger_optional_matrix(self):
        s = self.source
        self.assertIn("name: Android Kotlin KSP Incremental Pilot", s)
        self.assertIn("  pull_request:", s)
        self.assertIn("      - '.github/workflows/android-ci-kotlin-ksp-incremental-pilot.yml'", s)
        self.assertNotIn("\n  push:", s)
        self.assertNotIn("workflow_dispatch:", s)
        self.assertNotIn("  schedule:", s)
        self.assertIn("  contents: read", s)
        self.assertNotIn("contents: write", s)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", s)
        self.assertIn("github.head_ref == 'infra/android-ci-kotlin-ksp-incremental-pilot-v0'", s)
        self.assertIn("cache-read-only: true", s)
        self.assertNotIn("secrets.", s)
        self.assertNotIn("upload-artifact", s)
        self.assertNotIn("integration-apply", s)

    def test_a_b_use_identical_full_suite_and_only_one_comment_source_change(self):
        s = self.source
        self.assertIn("fail-fast: false", s)
        self.assertIn("mode: [default_incremental, incremental_disabled]", s)
        self.assertIn("-Pkotlin.incremental=false", s)
        self.assertIn("-Pksp.incremental=false", s)
        self.assertIn("flags=()", s)
        self.assertIn("app/src/main/java/com/music/bitchord/data/scrobbling/PrimaryArtist.kt", s)
        self.assertIn("git diff --quiet HEAD -- \"$source\"", s)
        self.assertIn('printf', s)
        self.assertIn('trap \'git restore -- "$source"\' EXIT', s)
        self.assertIn("run_full", s)
        self.assertIn(":app:testDevDebugUnitTest", s)
        self.assertIn("GRADLE_KOTLIN_KSP_INCREMENTAL_PILOT", s)
        self.assertIn("GITHUB_STEP_SUMMARY", s)
        self.assertNotIn(":app:assembleDevDebug", s)
        self.assertNotIn("--configuration-cache", s)
        self.assertNotIn("--rerun-tasks", s)
        self.assertNotIn("gradle.properties", s)

    def test_protected_android_ci_keeps_exact_required_test_gate(self):
        original = REQUIRED.read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", original)
        self.assertIn("    branches: [main]", original)
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            original,
        )
        self.assertNotIn("-Pkotlin.incremental=false", original)
        self.assertNotIn("-Pksp.incremental=false", original)


if __name__ == "__main__":
    unittest.main()

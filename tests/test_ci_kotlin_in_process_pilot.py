"""Kotlin daemon-vs-in-process compilation pilot cannot touch protected CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PILOT = ROOT / ".github/workflows/android-ci-kotlin-in-process-pilot.yml"
REQUIRED = ROOT / ".github/workflows/check.yml"


class KotlinInProcessPilotContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.s = PILOT.read_text(encoding="utf-8")

    def test_only_explicit_internal_pilot_pr_with_read_only_cache(self):
        s = self.s
        self.assertIn("name: Android Kotlin In-Process Compiler Pilot", s)
        self.assertIn("  pull_request:", s)
        self.assertIn("      - '.github/workflows/android-ci-kotlin-in-process-pilot.yml'", s)
        self.assertNotIn("\n  push:", s)
        self.assertNotIn("workflow_dispatch:", s)
        self.assertNotIn("  schedule:", s)
        self.assertIn("  contents: read", s)
        self.assertNotIn("contents: write", s)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", s)
        self.assertIn("github.head_ref == 'infra/android-ci-kotlin-in-process-pilot-v0'", s)
        self.assertIn("cache-read-only: true", s)
        self.assertNotIn("secrets.", s)
        self.assertNotIn("upload-artifact", s)
        self.assertNotIn("integration-apply", s)

    def test_same_full_target_with_independent_default_and_in_process_jobs(self):
        s = self.s
        self.assertIn("fail-fast: false", s)
        self.assertIn("mode: [daemon_default, in_process]", s)
        self.assertIn("flags=()", s)
        self.assertIn("-Pkotlin.compiler.execution.strategy=in-process", s)
        self.assertIn("run_full", s)
        self.assertIn(":app:testDevDebugUnitTest", s)
        self.assertIn("app/src/main/java/com/music/bitchord/data/scrobbling/PrimaryArtist.kt", s)
        self.assertIn('trap \'git restore -- "$source"\' EXIT', s)
        self.assertIn("KOTLIN_COMPILER_STRATEGY_RESULT", s)
        self.assertIn("GITHUB_STEP_SUMMARY", s)
        self.assertNotIn("--rerun-tasks", s)
        self.assertNotIn("--configuration-cache", s)
        self.assertNotIn(" -x ", s)
        self.assertNotIn("assembleDevDebug", s)

    def test_required_check_and_project_compiler_version_unchanged(self):
        check = REQUIRED.read_text(encoding="utf-8")
        root = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", check)
        self.assertIn("    branches: [main]", check)
        self.assertIn("run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest", check)
        self.assertNotIn("compiler.execution.strategy", check)
        self.assertIn('id("org.jetbrains.kotlin.android") version "2.3.20"', root)


if __name__ == "__main__":
    unittest.main()

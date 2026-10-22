"""Room KSP scope pilot is opt-in and never suppresses required Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ROOT_BUILD = ROOT / "build.gradle.kts"
PILOT = ROOT / ".github/workflows/android-ci-ksp-unit-test-scope-pilot.yml"
REQUIRED = ROOT / ".github/workflows/check.yml"


class KspTestScopePilotContracts(unittest.TestCase):
    def test_room_default_dependency_kept_and_pilot_strictly_opt_in(self):
        build = ROOT_BUILD.read_text(encoding="utf-8")
        self.assertIn('providers.gradleProperty("bitchord.roomProcessorMainOnlyPilot")', build)
        self.assertIn('"kspDevDebug"', build)
        self.assertIn('dependencies.add("ksp", "androidx.room:room-compiler:2.8.5")', build)
        self.assertNotIn('tasks.named("kspDevDebugUnitTestKotlin")', build)
        self.assertNotIn('enabled = false', build)
        self.assertNotIn('onlyIf { false }', build)
        self.assertNotIn("dependencies.add(\"kspTest", build)

    def test_optional_full_test_matrix_and_no_shared_cache_writes(self):
        s = PILOT.read_text(encoding="utf-8")
        self.assertIn("name: Android Room KSP Test-Scope Pilot", s)
        self.assertIn("  pull_request:", s)
        self.assertIn("      - '.github/workflows/android-ci-ksp-unit-test-scope-pilot.yml'", s)
        self.assertNotIn("\n  push:", s)
        self.assertNotIn("workflow_dispatch:", s)
        self.assertNotIn("  schedule:", s)
        self.assertIn("  contents: read", s)
        self.assertNotIn("contents: write", s)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", s)
        self.assertIn("github.head_ref == 'infra/android-ci-ksp-unit-test-scope-pilot-v0'", s)
        self.assertIn("mode: [global_room, dev_main_only_room]", s)
        self.assertIn("fail-fast: false", s)
        self.assertIn("cache-read-only: true", s)
        self.assertIn("-Pbitchord.roomProcessorMainOnlyPilot=true", s)
        self.assertIn("app/src/main/java/com/music/bitchord/data/catalog/CanonicalCatalogDao.kt", s)
        self.assertIn("run_full", s)
        self.assertIn(":app:testDevDebugUnitTest", s)
        self.assertIn('trap \'git restore -- "$source"\' EXIT', s)
        self.assertNotIn("secrets.", s)
        self.assertNotIn("upload-artifact", s)
        self.assertNotIn("integration-apply", s)
        self.assertNotIn("--exclude-task", s)
        self.assertNotIn(" -x ", s)
        self.assertNotIn("--rerun-tasks", s)

    def test_required_ci_and_release_default_unchanged(self):
        workflow = REQUIRED.read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", workflow)
        self.assertIn("    branches: [main]", workflow)
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            workflow,
        )
        self.assertNotIn("roomProcessorMainOnlyPilot", workflow)


if __name__ == "__main__":
    unittest.main()

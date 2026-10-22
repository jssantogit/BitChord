"""Fail-closed Android Kotlin/KSP invalidation probe contracts."""
from pathlib import Path
import unittest

from tools.android_ci_invalidation_probe import ProbeError, analyze_task_log

ROOT = Path(__file__).resolve().parents[1]
PILOT = ROOT / ".github/workflows/android-ci-module-invalidation-pilot.yml"
REQUIRED = ROOT / ".github/workflows/check.yml"

TASKS = (
    "shared_kotlin", "sharedui_kotlin", "app_ksp", "app_kotlin",
    "app_unit_ksp", "app_unit_kotlin", "app_unit_tests",
)


def sample(*, failed=False, duplicate=False, missing=False):
    names = (
        ":shared:compileDebugKotlinAndroid",
        ":sharedUi:compileDebugKotlinAndroid",
        ":app:kspDevDebugKotlin",
        ":app:compileDevDebugKotlin",
        ":app:kspDevDebugUnitTestKotlin",
        ":app:compileDevDebugUnitTestKotlin",
        ":app:testDevDebugUnitTest",
    )
    tasks = ["> Task " + name + (" UP-TO-DATE" if i % 2 else "")
             for i, name in enumerate(names)]
    if missing:
        tasks.pop(2)
    if duplicate:
        tasks.append(tasks[0])
    return "\n".join([
        *tasks,
        "BUILD FAILED in 1m" if failed else "BUILD SUCCESSFUL in 1m 3s",
        "73 actionable tasks: 9 executed, 64 up-to-date",
    ])


class InvalidationProbeParserTests(unittest.TestCase):
    def test_emits_only_fixed_task_names_and_allowed_statuses(self):
        result = analyze_task_log(sample(), "shared")
        self.assertEqual("shared", result["source_case"])
        self.assertEqual(63, result["gradle_seconds"])
        self.assertEqual(set(TASKS), set(result["tasks"]))
        self.assertEqual("executed", result["tasks"]["shared_kotlin"])
        self.assertEqual("up_to_date", result["tasks"]["sharedui_kotlin"])
        self.assertNotIn("Task :", str(result))
        self.assertNotIn("BUILD SUCCESSFUL", str(result))

    def test_rejects_missing_duplicate_and_failed_tasks(self):
        for body in [sample(failed=True), sample(duplicate=True),
                     sample(missing=True), "nothing logged",
                     sample().replace("BUILD SUCCESSFUL", "BUILD FAILED"),
                     sample() + "\n" + "BUILD SUCCESSFUL in 3s"]:
            with self.subTest(case=len(body)), self.assertRaises(ProbeError):
                analyze_task_log(body, "app")

    def test_rejects_unsafe_source_cases_and_noise(self):
        with self.assertRaises(ProbeError):
            analyze_task_log(sample(), "secrets/" + "x" * 200)
        with self.assertRaises(ProbeError):
            analyze_task_log(sample().replace("UP-TO-DATE", "UNSAFE", 1), "sharedUi")
        for mode in ("app", "shared", "sharedUi", "control"):
            self.assertEqual(mode, analyze_task_log(sample(), mode)["source_case"])

    def test_ksp_task_statuses_are_not_inferred_from_total_counts(self):
        src = sample().replace(
            "> Task :app:kspDevDebugKotlin",
            "> Task :app:kspDevDebugKotlin FROM-CACHE")
        output = analyze_task_log(src, "app")
        self.assertEqual("from_cache", output["tasks"]["app_ksp"])
        self.assertNotEqual("executed", output["tasks"]["app_ksp"])


class InvalidationWorkflowContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = PILOT.read_text(encoding="utf-8")

    def test_strict_optional_branch_and_read_only_cache(self):
        s = self.workflow
        self.assertIn("name: Android Kotlin KSP Module Invalidation Pilot", s)
        self.assertIn("  pull_request:", s)
        self.assertNotIn("\n  push:", s)
        self.assertNotIn("workflow_dispatch:", s)
        self.assertNotIn("  schedule:", s)
        self.assertIn("  contents: read", s)
        self.assertNotIn("contents: write", s)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", s)
        self.assertIn("github.head_ref == 'infra/android-ci-module-invalidation-pilot-v0'", s)
        self.assertIn("cache-read-only: true", s)
        self.assertNotIn("secrets.", s)
        self.assertNotIn("upload-artifact", s)
        self.assertNotIn("integration-apply", s)

    def test_four_independent_full_suite_cases_and_restoration(self):
        s = self.workflow
        self.assertIn("mode: [app, shared, sharedUi, control]", s)
        self.assertIn("max-parallel: 2", s)
        self.assertIn("fail-fast: false", s)
        for name in (
            "app/src/main/java/com/music/bitchord/data/scrobbling/PrimaryArtist.kt",
            "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/MonotonicClock.kt",
            "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricFocus.kt",
        ):
            self.assertIn(name, s)
        self.assertIn("case \"$MODE\" in", s)
        self.assertIn("run_full", s)
        self.assertIn(":app:testDevDebugUnitTest", s)
        self.assertIn("git diff --quiet HEAD", s)
        self.assertIn('trap \'git restore -- "$source"\' EXIT', s)
        self.assertIn("tools/android_ci_invalidation_probe.py", s)
        self.assertIn("GITHUB_STEP_SUMMARY", s)
        self.assertNotIn("--rerun-tasks", s)
        self.assertNotIn("--configuration-cache", s)
        self.assertNotIn("--exclude-task", s)
        self.assertNotIn(" -x ", s)

    def test_required_android_check_is_still_untouched(self):
        s = REQUIRED.read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", s)
        self.assertIn("    branches: [main]", s)
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            s,
        )
        self.assertNotIn("invalidation", s)


if __name__ == "__main__":
    unittest.main()

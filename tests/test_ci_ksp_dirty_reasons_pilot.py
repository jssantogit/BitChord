"""Fail-closed contracts for privacy-safe KSP2 incremental attribution."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.android_ci_ksp_dirty_probe import ProbeError, summarize_probe


TASKS = (
    ":app:kspDevDebugKotlin",
    ":app:kspDevDebugUnitTestKotlin",
    ":app:compileDevDebugKotlin",
    ":app:testDevDebugUnitTest",
)
LOGS = (
    "kspDirtySet.log",
    "kspDirtySetByDeps.log",
    "kspDirtySetByOutputs.log",
    "kspSourceToOutputs.log",
)
ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/android-ci-ksp-dirty-reasons-pilot.yml"


def gradle_log(task_state=""):
    lines = ["> Task " + task + (" " + task_state if task_state else "")
             for task in TASKS]
    return "\n".join([*lines, "BUILD SUCCESSFUL in 45s",
                      "73 actionable tasks: 9 executed, 64 up-to-date"])


class KspDirtyProbeTests(unittest.TestCase):
    def test_only_fixed_counts_and_no_sensitive_raw_contents(self):
        with TemporaryDirectory() as tmp:
            base = Path(tmp) / "kspCaches"
            for scope in ("devDebug", "devDebugUnitTest"):
                dest = base / scope / "logs"
                dest.mkdir(parents=True)
                for f in LOGS:
                    (dest / f).write_text(
                        "/private/path/PatientToken.kt\n"
                        "com.private.PersonName\n", encoding="utf-8")
            out = summarize_probe(gradle_log(), base, "sharedUi", "after")
            self.assertEqual(1, out["version"])
            self.assertEqual("sharedUi", out["case"])
            self.assertEqual("after", out["phase"])
            self.assertEqual(45, out["gradle_seconds"])
            self.assertEqual("executed", out["tasks"]["app_ksp_main"])
            self.assertEqual("observed", out["ksp_log_availability"])
            self.assertEqual(2, out["record_lines"]["devDebug"]["dirty_set"])
            self.assertEqual(2, out["record_lines"]["devDebugUnitTest"]["source_to_outputs"])
            self.assertFalse(out["ksp_causal_reason_proven"])
            serialized = str(out)
            for private in ("PatientToken", "PersonName", "/private/", "kspDirtySet.log"):
                self.assertNotIn(private, serialized)

    def test_missing_logs_are_explicit_unobserved_not_fabricated(self):
        with TemporaryDirectory() as tmp:
            out = summarize_probe(gradle_log("UP-TO-DATE"),
                                  Path(tmp) / "absent", "control", "before")
            self.assertEqual("unobserved", out["ksp_log_availability"])
            self.assertEqual(0, out["record_lines"]["devDebug"]["dirty_set"])
            self.assertEqual("up_to_date", out["tasks"]["app_ksp_main"])
            self.assertFalse(out["ksp_causal_reason_proven"])

    def test_partial_logs_remain_partial(self):
        with TemporaryDirectory() as tmp:
            base = Path(tmp)
            dest = base / "devDebug" / "logs"
            dest.mkdir(parents=True)
            (dest / "kspDirtySet.log").write_text("A\n")
            out = summarize_probe(gradle_log(), base, "sharedUi", "after")
            self.assertEqual("partial", out["ksp_log_availability"])
            self.assertEqual(1, out["record_lines"]["devDebug"]["dirty_set"])
            self.assertEqual(0, out["record_lines"]["devDebug"]["dirty_by_deps"])

    def test_refuses_failed_or_duplicate_or_unsafe_task_evidence(self):
        with TemporaryDirectory() as tmp:
            base = Path(tmp)
            cases = (
                gradle_log().replace("BUILD SUCCESSFUL", "BUILD FAILED"),
                gradle_log() + "\nBUILD SUCCESSFUL in 3s",
                gradle_log() + "\n> Task :app:kspDevDebugKotlin",
                gradle_log().replace("> Task :app:kspDevDebugKotlin\n", ""),
                gradle_log().replace("UP-TO-DATE", "BAD-STATE"),
            )
            for content in cases:
                with self.subTest(log=content[-12:]), self.assertRaises(ProbeError):
                    summarize_probe(content, base, "control", "after")
            for case, phase in (("secret/other", "after"), ("sharedUi", "invalid")):
                with self.assertRaises(ProbeError):
                    summarize_probe(gradle_log(), base, case, phase)

    def test_refuses_oversized_or_symlinked_ksp_data_without_leakage(self):
        with TemporaryDirectory() as tmp:
            base = Path(tmp) / "kspCaches"
            dest = base / "devDebug" / "logs"
            dest.mkdir(parents=True)
            (dest / "kspDirtySet.log").write_bytes(b"x" * 1_000_001)
            with self.assertRaises(ProbeError) as caught:
                summarize_probe(gradle_log(), base, "sharedUi", "after")
            self.assertNotIn(str(base), str(caught.exception))
            (dest / "kspDirtySet.log").unlink()
            outside = Path(tmp) / "outside"
            outside.write_text("PRIVATE_SECRET")
            (dest / "kspDirtySet.log").symlink_to(outside)
            with self.assertRaises(ProbeError):
                summarize_probe(gradle_log(), base, "sharedUi", "after")


class KspWorkflowContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.s = WORKFLOW.read_text(encoding="utf-8")

    def test_exact_branch_low_trust_and_no_raw_logging(self):
        s = self.s
        self.assertIn("name: Android KSP Incremental Dirty-Set Pilot", s)
        self.assertIn("  pull_request:", s)
        self.assertNotIn("\n  push:", s)
        self.assertNotIn("workflow_dispatch:", s)
        self.assertIn("  contents: read", s)
        self.assertNotIn("contents: write", s)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", s)
        self.assertIn("github.head_ref == 'infra/android-ci-ksp-dirty-reasons-pilot-v0'", s)
        self.assertIn("cache-read-only: true", s)
        self.assertIn("mode: [sharedUi, control]", s)
        self.assertIn("-Pksp.incremental.log=true", s)
        self.assertNotIn("ksp.incremental.intermodule", s)
        self.assertNotIn("secrets.", s)
        self.assertNotIn("upload-artifact", s)
        self.assertNotIn("tee ", s)
        self.assertNotIn("--info", s)
        self.assertNotIn("--debug", s)
        self.assertNotIn("--exclude-task", s)
        self.assertNotIn(" -x ", s)
        self.assertIn(":app:testDevDebugUnitTest", s)
        self.assertIn("GITHUB_STEP_SUMMARY", s)
        self.assertIn("tools/android_ci_ksp_dirty_probe.py", s)
        self.assertIn('trap \'git restore -- "$source"\' EXIT', s)
        self.assertIn('> "$log" 2>&1', s)
        self.assertIn("Raw Gradle logs are private to this ephemeral runner", s)

    def test_required_android_ci_remains_untouched(self):
        s = (ROOT / ".github/workflows/check.yml").read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", s)
        self.assertIn("    branches: [main]", s)
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            s,
        )
        self.assertNotIn("ksp.incremental.log", s)


if __name__ == "__main__":
    unittest.main()

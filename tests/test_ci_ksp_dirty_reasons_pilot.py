"""Fail-closed contracts for privacy-safe KSP2 incremental attribution."""
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import tools.android_ci_ksp_dirty_probe as probe
import unittest

from tools.android_ci_ksp_dirty_probe import ProbeError, summarize_probe, summarize_gradle_input_reasons, summarize_gradle_log_shape


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
            self.assertTrue(out["log_present"]["devDebug"]["dirty_set"])
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
            self.assertFalse(out["log_present"]["devDebug"]["dirty_set"])
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
            self.assertTrue(out["log_present"]["devDebug"]["dirty_set"])
            self.assertFalse(out["log_present"]["devDebug"]["dirty_by_deps"])
            self.assertEqual(0, out["record_lines"]["devDebug"]["dirty_by_deps"])

    def test_present_but_empty_log_is_not_equivalent_to_absence(self):
        with TemporaryDirectory() as tmp:
            base = Path(tmp) / "kspCaches"
            dest = base / "devDebug" / "logs"
            dest.mkdir(parents=True)
            (dest / "kspDirtySet.log").write_bytes(b"")
            out = summarize_probe(gradle_log(), base, "control", "after")
            self.assertEqual(0, out["record_lines"]["devDebug"]["dirty_set"])
            self.assertTrue(out["log_present"]["devDebug"]["dirty_set"])
            self.assertFalse(out["log_present"]["devDebug"]["dirty_by_deps"])
            self.assertEqual("partial", out["ksp_log_availability"])

    def test_refuses_failed_or_duplicate_or_unsafe_task_evidence(self):
        with TemporaryDirectory() as tmp:
            base = Path(tmp)
            cases = (
                gradle_log().replace("BUILD SUCCESSFUL", "BUILD FAILED"),
                gradle_log() + "\nBUILD SUCCESSFUL in 3s",
                gradle_log() + "\n> Task :app:kspDevDebugKotlin",
                gradle_log().replace("> Task :app:kspDevDebugKotlin\n", ""),
                gradle_log("UP-TO-DATE").replace("UP-TO-DATE", "BAD-STATE"),
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


class GradleKspInputReasonContracts(unittest.TestCase):
    def test_precise_reason_scope_without_exposing_paths_or_symbols(self):
        log = "\n".join([
            "> Task :app:kspDevDebugKotlin",
            "Task ':app:kspDevDebugKotlin' is not up-to-date because:",
            "  Input property 'classpath' file /private/patient/Token.kt has changed.",
            "  Input property 'otherNamedSecret' file /private/patient/Auth.kt has changed.",
            "> Task :app:kspDevDebugUnitTestKotlin UP-TO-DATE",
            "> Task :app:compileDevDebugKotlin UP-TO-DATE",
            "> Task :app:testDevDebugUnitTest",
            "BUILD SUCCESSFUL in 45s",
            "73 actionable tasks: 9 executed, 64 up-to-date",
        ])
        result = summarize_gradle_input_reasons(log, "sharedUi")
        self.assertEqual("observed", result["tasks"]["app_ksp_main"]["reason_availability"])
        self.assertEqual(["classpath_changed"], result["tasks"]["app_ksp_main"]["categories"])
        self.assertEqual("not_applicable", result["tasks"]["app_ksp_unit"]["reason_availability"])
        self.assertFalse(result["root_cause_proven"])
        for secret in ("patient", "Token.kt", "Auth.kt", "otherNamedSecret", "/private/"):
            self.assertNotIn(secret, str(result))

    def test_missing_debug_reason_is_not_mistaken_for_no_change(self):
        raw = gradle_log()
        result = summarize_gradle_input_reasons(raw, "control")
        self.assertEqual("unobserved", result["tasks"]["app_ksp_main"]["reason_availability"])
        self.assertEqual([], result["tasks"]["app_ksp_main"]["categories"])

    def test_ksp_source_option_and_history_categories_are_bounded(self):
        raw = "\n".join([
            "> Task :app:kspDevDebugKotlin",
            "Task ':app:kspDevDebugKotlin' is not up-to-date because:",
            "  Input property 'sourceFiles' file /secret/src.kt has been added.",
            "  Input property 'pluginOptions' has changed.",
            "  Output property 'destinationDirectory' has been removed.",
            "> Task :app:kspDevDebugUnitTestKotlin",
            "Task ':app:kspDevDebugUnitTestKotlin' is not up-to-date because:",
            "  No history is available.",
            "> Task :app:compileDevDebugKotlin",
            "> Task :app:testDevDebugUnitTest",
            "BUILD SUCCESSFUL in 12s",
            "73 actionable tasks: 10 executed, 63 up-to-date",
        ])
        got = summarize_gradle_input_reasons(raw, "sharedUi")
        self.assertEqual(["config_changed", "outputs_changed", "sources_changed"],
                         got["tasks"]["app_ksp_main"]["categories"])
        self.assertEqual(["history_missing"],
                         got["tasks"]["app_ksp_unit"]["categories"])

    def test_info_log_has_strict_but_adequate_bounded_size_limit(self):
        raw = gradle_log() + "\n" + ("PRIVATE_NOT_TO_EXPORT " * 30)
        self.assertGreater(probe._MAX_GRADLE_LOG_BYTES, 8_000_000)
        with patch.object(probe, "_MAX_GRADLE_LOG_BYTES", len(raw) + 1):
            out = summarize_gradle_input_reasons(raw, "sharedUi")
            self.assertEqual("unobserved", out["tasks"]["app_ksp_main"]["reason_availability"])
        with patch.object(probe, "_MAX_GRADLE_LOG_BYTES", len(raw) - 1):
            with self.assertRaises(ProbeError) as caught:
                summarize_gradle_input_reasons(raw, "sharedUi")
            self.assertNotIn("PRIVATE_NOT_TO_EXPORT", str(caught.exception))

    def test_untrusted_or_ambiguous_input_reason_logs_fail_closed(self):
        cases = [
            gradle_log().replace("BUILD SUCCESSFUL", "BUILD FAILED"),
            gradle_log() + "\nBUILD SUCCESSFUL in 10s",
            gradle_log() + "\n> Task :app:kspDevDebugKotlin",
            "\n".join([
                "> Task :app:kspDevDebugKotlin",
                "Task ':app:kspDevDebugKotlin' is not up-to-date because:",
                "  Input property 'classpath' file /a changed.",
                "Task ':app:kspDevDebugKotlin' is not up-to-date because:",
                "  Input property 'classpath' file /b changed.",
                "> Task :app:kspDevDebugUnitTestKotlin",
                "> Task :app:compileDevDebugKotlin",
                "> Task :app:testDevDebugUnitTest",
                "BUILD SUCCESSFUL in 11s",
                "73 actionable tasks: 9 executed, 64 up-to-date",
            ]),
        ]
        for sample in cases:
            with self.subTest(case=len(sample)), self.assertRaises(ProbeError):
                summarize_gradle_input_reasons(sample, "sharedUi")
        with self.assertRaises(ProbeError):
            summarize_gradle_input_reasons(gradle_log(), "/absolute/secret")


class SafeGradleShapeDiagnostics(unittest.TestCase):
    def test_shape_reports_only_fixed_counts_and_size_bands(self):
        with TemporaryDirectory() as tmp:
            p = Path(tmp) / "verbose.log"
            p.write_text(gradle_log() + "\n" +
                "Task ':app:kspDevDebugKotlin' is not up-to-date because:\n" +
                "  Input property 'superSecret' file /private/Token.kt has changed.\n")
            out = summarize_gradle_log_shape(p)
            self.assertEqual("within_limit", out["size_status"])
            self.assertEqual("valid_task_summary", out["task_shape"])
            self.assertEqual("one_success", out["build_shape"])
            self.assertNotIn("Token.kt", str(out))
            self.assertNotIn("superSecret", str(out))
            self.assertNotIn("/private", str(out))

    def test_shape_distinguishes_duplicate_and_missing_without_identifiers(self):
        with TemporaryDirectory() as tmp:
            p = Path(tmp) / "log"
            p.write_text(gradle_log() + "\n> Task :app:kspDevDebugKotlin")
            out = summarize_gradle_log_shape(p)
            self.assertEqual("duplicate_task_marker", out["task_shape"])
            p.write_text(gradle_log().replace("> Task :app:kspDevDebugKotlin\n", ""))
            out = summarize_gradle_log_shape(p)
            self.assertEqual("missing_task_marker", out["task_shape"])

    def test_shape_refuses_unsafe_large_or_symlinked_files(self):
        with TemporaryDirectory() as tmp:
            p = Path(tmp) / "log"
            p.write_bytes(b"x" * 150)
            with patch.object(probe, "_MAX_GRADLE_LOG_BYTES", 100):
                result = summarize_gradle_log_shape(p)
            self.assertEqual("over_limit", result["size_status"])
            self.assertEqual("not_inspected", result["task_shape"])
            victim = Path(tmp) / "secret"
            victim.write_text("ALIAS_SECRET")
            p.unlink()
            p.symlink_to(victim)
            with self.assertRaises(ProbeError) as caught:
                summarize_gradle_log_shape(p)
            self.assertNotIn("ALIAS_SECRET", str(caught.exception))

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
        self.assertIn('run_full "$RUNNER_TEMP/ksp-after.log" --info', s)
        self.assertIn('run_full "$RUNNER_TEMP/ksp-before.log"', s)
        self.assertNotIn('run_full "$RUNNER_TEMP/ksp-before.log" --info', s)
        self.assertIn("KSP_SANITIZED_INPUT_REASONS", s)
        self.assertIn("KSP_P10_LOG_SHAPE", s)
        self.assertIn("--shape-log", s)
        self.assertIn("KSP_P10_STAGE_AFTER_COUNTS", s)
        self.assertIn("KSP_P10_STAGE_INPUT_CLASSIFICATION", s)
        self.assertIn("KSP_P10_STAGE_ALL_SUMMARIES_READY", s)
        self.assertIn("summarize_gradle_input_reasons", (ROOT / "tools/android_ci_ksp_dirty_probe.py").read_text(encoding="utf-8"))
        self.assertNotIn("--debug", s)
        self.assertNotIn("--exclude-task", s)
        self.assertNotIn(" -x ", s)
        self.assertIn(":app:testDevDebugUnitTest", s)
        self.assertIn("GITHUB_STEP_SUMMARY", s)
        self.assertIn("KSP_SANITIZED_SUMMARY_BEFORE", s)
        self.assertIn("KSP_SANITIZED_SUMMARY_AFTER", s)
        self.assertIn('cat "$RUNNER_TEMP/summary-before.json"', s)
        self.assertIn('cat "$RUNNER_TEMP/summary-after.json"', s)

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

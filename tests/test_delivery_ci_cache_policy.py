"""Fail-closed evidence extraction from bounded Android CI job logs.

The parser emits only fixed labels and numeric data. No raw log text, cache
keys, user path, repository token or Android metadata may be copied to output.
"""
import unittest

from tools.delivery_ci_cache_policy import EvidenceError, summarize_cache_job


SHA = "a" * 40
PREFIX = "2026-10-21T15:00:{:02d}.0000000Z "
RUN = "##[group]Run ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest"


def line(second, message):
    return PREFIX.format(second) + message


class AndroidCacheEvidenceTests(unittest.TestCase):
    def test_main_write_phase_measured_separately_from_gradle_tasks(self):
        log = "\n".join([
            line(0, RUN),
            line(1, "> Task :app:kspDevDebugKotlin"),
            line(2, "> Task :app:compileDevDebugKotlin FROM-CACHE"),
            line(3, "BUILD SUCCESSFUL in 2m 1s"),
            line(4, "73 actionable tasks: 40 executed, 33 from cache"),
            line(10, "##[group]Caching Gradle state"),
            line(11, "Saved cache entry with key SECRET=should-never-echo in 2000ms"),
            line(20, "##[endgroup]"),
        ])
        output = summarize_cache_job(log, SHA, "main")
        self.assertEqual(121, output["gradle_seconds"])
        self.assertEqual(40, output["executed_tasks"])
        self.assertEqual(33, output["from_cache_tasks"])
        self.assertEqual("written", output["write_policy"])
        self.assertEqual(10, output["cache_write_phase_seconds"])
        self.assertEqual(1, output["saved_cache_entries"])
        self.assertEqual("executed", output["tasks"]["ksp_main"])
        self.assertEqual("from_cache", output["tasks"]["kotlin_main"])
        self.assertNotIn("SECRET", str(output))
        self.assertNotIn("should-never-echo", str(output))
        self.assertNotIn("cache entry with key", str(output))

    def test_pr_read_only_reports_zero_write_only_with_positive_evidence(self):
        log = "\n".join([
            line(0, RUN),
            line(1, "> Task :app:kspDevDebugKotlin FROM-CACHE"),
            line(2, "BUILD SUCCESSFUL in 53s"),
            line(3, "73 actionable tasks: 34 executed, 39 from cache"),
            line(4, "Cache is read-only: will not save state for use in subsequent builds."),
        ])
        output = summarize_cache_job(log, SHA, "pr")
        self.assertEqual(53, output["gradle_seconds"])
        self.assertEqual("read_only", output["write_policy"])
        self.assertEqual(0, output["cache_write_phase_seconds"])
        self.assertEqual(0, output["saved_cache_entries"])
        self.assertEqual("from_cache", output["tasks"]["ksp_main"])
        self.assertIsNone(output["tasks"]["kotlin_main"])

    def test_unobserved_cache_outcome_is_not_guessed_from_lane(self):
        log = "\n".join([
            line(0, RUN),
            line(1, "BUILD SUCCESSFUL in 4m"),
            line(2, "73 actionable tasks: 40 executed, 33 from cache"),
        ])
        result = summarize_cache_job(log, SHA, "main")
        self.assertEqual("unobserved", result["write_policy"])
        self.assertIsNone(result["cache_write_phase_seconds"])
        self.assertIsNone(result["saved_cache_entries"])

    def test_rejects_truncated_or_malformed_evidence(self):
        good = "\n".join([line(0, RUN), line(1, "BUILD SUCCESSFUL in 5s"),
                            line(2, "73 actionable tasks: 60 executed, 13 from cache")])
        for body, sha, lane in [
            ("irrelevant", SHA, "main"),
            (good.replace("BUILD SUCCESSFUL", "BUILD FAILED"), SHA, "main"),
            (good, "not-sha", "main"),
            (good, SHA, "token=unexpected"),
            (good.replace("60 executed, 13 from cache", "60 executed, 50 from cache"), SHA, "main"),
            (good + "\n" + line(5, "##[group]Caching Gradle state"), SHA, "main"),
            (good + "\n" + line(10, "##[endgroup]"), SHA, "main"),
            (good + "\n" + line(7, "Cache is read-only: will not save state for use in subsequent builds.")
                + "\n" + line(8, "##[group]Caching Gradle state")
                + "\n" + line(9, "##[endgroup]"), SHA, "main"),
        ]:
            with self.subTest(case=body[:28]), self.assertRaises(EvidenceError):
                summarize_cache_job(body, sha, lane)

    def test_refuses_clock_regression_and_bad_duration(self):
        log = "\n".join([
            line(0, RUN),
            line(1, "BUILD SUCCESSFUL in 10s"),
            line(2, "73 actionable tasks: 40 executed, 33 from cache"),
            line(20, "##[group]Caching Gradle state"),
            line(10, "##[endgroup]"),
        ])
        with self.assertRaises(EvidenceError):
            summarize_cache_job(log, SHA, "main")
        with self.assertRaises(EvidenceError):
            summarize_cache_job(log.replace("in 10s", "in UNKNOWN"), SHA, "main")


if __name__ == "__main__":
    unittest.main()

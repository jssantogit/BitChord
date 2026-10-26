"""Pairwise main CI cache evidence must never imply an unsafe speedup."""
import unittest

from tools.delivery_ci_cache_lineage import compare_main_cache_evidence, LineageError

A = "a" * 40
B = "b" * 40
TASKS = {
    "ksp_main": "executed",
    "kotlin_main": "executed",
    "ksp_tests": "executed",
    "kotlin_tests": "executed",
    "android_tests": "executed",
    "shared_kotlin": "from_cache",
}


def snapshot(sha, cached=33, executed=40, gradle=234, family="android_workflow",
             lane="main", write="written"):
    return {
        "version": 1, "head_sha": sha, "lane": lane,
        "gradle_seconds": gradle, "actionable_tasks": 73,
        "executed_tasks": executed, "from_cache_tasks": cached,
        "cache_restore_status": "hit",
        "cache_restore_workflow_family": family,
        "write_policy": write, "cache_write_phase_seconds": 23,
        "saved_cache_entries": 4, "tasks": dict(TASKS),
    }


class CacheLineageTests(unittest.TestCase):
    def test_two_main_observations_do_not_claim_caused_speedup(self):
        a = snapshot(A, cached=33, executed=40, gradle=234)
        b = snapshot(B, cached=39, executed=34, gradle=50)
        out = compare_main_cache_evidence(a, b)
        self.assertEqual("observations_only", out["status"])
        self.assertEqual("android_workflow", out["cache_workflow_family"])
        self.assertEqual(6, out["from_cache_task_delta"])
        self.assertEqual(-6, out["executed_task_delta"])
        self.assertEqual(-184, out["gradle_wall_seconds_delta"])
        self.assertFalse(out["cross_revision_output_reuse_proven"])
        self.assertFalse(out["speedup_attributable_to_cache"])
        self.assertFalse(out["safe_to_promote_pr_cache"])
        self.assertNotIn(A, str(out))
        self.assertNotIn(B, str(out))
        self.assertNotIn("head_sha", str(out))
        self.assertNotIn("cache_key", str(out))

    def test_wrong_trust_scope_and_cross_workflow_are_not_comparable(self):
        good = snapshot(A)
        for bad in (
            snapshot(B, lane="pr"),
            snapshot(B, family="build_smoke_apk_workflow"),
            snapshot(B, family="other_workflow"),
            {**snapshot(B), "cache_restore_status": "unobserved",
             "cache_restore_workflow_family": None},
            snapshot(B, write="read_only"),
            snapshot(B, write="unobserved"),
        ):
            with self.subTest(case=(bad.get("lane"), bad.get("write_policy"),
                                    bad.get("cache_restore_workflow_family"))):
                result = compare_main_cache_evidence(good, bad)
                self.assertEqual("incomparable", result["status"])
                self.assertIsNone(result["from_cache_task_delta"])
                self.assertFalse(result["safe_to_promote_pr_cache"])
                self.assertFalse(result["speedup_attributable_to_cache"])

    def test_missing_compiler_task_evidence_must_not_claim_lineage(self):
        a = snapshot(A)
        b = snapshot(B)
        b["tasks"]["ksp_main"] = None
        result = compare_main_cache_evidence(a, b)
        self.assertEqual("incomplete_task_evidence", result["status"])
        self.assertIsNone(result["gradle_wall_seconds_delta"])
        self.assertFalse(result["cross_revision_output_reuse_proven"])

    def test_hard_fail_on_invalid_counts_and_untrusted_strings(self):
        for a, b in (
            (snapshot(A), snapshot(A)),
            (snapshot(A), {**snapshot(B), "head_sha": "invalid"}),
            (snapshot(A), {**snapshot(B), "from_cache_tasks": 300}),
            (snapshot(A), {**snapshot(B), "actionable_tasks": 1}),
            (snapshot(A), {**snapshot(B), "lane": "PR-TOKEN=SECRET"}),
            (snapshot(A), {**snapshot(B), "tasks": {"unknown": "executed"}}),
            (snapshot(A), {**snapshot(B), "tasks": dict(TASKS, ksp_main="UNSAFE")}),
            (snapshot(A), {**snapshot(B), "cache_restore_workflow_family": "foo SECRET"}),
            (snapshot(A), {**snapshot(B), "gradle_seconds": -5}),
        ):
            with self.subTest(bad=repr(b)[:70]), self.assertRaises(LineageError) as caught:
                compare_main_cache_evidence(a, b)
            self.assertNotIn("SECRET", str(caught.exception))

    def test_rejects_unhashable_and_boolean_forged_evidence(self):
        for field, value in (
            ("version", True),
            ("cache_restore_workflow_family", ["android_workflow"]),
            ("write_policy", ["written"]),
            ("lane", ["main"]),
            ("cache_restore_status", ["hit"]),
            ("executed_tasks", True),
            ("gradle_seconds", False),
        ):
            b = snapshot(B)
            b[field] = value
            with self.subTest(field=field), self.assertRaises(LineageError):
                compare_main_cache_evidence(snapshot(A), b)
        b = snapshot(B)
        b["tasks"]["kotlin_main"] = ["from_cache"]
        with self.assertRaises(LineageError):
            compare_main_cache_evidence(snapshot(A), b)

    def test_cannot_infer_identity_from_cache_hit_counts(self):
        # Cache hits are output-hash evidence, but not commit provenance or
        # proof of which earlier workflow produced those build outputs.
        a = snapshot(A, cached=39, executed=34)
        b = snapshot(B, cached=39, executed=34)
        result = compare_main_cache_evidence(a, b)
        self.assertEqual("observations_only", result["status"])
        self.assertEqual(0, result["from_cache_task_delta"])
        self.assertFalse(result["cross_revision_output_reuse_proven"])


if __name__ == "__main__":
    unittest.main()

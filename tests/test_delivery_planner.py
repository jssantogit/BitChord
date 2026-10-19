"""DI2 read-only graph planning and exact manifest proposals."""
import unittest
from tools.delivery_planner import PlannerInputError, plan


MAIN = "0" * 40


def pr(number, ref, base="main", files=None, draft=False, check="success",
       mergeable="clean"):
    return {"number": number, "head_sha": format(number, "040x"),
            "head_ref": ref, "base_ref": base,
            "files": files or [f"src/{number}.kt"], "draft": draft,
            "check": check, "mergeable": mergeable}


class PlannerTests(unittest.TestCase):
    def test_topological_order_and_proposals_preserve_review_gate(self):
        snapshot = {"main_sha": MAIN, "pull_requests": [
            pr(12, "perf/p12"),
            pr(11, "catalog/p11", "catalog/p10"),
            pr(10, "catalog/p10"),
        ]}
        first = plan(snapshot)
        second = plan(snapshot)
        self.assertEqual(first, second)
        self.assertEqual([10, 11, 12], [x["number"] for x in first["ordered"]])
        self.assertEqual([10, 12], [x["number"] for x in first["manifest_proposal"]["items"]])
        child = next(x for x in first["ordered"] if x["number"] == 11)
        self.assertEqual([10], child["dependencies"])
        self.assertIn("stacked_base", child["blockers"])
        self.assertTrue(first["approval_required"])
        self.assertFalse(first["executable"])

    def test_overlap_conflicts_are_blocked_not_assumed_safe(self):
        result = plan({"main_sha": MAIN, "pull_requests": [
            pr(1, "catalog/p1", files=["app/build.gradle.kts"]),
            pr(2, "perf/p2", files=["app/build.gradle.kts"])]})
        self.assertEqual([], result["manifest_proposal"]["items"])
        self.assertTrue(all("overlapping_files" in x["blockers"] for x in result["ordered"]))

    def test_missing_parent_draft_and_untrusted_checks_block(self):
        p = plan({"main_sha": MAIN, "pull_requests": [
            pr(2, "playback/p2", "missing/parent"),
            pr(1, "perf/p1", draft=True, check="pending")]})
        self.assertEqual([], p["manifest_proposal"]["items"])
        self.assertEqual(["draft", "ci_pending"], p["ordered"][0]["blockers"])
        self.assertIn("missing_parent", p["ordered"][1]["blockers"])

    def test_cycle_is_explained_not_executed(self):
        x = plan({"main_sha": MAIN, "pull_requests": [
            pr(1, "a/one", "b/two"), pr(2, "b/two", "a/one")]})
        self.assertEqual([1, 2], [x["number"] for x in x["ordered"]])
        self.assertTrue(all("dependency_cycle" in r["blockers"] for r in x["ordered"]))
        self.assertEqual([], x["manifest_proposal"]["items"])

    def test_invalid_sha_duplicate_branch_and_traversal_fail_closed(self):
        for ps in ([pr(1, "p/a", files=["../secrets"])],
                   [pr(1, "p/a"), pr(2, "p/a")],
                   [dict(pr(1, "p/a"), head_sha="not-a-sha")]):
            with self.subTest(ps=ps), self.assertRaises(PlannerInputError):
                plan({"main_sha": MAIN, "pull_requests": ps})

    def test_bounded_batch_never_approves_itself(self):
        p = plan({"main_sha": MAIN, "pull_requests": [
            pr(n, f"infra/p{n}") for n in (4, 2, 3, 1)]})
        self.assertEqual([1, 2, 3], [x["number"] for x in p["manifest_proposal"]["items"]])
        self.assertEqual([4], p["not_proposed"])
        self.assertTrue(p["approval_required"])


if __name__ == "__main__":
    unittest.main()

"""DI2 live snapshot collection must be complete, pinned, GET-only and redacted."""
import unittest

from tools.delivery_snapshot import SnapshotError, GitHubSnapshotReader, collect_snapshot
from tools.delivery_planner import plan

MAIN = "a" * 40
ONE = "1" * 40
TWO = "2" * 40


class FakeReader:
    def __init__(self):
        self.main = MAIN
        self.read_count = 0
        self.details = {
            2: {"number": 2, "state": "open", "merged": False,
                "head_sha": TWO, "head_ref": "perf/p2", "base_ref": "main",
                "draft": True, "mergeable": "blocked", "head_repo": "jssantogit/BitChord"},
            1: {"number": 1, "state": "open", "merged": False,
                "head_sha": ONE, "head_ref": "catalog/p1", "base_ref": "main",
                "draft": False, "mergeable": "clean", "head_repo": "jssantogit/BitChord"},
        }

    def main_sha(self):
        self.read_count += 1
        return self.main

    def open_prs(self):
        return [dict(self.details[2]), dict(self.details[1])]

    def pr(self, number):
        return dict(self.details[number])

    def files(self, number):
        return [f"src/{number}.kt"]

    def check(self, sha):
        return "success" if sha == ONE else "pending"


class SnapshotTests(unittest.TestCase):
    def test_live_collection_is_pinned_sorted_and_consumable_by_planner(self):
        reader = FakeReader()
        result = collect_snapshot(reader, "jssantogit/BitChord")
        self.assertEqual(MAIN, result["main_sha"])
        self.assertEqual([1, 2], [p["number"] for p in result["pull_requests"]])
        self.assertEqual("pending", result["pull_requests"][1]["check"])
        self.assertEqual("blocked", result["pull_requests"][1]["mergeable"])
        self.assertEqual(2, reader.read_count)
        classified = plan(result)
        self.assertEqual([1], [x["number"] for x in classified["manifest_proposal"]["items"]])
        self.assertIn("merge_policy_blocked", classified["ordered"][1]["blockers"])
        self.assertFalse(classified["executable"])

    def test_stale_main_head_and_missing_pull_request_fail_closed(self):
        class MainDrift(FakeReader):
            def main_sha(self):
                self.read_count += 1
                return MAIN if self.read_count == 1 else "b" * 40

        with self.assertRaises(SnapshotError):
            collect_snapshot(MainDrift(), "jssantogit/BitChord")
        changed = FakeReader()
        changed.details[2]["head_sha"] = "f" * 40
        # The listing remains old while the detail endpoint sees the new HEAD.
        changed.open_prs = lambda: [
            dict(FakeReader().details[2]), dict(FakeReader().details[1])]
        with self.assertRaises(SnapshotError):
            collect_snapshot(changed, "jssantogit/BitChord")
        gone = FakeReader()
        gone.details[1]["state"] = "closed"
        with self.assertRaises(SnapshotError):
            collect_snapshot(gone, "jssantogit/BitChord")

    def test_duplicate_pr_and_incomplete_file_list_fail_closed(self):
        duplicate = FakeReader()
        duplicate.open_prs = lambda: [duplicate.details[1], duplicate.details[1]]
        with self.assertRaises(SnapshotError):
            collect_snapshot(duplicate, "jssantogit/BitChord")
        excessive = FakeReader()
        excessive.files = lambda n: [f"x/{i}.kt" for i in range(101)]
        with self.assertRaises(SnapshotError):
            collect_snapshot(excessive, "jssantogit/BitChord")

    def test_untrusted_exceptions_are_not_echoed(self):
        r = FakeReader()
        r.check = lambda sha: (_ for _ in ()).throw(RuntimeError("TOKEN=private-123"))
        with self.assertRaises(SnapshotError) as caught:
            collect_snapshot(r, "jssantogit/BitChord")
        self.assertNotIn("private-123", str(caught.exception))

    def test_adapter_paginates_and_rejects_duplicate_checks(self):
        adapter = GitHubSnapshotReader("jssantogit/BitChord")
        calls = []
        def fixture(path):
            calls.append(path)
            if path.endswith("page=1"):
                return [{"number": n, "head": {"sha": format(n, "040x"), "ref": f"infra/p{n}"}, "base": {"ref": "main"}, "draft": True} for n in range(1, 101)]
            if path.endswith("page=2"):
                return [{"number": 101, "head": {"sha": format(101, "040x"), "ref": "infra/p101"}, "base": {"ref": "main"}, "draft": True}]
            return {"check_runs": [
                {"name": "Android · test and build", "status": "completed",
                 "conclusion": "success"},
                {"name": "Android · test and build", "status": "completed",
                 "conclusion": "success"}]}
        adapter._get = fixture
        self.assertEqual(101, len(adapter.open_prs()))
        self.assertEqual("missing", adapter.check(ONE))
        self.assertEqual(3, len(calls))
        self.assertTrue(all("page=" in x or "/commits/" in x for x in calls))


if __name__ == "__main__":
    unittest.main()

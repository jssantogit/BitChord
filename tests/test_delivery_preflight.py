"""DI3 read-only preflight: bounded concurrency, drift, fail-closed results."""
import threading
import time
import unittest

from tools.delivery_planner import plan
from tools.delivery_preflight import PreflightInputError, parallel_preflight


MAIN = "0" * 40


def sample():
    return plan({"main_sha": MAIN, "pull_requests": [
        {"number": i, "head_sha": format(i, "040x"), "head_ref": f"infra/p{i}",
         "base_ref": "main", "files": [f"src/{i}.kt"],
         "draft": False, "check": "success", "mergeable": "clean"}
        for i in (3, 1, 2)]})


class Reader:
    def __init__(self):
        self.active, self.max_active = 0, 0
        self.lock = threading.Lock()
        self.sha = MAIN
        self.drift = False

    def main_sha(self):
        return ("e" * 40) if self.drift else self.sha

    def pr(self, n):
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        time.sleep(0.01)
        with self.lock:
            self.active -= 1
        return {"head_sha": format(n, "040x"), "base_ref": "main",
                "base_sha": MAIN, "draft": False, "mergeable": "clean"}

    def files(self, n):
        return [f"src/{n}.kt"]

    def check(self, sha):
        return "success"

    def merge(self, *args):
        raise AssertionError("preflight must not write")


class PreflightTests(unittest.TestCase):
    def test_parallel_reads_are_sorted_and_never_authorize_merge(self):
        r = Reader()
        result = parallel_preflight(sample(), r, max_workers=3)
        self.assertGreaterEqual(r.max_active, 2)
        self.assertEqual([1, 2, 3], [e["number"] for e in result["results"]])
        self.assertTrue(all(e["verified_candidate"] for e in result["results"]))
        self.assertTrue(result["approval_required"])
        self.assertFalse(result["executable"])

    def test_head_and_file_drift_are_independent_blockers(self):
        class Drift(Reader):
            def pr(self, n):
                v = super().pr(n)
                if n == 2:
                    v["head_sha"] = "f" * 40
                return v

            def files(self, n):
                return ["unexpected.txt"] if n == 3 else super().files(n)

        rows = parallel_preflight(sample(), Drift())["results"]
        self.assertIn("head_drift", rows[1]["blockers"])
        self.assertIn("file_drift", rows[2]["blockers"])
        self.assertFalse(rows[1]["verified_candidate"])

    def test_main_drift_invalidates_every_result(self):
        r = Reader()
        times = 0
        def changing():
            nonlocal times
            times += 1
            return MAIN if times == 1 else "e" * 40
        r.main_sha = changing
        results = parallel_preflight(sample(), r)
        self.assertTrue(results["main_drift"])
        self.assertTrue(all(not e["verified_candidate"] for e in results["results"]))

    def test_read_failures_are_redacted_and_fail_closed(self):
        class Failed(Reader):
            def check(self, sha):
                raise RuntimeError("private token is forbidden")
        result = parallel_preflight(sample(), Failed())
        self.assertFalse(any(x["verified_candidate"] for x in result["results"]))
        self.assertTrue(all("read_error" in x["blockers"] for x in result["results"]))
        self.assertNotIn("private token", str(result))

    def test_rejects_unapproved_planner_or_unbounded_workers(self):
        bad = sample()
        bad["executable"] = True
        with self.assertRaises(PreflightInputError):
            parallel_preflight(bad, Reader())
        with self.assertRaises(PreflightInputError):
            parallel_preflight(sample(), Reader(), max_workers=99)


if __name__ == "__main__":
    unittest.main()

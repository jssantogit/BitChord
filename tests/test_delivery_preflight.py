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
        self.repository = "jssantogit/BitChord"

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
                "base_sha": MAIN, "draft": False, "mergeable": "clean",
                "state": "open", "merged": False,
                "head_repo": "jssantogit/BitChord"}

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

    def test_closed_or_foreign_pr_is_not_prepared_for_execution(self):
        class Closed(Reader):
            def pr(self, n):
                result = super().pr(n)
                if n == 1:
                    result["state"] = "closed"
                    result["merged"] = True
                if n == 2:
                    result["head_repo"] = "attacker/untrusted"
                return result

        result = parallel_preflight(sample(), Closed())
        self.assertIn("pr_closed", result["results"][0]["blockers"])
        self.assertIn("untrusted_head_repository", result["results"][1]["blockers"])
        self.assertFalse(result["results"][0]["verified_candidate"])
        self.assertFalse(result["results"][1]["verified_candidate"])
        self.assertTrue(result["approval_required"])
        self.assertFalse(result["executable"])

    def test_portable_restart_evidence_never_bypasses_di3_preflight(self):
        # A clean live observation AND a verified portable checkpoint are still
        # two read-only reports; neither may authorize a protected merge.
        import tempfile
        from pathlib import Path
        from tools.delivery_journal import initialize
        from tools.delivery_transfer import export_evidence, inspect_evidence
        manifest = {"version": 1, "repository": "jssantogit/BitChord",
                    "target": "main", "items": [{
                        "number": 1, "expected_head_sha": format(1, "040x"),
                        "expected_base_ref": "main", "depends_on": None,
                        "allowed_files": ["src/1.kt"]}]}
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "checkpoint.json"
            initialize(file, manifest, MAIN, authorized=False)
            blob, digest = export_evidence(
                file, manifest, run_id=77, attempt=1, source_main_sha=MAIN,
                event="workflow_dispatch", ref="refs/heads/main")
            recovered = inspect_evidence(
                blob, manifest, sha256=digest, run_id=77, attempt=1,
                expected_main_sha=MAIN)
        live = parallel_preflight(sample(), Reader())
        self.assertTrue(all(row["verified_candidate"] for row in live["results"]))
        self.assertFalse(live["executable"])
        self.assertFalse(recovered["executable"])
        self.assertFalse(recovered["replay_merge"])
        self.assertTrue(recovered["approval_required"])

    def test_rejects_unapproved_planner_or_unbounded_workers(self):
        bad = sample()
        bad["executable"] = True
        with self.assertRaises(PreflightInputError):
            parallel_preflight(bad, Reader())
        with self.assertRaises(PreflightInputError):
            parallel_preflight(sample(), Reader(), max_workers=99)


if __name__ == "__main__":
    unittest.main()

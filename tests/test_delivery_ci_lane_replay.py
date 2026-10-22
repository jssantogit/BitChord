"""Offline fourth-lane replay. All improvement claims require baseline calibration."""
import unittest
from datetime import datetime, timedelta, timezone

from tools.delivery_ci_lane_replay import LaneReplayError, replay_recent_ci


SHA = "a" * 40
BASE = datetime(2026, 10, 22, tzinfo=timezone.utc)


def stamp(seconds):
    return (BASE + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


class Reader:
    repository = "jssantogit/BitChord"

    def __init__(self, calibrated=True):
        self.runs = []
        self.jobs_by_id = {}
        for i in range(8):
            run_id = 100 + i
            branch = ("perf/d15-secret-token-not-for-export" if i % 2 == 0
                      else "infra/android-ci-example")
            self.runs.append({
                "id": run_id, "event": "pull_request",
                "head_sha": SHA, "head_branch": branch,
                "head_repository": {"full_name": self.repository},
            })
            ready = i
            # All eight jobs are currently serialized in legacy lane C.
            actual_start = i * 100 + (500 if not calibrated else 0)
            self.jobs_by_id[run_id] = [{
                "id": 1000 + i, "name": "Android · test and build",
                "created_at": stamp(ready),
                "started_at": stamp(actual_start),
                "completed_at": stamp(actual_start + 100),
                "conclusion": "success",
            }]

    def runs_page(self, n):
        return self.runs if n == 1 else []

    def jobs(self, run_id):
        return self.jobs_by_id[run_id]


class LaneReplayTests(unittest.TestCase):
    def test_calibrated_replay_exposes_only_fixed_aggregate_metrics(self):
        report = replay_recent_ci(Reader())
        self.assertEqual("calibrated_sample_only", report["status"])
        self.assertEqual(8, report["included_jobs"])
        self.assertEqual(4, report["performance_jobs"])
        self.assertEqual(4, report["other_c_jobs"])
        self.assertEqual(0, report["baseline_median_absolute_error_seconds"])
        self.assertGreater(report["idealized_total_wait_reduction_seconds"], 0)
        self.assertGreater(report["baseline_idealized_total_wait_seconds"],
                           report["candidate_idealized_total_wait_seconds"])
        self.assertNotIn("secret-token", str(report))
        self.assertNotIn("infra/android-ci-example", str(report))
        self.assertNotIn(SHA, str(report))
        self.assertNotIn("head_branch", str(report))

    def test_unreliable_original_lane_replay_never_claims_savings(self):
        report = replay_recent_ci(Reader(calibrated=False))
        self.assertEqual("uncalibrated", report["status"])
        self.assertIsNone(report["idealized_total_wait_reduction_seconds"])
        self.assertGreater(report["baseline_median_absolute_error_seconds"], 30)

    def test_single_badly_misfit_job_vetoes_positive_replay(self):
        reader = Reader()
        # A median-only fit check would miss this large real-world outlier.
        reader.jobs_by_id[107][0]["started_at"] = stamp(1200)
        reader.jobs_by_id[107][0]["completed_at"] = stamp(1300)
        report = replay_recent_ci(reader)
        self.assertEqual("uncalibrated", report["status"])
        self.assertIsNone(report["idealized_total_wait_reduction_seconds"])

    def test_incomplete_or_one_sided_sample_reports_no_gain(self):
        r = Reader()
        r.runs = r.runs[:4]
        result = replay_recent_ci(r)
        self.assertEqual("insufficient_sample", result["status"])
        self.assertIsNone(result["idealized_total_wait_reduction_seconds"])
        r = Reader()
        for run in r.runs:
            run["head_branch"] = "perf/d15"
        report = replay_recent_ci(r)
        self.assertEqual("insufficient_sample", report["status"])
        self.assertIsNone(report["idealized_total_wait_reduction_seconds"])

    def test_rejects_missing_provenance_and_bad_or_impossible_times(self):
        cases = [
            lambda r: setattr(r, "repository", "stranger/other"),
            lambda r: r.runs[0].update({"head_sha": "BAD"}),
            lambda r: r.runs[0]["head_repository"].update({"full_name": "stranger/other"}),
            lambda r: r.runs.append(dict(r.runs[0])),
            lambda r: r.jobs_by_id[100][0].update({"started_at": stamp(-1)}),
            lambda r: r.jobs_by_id[100][0].update({"completed_at": stamp(0)}),
            lambda r: r.jobs_by_id[100].append(dict(r.jobs_by_id[100][0])),
        ]
        for i, mutate in enumerate(cases):
            reader = Reader()
            mutate(reader)
            with self.subTest(mutator_index=i), self.assertRaises(LaneReplayError):
                replay_recent_ci(reader)

    def test_failed_job_is_excluded_and_blocks_claimed_savings(self):
        reader = Reader()
        reader.jobs_by_id[100][0]["conclusion"] = "failure"
        result = replay_recent_ci(reader)
        self.assertEqual("insufficient_sample", result["status"])
        self.assertEqual(1, result["excluded_unfinished_or_missing_jobs"])
        self.assertIsNone(result["idealized_total_wait_reduction_seconds"])

    def test_rejects_truncated_page_and_sanitizes_reader_errors(self):
        reader = Reader()
        reader.runs = reader.runs * 13  # More than a complete 100-item page.
        with self.assertRaises(LaneReplayError):
            replay_recent_ci(reader)
        reader = Reader()
        reader.jobs = lambda rid: (_ for _ in ()).throw(RuntimeError("TOKEN=PRIVATE"))
        with self.assertRaises(LaneReplayError) as caught:
            replay_recent_ci(reader)
        self.assertNotIn("PRIVATE", str(caught.exception))


if __name__ == "__main__":
    unittest.main()

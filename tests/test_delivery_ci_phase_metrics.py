"""DI5-compatible Android CI phase metrics; no raw workflow fields escape."""
import unittest

from tools.delivery_ci_phase_metrics import PhaseError, collect_phase_observations

SHA = "a" * 40
BASE = "2026-10-21T"


def ts(hms):
    return BASE + hms + "Z"


class Reader:
    repository = "jssantogit/BitChord"

    def __init__(self):
        self.runs = [{
            "id": 100,
            "head_sha": SHA,
            "head_branch": "perf/d14-stall SECRET_IN_BRANCH",
            "event": "pull_request",
            "head_repository": {"full_name": self.repository},
            "pull_requests": [{"number": 143}],
        }]
        self.job = {
            "id": 900,
            "name": "Android · test and build",
            "created_at": ts("20:00:00"),
            "started_at": ts("20:00:20"),
            "completed_at": ts("20:04:00"),
            "conclusion": "success",
            "steps": [
                {"name": "Set up job", "started_at": ts("20:00:20"), "completed_at": ts("20:00:22"), "conclusion": "success"},
                {"name": "Set up Gradle", "started_at": ts("20:00:22"), "completed_at": ts("20:00:42"), "conclusion": "success"},
                {"name": "Run Android unit tests", "started_at": ts("20:00:50"), "completed_at": ts("20:03:30"), "conclusion": "success"},
                {"name": "Post Set up Gradle", "started_at": ts("20:03:45"), "completed_at": ts("20:03:55"), "conclusion": "success"},
            ],
        }

    def runs_page(self, page):
        return self.runs if page == 1 else []

    def jobs(self, run_id):
        return [self.job]


class PhaseObservationTests(unittest.TestCase):
    def test_known_phase_times_and_no_unbounded_log_data(self):
        reader = Reader()
        result = collect_phase_observations(reader)
        self.assertEqual(1, len(result["jobs"]))
        job = result["jobs"][0]
        self.assertEqual("pr_c", job["lane"])
        self.assertEqual(SHA, job["head_sha"])
        self.assertEqual(20, job["queue_seconds"])
        self.assertEqual(220, job["job_seconds"])
        self.assertEqual(20, job["gradle_setup_seconds"])
        self.assertEqual(160, job["android_test_step_seconds"])
        self.assertEqual(10, job["gradle_post_seconds"])
        self.assertEqual(60, job["non_test_step_seconds"])
        self.assertEqual(160, result["metrics_by_lane"]["pr_c"]["median_android_test_step_seconds"])
        self.assertNotIn("SECRET_IN_BRANCH", str(result))
        self.assertNotIn("head_branch", str(result))
        self.assertNotIn("perf/d14-stall", str(result))

    def test_main_group_and_skipped_post_step_are_unknown_not_zero(self):
        reader = Reader()
        reader.runs[0]["event"] = "push"
        reader.runs[0]["head_branch"] = "main"
        reader.runs[0]["pull_requests"] = []
        reader.job["steps"] = [x for x in reader.job["steps"] if x["name"] != "Post Set up Gradle"]
        result = collect_phase_observations(reader)
        job = result["jobs"][0]
        self.assertEqual("main", job["lane"])
        self.assertIsNone(job["gradle_post_seconds"])
        self.assertIsNone(result["metrics_by_lane"]["main"]["median_gradle_post_seconds"])

    def test_incomplete_job_no_fabricated_execution(self):
        reader = Reader()
        reader.job["started_at"] = None
        reader.job["completed_at"] = None
        reader.job["conclusion"] = None
        reader.job["steps"] = []
        result = collect_phase_observations(reader)
        job = result["jobs"][0]
        self.assertIsNone(job["queue_seconds"])
        self.assertIsNone(job["job_seconds"])
        self.assertIsNone(job["android_test_step_seconds"])
        self.assertEqual(0, result["metrics_by_lane"]["pr_c"]["finished_jobs"])

    def test_error_and_leaks_fail_closed(self):
        for mutation in (
            lambda r: r.job.update({"conclusion": "success", "steps": []}),
            lambda r: r.job["steps"].append(dict(r.job["steps"][-1])),
            lambda r: r.job["steps"][2].update({"completed_at": ts("20:05:00")}),
            lambda r: r.job.update({"started_at": ts("20:05:00")}),
            lambda r: r.runs[0].update({"head_sha": "bad"}),
            lambda r: r.runs[0]["head_repository"].update({"full_name": "foreign/repo"}),
        ):
            reader = Reader()
            mutation(reader)
            with self.assertRaises(PhaseError):
                collect_phase_observations(reader)

    def test_reader_failure_redacted_and_duplicate_run_rejected(self):
        reader = Reader()
        reader.jobs = lambda x: (_ for _ in ()).throw(RuntimeError("PRIVATE_TOKEN=verysecret"))
        with self.assertRaises(PhaseError) as e:
            collect_phase_observations(reader)
        self.assertNotIn("verysecret", str(e.exception))
        reader = Reader()
        reader.runs.append(dict(reader.runs[0]))
        with self.assertRaises(PhaseError):
            collect_phase_observations(reader)

    def test_metrics_do_not_fabricate_p90_with_small_sample(self):
        reader = Reader()
        report = collect_phase_observations(reader)
        lane = report["metrics_by_lane"]["pr_c"]
        self.assertIsNone(lane["p90_queue_seconds"])
        self.assertEqual(20, lane["median_queue_seconds"])
        self.assertEqual(1, lane["finished_jobs"])


if __name__ == "__main__":
    unittest.main()

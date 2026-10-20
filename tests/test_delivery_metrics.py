"""DI1 delivery metrics: deterministic, privacy-safe, read-only observations."""
import unittest

from tools.delivery_metrics import DeliveryDataError, summarize


H1 = "a" * 40
H2 = "b" * 40


class DeliveryMetricsTests(unittest.TestCase):
    def test_records_queue_execution_and_pr_lifetime_separately(self):
        payload = {
            "prs": [
                {"number": 8, "created_at": "2026-10-19T16:00:00Z",
                 "merged_at": "2026-10-19T16:15:00Z"},
                {"number": 7, "created_at": "2026-10-19T16:00:00Z",
                 "merged_at": None},
            ],
            "jobs": [
                {"run_id": 20, "pr_number": 8, "head_sha": H1,
                 "created_at": "2026-10-19T16:00:00Z",
                 "started_at": "2026-10-19T16:03:00Z",
                 "completed_at": "2026-10-19T16:08:00Z",
                 "conclusion": "success"},
                {"run_id": 21, "pr_number": 7, "head_sha": H2,
                 "created_at": "2026-10-19T16:00:00Z",
                 "started_at": "2026-10-19T16:01:00Z",
                 "completed_at": "2026-10-19T16:04:00Z",
                 "conclusion": "failure"},
            ],
            "blocks": [{"pr_number": 7, "stage": "sync",
                        "code": "HTTP_403", "details": "SECRET_TOKEN=do-not-echo"}],
        }
        result = summarize(payload)
        self.assertEqual(120, result["metrics"]["median_queue_seconds"])
        self.assertEqual(240, result["metrics"]["median_execution_seconds"])
        self.assertEqual(900, result["prs"][1]["lifetime_seconds"])
        self.assertEqual("permissions", result["blocks"][0]["reason"])
        self.assertNotIn("SECRET_TOKEN", str(result))
        self.assertEqual([7, 8], [p["number"] for p in result["prs"]])

    def test_out_of_order_timestamps_and_invalid_sha_fail_closed(self):
        payload = {"prs": [], "blocks": [], "jobs": [{
            "run_id": 1, "pr_number": 2, "head_sha": "invalid",
            "created_at": "2026-10-19T16:00:00Z",
            "started_at": "2026-10-19T16:03:00Z",
            "completed_at": "2026-10-19T16:02:00Z",
            "conclusion": "success"}]}
        with self.assertRaises(DeliveryDataError):
            summarize(payload)
        payload["jobs"][0]["head_sha"] = H1
        with self.assertRaises(DeliveryDataError):
            summarize(payload)

    def test_incomplete_runs_do_not_fabricate_duration(self):
        p = {"prs": [], "blocks": [], "jobs": [{
            "run_id": 3, "pr_number": None, "head_sha": H1,
            "created_at": "2026-10-19T16:00:00Z",
            "started_at": None, "completed_at": None,
            "conclusion": None}]}
        x = summarize(p)
        self.assertIsNone(x["jobs"][0]["queue_seconds"])
        self.assertIsNone(x["jobs"][0]["execution_seconds"])
        self.assertIsNone(x["metrics"]["median_execution_seconds"])

    def test_rejects_unknown_stage_and_duplicate_run_record(self):
        p = {"prs": [], "jobs": [], "blocks": [{
            "pr_number": 2, "stage": "arbitrary", "code": "UNKNOWN"}]}
        with self.assertRaises(DeliveryDataError):
            summarize(p)
        p["blocks"] = []
        x = {"run_id": 2, "pr_number": 2, "head_sha": H1,
             "created_at": "2026-10-19T16:00:00Z",
             "started_at": None, "completed_at": None,
             "conclusion": None}
        p["jobs"] = [x, dict(x)]
        with self.assertRaises(DeliveryDataError):
            summarize(p)


    def test_lane_pressure_is_aggregated_from_explicit_bounded_labels(self):
        p = {"prs": [], "blocks": [], "jobs": [
            {"run_id": 11, "pr_number": 4, "head_sha": H1, "lane": "pr_a",
             "created_at": "2026-10-20T08:00:00Z",
             "started_at": "2026-10-20T08:01:00Z",
             "completed_at": "2026-10-20T08:04:00Z",
             "conclusion": "success"},
            {"run_id": 12, "pr_number": 5, "head_sha": H2, "lane": "pr_b",
             "created_at": "2026-10-20T08:00:00Z",
             "started_at": "2026-10-20T08:03:00Z",
             "completed_at": "2026-10-20T08:10:00Z",
             "conclusion": "success"},
            {"run_id": 13, "pr_number": None, "head_sha": H2, "lane": "main",
             "created_at": "2026-10-20T08:05:00Z",
             "started_at": "2026-10-20T08:05:30Z",
             "completed_at": "2026-10-20T08:07:00Z",
             "conclusion": "success"},
            {"run_id": 14, "pr_number": 6, "head_sha": H1, "lane": "pr_b",
             "created_at": "2026-10-20T08:09:00Z",
             "started_at": None, "completed_at": None,
             "conclusion": None},
        ]}
        out = summarize(p)
        self.assertEqual(2, out["metrics_by_lane"]["pr_b"]["jobs"])
        self.assertEqual(1, out["metrics_by_lane"]["pr_b"]["finished_jobs"])
        self.assertEqual(180, out["metrics_by_lane"]["pr_b"]["median_queue_seconds"])
        self.assertEqual(420, out["metrics_by_lane"]["pr_b"]["median_execution_seconds"])
        self.assertEqual(60, out["metrics_by_lane"]["pr_a"]["median_queue_seconds"])
        self.assertEqual(30, out["metrics_by_lane"]["main"]["median_queue_seconds"])
        self.assertIsNone(out["jobs"][3]["queue_seconds"])

    def test_lane_label_must_be_allowlisted_and_missing_is_unknown(self):
        job = {"run_id": 11, "pr_number": None, "head_sha": H1,
               "created_at": "2026-10-20T08:00:00Z",
               "started_at": None, "completed_at": None, "conclusion": None}
        p = {"prs": [], "blocks": [], "jobs": [job]}
        self.assertEqual('unknown', summarize(p)["jobs"][0]["lane"])
        job["lane"] = "secret=raw/branch"
        with self.assertRaises(DeliveryDataError):
            summarize(p)

if __name__ == "__main__":
    unittest.main()

"""Proposed fourth Android PR lane: performance isolation without gate weakening.

This is a draft-only candidate. Its running behavior must be evaluated after
a separately authorized protected merge, never inferred from static routing.
"""
from pathlib import Path
import unittest

from tools.delivery_ci_observations import _lane_for_run
from tools.delivery_metrics import summarize

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / ".github/workflows/check.yml"


class AndroidFourthLaneCandidateContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = CHECK.read_text(encoding="utf-8")

    def test_all_four_pr_lane_labels_and_no_main_collision(self):
        s = self.workflow
        for lane in ("a", "b", "c", "d"):
            self.assertIn("'bitchord-android-pr-lane-" + lane + "'", s)
        self.assertIn("startsWith(github.head_ref, 'perf/')", s)
        self.assertIn("format('bitchord-android-main-{0}', github.sha)", s)
        self.assertIn("      queue: max\n      cancel-in-progress: false", s)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", s)

    def test_required_android_suite_events_permissions_and_timeout_unchanged(self):
        s = self.workflow
        self.assertIn("name: Android CI", s)
        self.assertIn("    name: Android · test and build", s)
        self.assertIn("    branches: [main]", s)
        self.assertIn("  pull_request:", s)
        self.assertIn("  contents: read", s)
        self.assertNotIn("contents: write", s)
        self.assertIn("    timeout-minutes: 45", s)
        self.assertIn("run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest", s)
        self.assertNotIn("continue-on-error: true", s)
        self.assertNotIn("  paths:", s)
        self.assertNotIn(":app:assembleDevDebug", s)

    def test_read_only_ci_observations_and_metrics_agree_with_new_route(self):
        cases = {
            "catalog/c18-rating": "pr_a",
            "intelligence/l18-consumer": "pr_a",
            "playback/b18-verified": "pr_b",
            "perf/d15-observability": "pr_d",
            "infra/android-ci-four-lane-candidate-v0": "pr_c",
            "acquisition/a0": "pr_c",
        }
        for branch, lane in cases.items():
            with self.subTest(branch=branch):
                self.assertEqual(lane, _lane_for_run("pull_request", branch))
        self.assertEqual("main", _lane_for_run("push", "main"))
        self.assertEqual("unknown", _lane_for_run("pull_request", None))

        payload = {"prs": [], "blocks": [], "jobs": [{
            "run_id": 400, "pr_number": 144, "lane": "pr_d",
            "head_sha": "a" * 40, "conclusion": "success",
            "created_at": "2026-10-21T19:00:00Z",
            "started_at": "2026-10-21T19:02:00Z",
            "completed_at": "2026-10-21T19:06:00Z",
        }]}
        out = summarize(payload)
        self.assertEqual("pr_d", out["jobs"][0]["lane"])
        self.assertEqual(120, out["metrics_by_lane"]["pr_d"]["median_queue_seconds"])
        self.assertEqual(240, out["metrics_by_lane"]["pr_d"]["median_execution_seconds"])


if __name__ == "__main__":
    unittest.main()

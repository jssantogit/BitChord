"""Android CI lane policy regression: deterministic routing, no gate weakening.

Only static workflow/API contracts. Live runner behavior and throughput require
a separately authorized on-main pilot and exact head CI evidence.
"""
import pathlib
import unittest

from tools.delivery_ci_observations import _lane_for_run

ROOT = pathlib.Path(__file__).resolve().parents[1]
CHECK = ROOT / ".github/workflows/check.yml"


class AndroidLanePilotRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = CHECK.read_text(encoding="utf-8")

    def test_existing_three_pr_groups_and_main_sha_isolation_remain(self):
        source = self.workflow
        self.assertIn("'bitchord-android-pr-lane-a'", source)
        self.assertIn("'bitchord-android-pr-lane-b'", source)
        self.assertIn("'bitchord-android-pr-lane-c'", source)
        self.assertIn("startsWith(github.head_ref, 'catalog/')", source)
        self.assertIn("startsWith(github.head_ref, 'intelligence/')", source)
        self.assertIn("startsWith(github.head_ref, 'playback/')", source)
        self.assertIn("format('bitchord-android-main-{0}', github.sha)", source)
        self.assertIn("      queue: max\n      cancel-in-progress: false", source)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", source)

    def test_first_party_metrics_labels_match_workflow_lane_policy(self):
        cases = {
            "catalog/c13-provider": "pr_a",
            "intelligence/l13-smart": "pr_a",
            "playback/b13-output": "pr_b",
            "perf/d10-diagnostics": "pr_d",
            "infra/queue-overlap": "pr_c",
            "acquisition/a0": "pr_c",
            "other/external": "pr_c",
        }
        for ref, expected in cases.items():
            with self.subTest(ref=ref):
                self.assertEqual(expected, _lane_for_run("pull_request", ref))
        self.assertEqual("main", _lane_for_run("push", "main"))
        self.assertEqual("unknown", _lane_for_run("push", "feature"))
        self.assertEqual("unknown", _lane_for_run("pull_request", None))

    def test_required_check_suite_and_protected_main_unchanged(self):
        source = self.workflow
        self.assertIn("    name: Android · test and build", source)
        self.assertIn("run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest", source)
        self.assertIn("    branches: [main]", source)
        self.assertIn("    runs-on: ubuntu-latest", source)
        self.assertIn("    timeout-minutes: 45", source)
        self.assertNotIn("continue-on-error: true", source)
        self.assertNotIn("fail-fast: false", source)


if __name__ == "__main__":
    unittest.main()

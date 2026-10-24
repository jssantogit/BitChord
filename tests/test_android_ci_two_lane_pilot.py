"""Regression tests for bounded CI lanes, including three-lane migration.

The required check and test scope must not change. These tests protect our
routing contract; observable scheduler behavior must still be tested on GitHub.
"""
import pathlib
import unittest

CHECK = pathlib.Path(__file__).resolve().parents[1] / ".github/workflows/check.yml"


def lane_for_pr_branch(branch: str) -> str:
    """Independent static policy oracle matching the current three-lane routing."""
    if branch.startswith(("catalog/", "intelligence/")):
        return "a"
    return "b" if branch.startswith("playback/") else "c"


class AndroidLanePolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = CHECK.read_text(encoding="utf-8")

    def test_three_stable_pr_groups_with_main_outside(self):
        yaml = self.workflow
        self.assertIn("'bitchord-android-pr-lane-a'", yaml)
        self.assertIn("'bitchord-android-pr-lane-b'", yaml)
        self.assertIn("'bitchord-android-pr-lane-c'", yaml)
        self.assertIn("startsWith(github.head_ref, 'catalog/')", yaml)
        self.assertIn("startsWith(github.head_ref, 'intelligence/')", yaml)
        self.assertIn("github.event_name == 'pull_request'", yaml)
        self.assertIn("format('bitchord-android-main-{0}', github.sha)", yaml)
        self.assertNotIn("'bitchord-android-pr-queue'", yaml)

    def test_no_job_loss_or_cross_pr_cancel(self):
        yaml = self.workflow
        self.assertIn("      queue: max\n      cancel-in-progress: false", yaml)
        self.assertIn(
            "cancel-in-progress: ${{ github.event_name == 'pull_request' }}",
            yaml,
        )
        self.assertIn("    name: Android · test and build", yaml)
        self.assertRegex(
            yaml,
            r"run: \./gradlew (?:--build-cache )?-Pbitchord\.bundleWear=false :app:testDevDebugUnitTest",
        )

    def test_routing_is_deterministic_for_each_workstream(self):
        cases = {
            "catalog/c7-canonical": "a",
            "intelligence/l8-integrity": "a",
            "playback/b8-output": "b",
            "perf/d5-logs": "c",
            "acquisition/a0-on-device": "c",
            "infra/workflow-permissions-safety-v0": "c",
            "external/manual-branch": "c",
        }
        for branch, lane in cases.items():
            with self.subTest(branch=branch):
                self.assertEqual(lane_for_pr_branch(branch), lane)


    def test_migration_requires_three_lane_and_matching_first_party_observation(self):
        from tools.delivery_ci_observations import _lane_for_run
        self.assertIn("'bitchord-android-pr-lane-c'", self.workflow)
        self.assertEqual('pr_b', _lane_for_run('pull_request', 'playback/b13'))
        self.assertEqual('pr_c', _lane_for_run('pull_request', 'perf/d10'))


if __name__ == "__main__":
    unittest.main()

"""Contract checks for the required Android check and PR serialization.

Only the runner-facing, low-level YAML contract is asserted. The GitHub Actions
scheduler behavior also needs validation on real PR runs after merge.
"""
import pathlib
import unittest

CHECK = pathlib.Path(__file__).resolve().parents[1] / '.github/workflows/check.yml'


class AndroidCIQueueContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = CHECK.read_text(encoding='utf-8')

    def test_required_check_and_events_remain_present(self):
        self.assertIn('name: Android · test and build', self.workflow)
        self.assertIn('  pull_request:', self.workflow)
        self.assertIn('    branches: [main]', self.workflow)
        self.assertIn('run: ./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest', self.workflow)
        self.assertNotIn(':app:assembleDevDebug', self.workflow)
        self.assertNotIn('\n  paths:', self.workflow)

    def test_obsolete_pr_runs_cancel_but_main_runs_do_not(self):
        self.assertIn("group: android-ci-${{ github.workflow }}-${{ github.event_name == 'pull_request' && github.ref || github.sha }}", self.workflow)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", self.workflow)

    def test_all_active_pr_heads_use_one_lossless_job_queue(self):
        self.assertIn("group: ${{ github.event_name == 'pull_request' && 'bitchord-android-pr-queue' || format('bitchord-android-main-{0}', github.sha) }}", self.workflow)
        self.assertIn('      queue: max\n      cancel-in-progress: false', self.workflow)

    def test_main_is_independent_of_pr_lane(self):
        self.assertIn("format('bitchord-android-main-{0}', github.sha)", self.workflow)
        self.assertIn('    name: Android · test and build', self.workflow)


if __name__ == '__main__':
    unittest.main()

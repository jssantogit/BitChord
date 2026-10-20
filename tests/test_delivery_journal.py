"""DI4a: fail-closed, local checkpoint contract."""
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import unittest

from tools.delivery_journal import JournalError, initialize, advance, load, reconcile_checkpoint

SHA = 'a' * 40
NEXT = 'b' * 40

def proposal():
    return {'version': 1, 'repository': 'jssantogit/BitChord', 'target': 'main',
            'items': [{'number': 90, 'expected_head_sha': NEXT,
                       'expected_base_ref': 'main', 'depends_on': None,
                       'allowed_files': ['tools/delivery_journal.py']}]}

class JournalTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'journal.json'

    def begin(self):
        return initialize(self.path, proposal(), SHA, authorized=False)

    def test_unapproved_proposal_never_grants_execution_authority(self):
        state = self.begin()
        self.assertEqual(0, state['revision'])
        self.assertFalse(state['executable'])
        self.assertTrue(state['approval_required'])
        self.assertEqual('planned', state['items'][0]['stage'])
        with self.assertRaises(JournalError):
            advance(self.path, 0, 90, 'merge_intent', expected_head=NEXT)

    def test_monotonic_transitions_with_compare_and_swap_revision(self):
        self.begin()
        one = advance(self.path, 0, 90, 'preflight_green', expected_head=NEXT)
        self.assertEqual(1, one['revision'])
        with self.assertRaises(JournalError):
            advance(self.path, 0, 90, 'preflight_green', expected_head=NEXT)
        with self.assertRaises(JournalError):
            advance(self.path, 1, 90, 'post_ci_green', expected_head=NEXT)
        self.assertEqual('preflight_green', load(self.path)['items'][0]['stage'])

    def test_detect_corrupt_or_tampered_journal(self):
        self.begin()
        data = json.loads(self.path.read_text())
        data['items'][0]['expected_head_sha'] = 'f' * 40
        self.path.write_text(json.dumps(data))
        with self.assertRaises(JournalError):
            load(self.path)

    def test_atomic_checkpoint_survives_restart_and_rejects_different_batch(self):
        first = self.begin()
        self.assertEqual(first, initialize(self.path, proposal(), SHA, authorized=False))
        changed = proposal()
        changed['items'][0]['allowed_files'].append('unexpected.py')
        with self.assertRaises(JournalError):
            initialize(self.path, changed, SHA, authorized=False)
        with self.assertRaises(JournalError):
            initialize(self.path, proposal(), 'e' * 40, authorized=False)

    def test_parallel_cas_only_one_revision_can_win(self):
        self.begin()
        def attempt(_):
            try:
                advance(self.path, 0, 90, 'preflight_green', expected_head=NEXT)
                return 'won'
            except JournalError:
                return 'blocked'
        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = sorted(pool.map(attempt, [0, 1]))
        self.assertEqual(['blocked', 'won'], outcomes)
        self.assertEqual(1, load(self.path)['revision'])

    def test_ambiguous_merge_never_replays_mutation(self):
        self.begin()
        step = advance(self.path, 0, 90, 'preflight_green', expected_head=NEXT)
        check = reconcile_checkpoint(step, 90, observed_main=SHA,
                                     observed_pr_merged=False, observed_merge_sha=None)
        self.assertEqual('revalidate_before_merge', check['decision'])
        self.assertFalse(check['execute'])

    def test_rejects_symlink_journal(self):
        outside = Path(self.tmp.name) / 'outside.json'
        outside.write_text('secret')
        self.path.symlink_to(outside)
        with self.assertRaises(JournalError):
            self.begin()

if __name__ == '__main__':
    unittest.main()

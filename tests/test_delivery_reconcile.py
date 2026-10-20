"""DI4b: read-only checkpoint reconciliation against live observations."""
import tempfile
from pathlib import Path
import unittest
from tools.delivery_journal import initialize
from tools.delivery_reconcile import reconcile_live

MAIN = 'a' * 40
HEAD = 'b' * 40

def manifest():
    return {'version': 1, 'repository': 'jssantogit/BitChord', 'target': 'main',
            'items': [{'number': 90, 'expected_head_sha': HEAD,
                       'expected_base_ref': 'main', 'depends_on': None,
                       'allowed_files': ['src/a.kt']}]}

class Reader:
    repository = 'jssantogit/BitChord'
    def __init__(self):
        self.main = MAIN
        self.data = {'head_sha': HEAD, 'base_ref': 'main', 'base_sha': MAIN,
                     'draft': False, 'merged': False, 'state': 'open',
                     'mergeable': 'clean', 'head_repo': self.repository}
        self.paths = ['src/a.kt']
        self.status = 'success'
    def main_sha(self):
        return self.main
    def pr(self, number):
        return dict(self.data)
    def files(self, number):
        return list(self.paths)
    def check(self, sha):
        return self.status
    def merge(self, *args):
        raise AssertionError('reconciler must not merge')

class ReconcileTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.snapshot = initialize(Path(self.tmp.name) / 'journal.json',
                                   manifest(), MAIN, authorized=False)

    def test_clean_is_revalidation_not_authorization(self):
        report = reconcile_live(self.snapshot, manifest(), Reader())
        self.assertEqual('preflight_revalidation_required', report['results'][0]['decision'])
        self.assertTrue(report['approval_required'])
        self.assertFalse(report['executable'])
        self.assertFalse(report['results'][0]['replay_merge'])

    def test_merged_or_main_drift_requires_manual_review(self):
        r = Reader()
        r.data['state'], r.data['merged'] = 'closed', True
        result = reconcile_live(self.snapshot, manifest(), r)
        self.assertEqual('manual_postmerge_review', result['results'][0]['decision'])
        r = Reader()
        r.main_sha = lambda: 'e' * 40
        result = reconcile_live(self.snapshot, manifest(), r)
        self.assertTrue(result['main_drift'])
        self.assertEqual('halt_main_drift', result['results'][0]['decision'])

    def test_head_files_ci_draft_foreign_repo_and_base_fail_closed(self):
        for key, value in [('head_sha','f' * 40), ('draft', True),
                           ('head_repo','untrusted/repo'), ('base_ref','unexpected')]:
            r = Reader()
            r.data[key] = value
            with self.subTest(key=key):
                self.assertEqual('blocked', reconcile_live(
                    self.snapshot, manifest(), r)['results'][0]['decision'])
        r = Reader()
        r.paths = ['unknown.py']
        self.assertEqual('blocked', reconcile_live(self.snapshot, manifest(), r)['results'][0]['decision'])
        r = Reader()
        r.status = 'failure'
        self.assertEqual('blocked', reconcile_live(self.snapshot, manifest(), r)['results'][0]['decision'])

    def test_private_read_error_is_redacted(self):
        class Broken(Reader):
            def pr(self, n):
                raise RuntimeError('SECRET-DO-NOT-DISCLOSE')
        result = reconcile_live(self.snapshot, manifest(), Broken())
        self.assertEqual('blocked', result['results'][0]['decision'])
        self.assertNotIn('SECRET-', str(result))

    def test_manifest_swap_is_rejected(self):
        alternate = manifest()
        alternate['items'][0]['allowed_files'] = ['unreviewed.py']
        with self.assertRaises(ValueError):
            reconcile_live(self.snapshot, alternate, Reader())

if __name__ == '__main__':
    unittest.main()

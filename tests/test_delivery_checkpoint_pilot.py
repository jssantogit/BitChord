"""DI4e RED: generate read-only checkpoint bytes with no merge authority."""
import json
from pathlib import Path
import tempfile
import unittest
from tools.delivery_checkpoint_pilot import PilotError, prepare_evidence

MAIN = 'a' * 40
REPO = 'jssantogit/BitChord'
MANIFEST = {
    'version': 1, 'repository': REPO, 'target': 'main',
    'items': [{'number': 90, 'expected_head_sha': 'b' * 40,
               'expected_base_ref': 'main', 'depends_on': None,
               'allowed_files': ['tools/delivery_journal.py']}]
}

class PilotTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name)
        self.path = self.folder / 'delivery-checkpoint.json'

    def export(self, **changes):
        kw = dict(manifest=MANIFEST, output=self.path, main_sha=MAIN,
                  run_id=8123, attempt=1, event='workflow_dispatch',
                  ref='refs/heads/main')
        kw.update(changes)
        return prepare_evidence(**kw)

    def test_export_is_private_and_pins_non_executable_provenance(self):
        result = self.export()
        data = json.loads(self.path.read_bytes())
        self.assertEqual('a' * 40, data['source']['main_sha'])
        self.assertEqual(8123, data['source']['run_id'])
        self.assertTrue(data['journal']['approval_required'])
        self.assertFalse(data['journal']['executable'])
        self.assertFalse(result['executable'])
        self.assertEqual(64, len(result['sha256']))
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)

    def test_atomic_export_rejects_existing_file_and_symlinks(self):
        self.export()
        with self.assertRaises(PilotError):
            self.export()
        self.path.unlink()
        self.path.symlink_to(self.folder / 'sensitive')
        with self.assertRaises(PilotError):
            self.export()

    def test_fake_pr_or_wrong_ref_never_publishes(self):
        for change in ({'event':'pull_request'}, {'ref':'refs/heads/feature'},
                       {'main_sha':'invalid'}, {'attempt':True}):
            with self.subTest(change=change), self.assertRaises(PilotError):
                self.export(**change)
            self.assertFalse(self.path.exists())

    def test_cannot_promote_manifest_to_approval(self):
        unsafe = dict(MANIFEST)
        unsafe['repository'] = 'evil/repo'
        with self.assertRaises(PilotError):
            self.export(manifest=unsafe)
        self.assertFalse(self.path.exists())

    def test_workflow_is_manual_and_separates_publisher_and_reader(self):
        text = (Path(__file__).resolve().parents[1] /
                '.github/workflows/delivery-checkpoint-evidence.yml').read_text()
        self.assertIn('workflow_dispatch:', text)
        self.assertIn('mode: export', text)
        self.assertIn('mode: inspect', text)
        self.assertIn('actions/upload-artifact@v4', text)
        self.assertIn('actions/download-artifact@v4', text)
        self.assertIn('retention-days: 1', text)
        self.assertIn('permissions:', text)
        self.assertIn('github.ref == \'refs/heads/main\'', text)
        for forbidden in ('\n  push:', '\n  schedule:', '\n  pull_request:',
                          'permission-contents: write', 'INTEGRATION_APP_TOKEN',
                          'integration-apply'):
            self.assertNotIn(forbidden, text)

if __name__ == '__main__':
    unittest.main()

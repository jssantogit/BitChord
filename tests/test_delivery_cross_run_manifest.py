"""DI4d/e: exact pre-approved manifests; records are not merge permission."""
import json
from pathlib import Path
import unittest
from tools.integration_queue import validate_manifest, MAX_ITEMS

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.github/integration-batches/infra-di4-cross-run.json'

class DI4CrossRunBatchTests(unittest.TestCase):
    def test_reviewed_head_base_and_minimum_diff_scope(self):
        manifest = json.loads(SOURCE.read_text('utf-8'))
        rows = validate_manifest(manifest, 'jssantogit/BitChord')
        self.assertEqual(3, MAX_ITEMS)
        self.assertEqual([97, 98], [r['number'] for r in rows])
        self.assertEqual([None, 97], [r['depends_on'] for r in rows])
        self.assertEqual(['main', 'infra/di4d-cross-run-artifact-verification-v0'],
                         [r['expected_base_ref'] for r in rows])
        self.assertEqual(['cfeb250e89445bc8500d73ff0cbc52015abb047f',
                          '78c0e847b3b25ed6830ef553814a7e5c848f9be9'],
                         [r['expected_head_sha'] for r in rows])
        self.assertEqual([
            ['docs/delivery-di4d-artifact.md', 'tests/test_delivery_artifact.py',
             'tools/delivery_artifact.py'],
            ['docs/delivery-di4e-pilot.md', 'tests/test_delivery_checkpoint_pilot.py',
             'tools/delivery_checkpoint_pilot.py']
        ], [r['allowed_files'] for r in rows])

    def test_no_workflow_writes_or_functional_prs(self):
        rows = validate_manifest(json.loads(SOURCE.read_text('utf-8')),
                                 'jssantogit/BitChord')
        self.assertFalse(any(n in (99, 49) or n < 90 for n in
                             (r['number'] for r in rows)))
        self.assertFalse(any(p.startswith('.github/workflows/') for r in rows
                             for p in r['allowed_files']))

if __name__ == '__main__':
    unittest.main()

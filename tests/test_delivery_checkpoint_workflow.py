"""DI4e installer: manual only, least privilege; no integration write path."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/delivery-checkpoint-evidence.yml'

class ManualEvidenceWorkflowTests(unittest.TestCase):
    def test_export_and_inspect_are_manual_and_main_only(self):
        data = WORKFLOW.read_text('utf-8')
        self.assertIn('  workflow_dispatch:', data)
        self.assertIn('          - export', data)
        self.assertIn('          - inspect', data)
        self.assertEqual(2, data.count("github.ref == 'refs/heads/main'"))
        for forbidden in ('\n  push:', '\n  pull_request:', '\n  schedule:',
                          '\n  workflow_run:', 'permission-contents: write',
                          'INTEGRATION_APP_TOKEN', 'integration-apply',
                          'tools/integration_queue.py'):
            self.assertNotIn(forbidden, data)

    def test_retention_and_identity_are_bounded(self):
        data = WORKFLOW.read_text('utf-8')
        self.assertIn('actions/upload-artifact@v4', data)
        self.assertIn('actions/download-artifact@v4', data)
        self.assertIn('python3 -m tools.delivery_checkpoint_pilot', data)
        self.assertIn('python3 -m tools.delivery_artifact', data)
        self.assertIn('retention-days: 1', data)
        self.assertIn('name: di4-checkpoint-evidence', data)
        self.assertIn('run-id: ${{ inputs.source_run_id }}', data)
        self.assertIn('--sha256 "$EVIDENCE_SHA256"', data)
        self.assertIn('--expected-main-sha "$EXPECTED_MAIN"', data)
        self.assertIn('contents: read', data)
        self.assertIn('actions: read', data)
        self.assertNotIn('contents: write', data)

if __name__ == '__main__':
    unittest.main()

"""Queue bootstrap: dynamic protected-main manifest lookup and future DI CI paths."""
from pathlib import Path
import unittest
from tools.integration_queue import validate_manifest, StopIntegration

ROOT = Path(__file__).resolve().parents[1]
WF = ROOT / ".github/workflows/integration-queue.yml"
PY = ROOT / ".github/workflows/integration-queue-tests.yml"

class DynamicBatchBootstrapTests(unittest.TestCase):
    def test_bootstrap_is_manifest_agnostic(self):
        workflow = WF.read_text(encoding="utf-8")
        self.assertIn("type: string", workflow.split("      batch:", 1)[1].split("      mode:", 1)[0])
        self.assertNotIn("infra-di4-foundations", workflow)
        self.assertNotIn("infra-delivery-reliability", workflow)

    def test_dispatch_accepts_name_of_reviewed_manifest_without_choice_bootstrap(self):
        value = WF.read_text(encoding="utf-8")
        section = value.split("      batch:", 1)[1].split("      mode:", 1)[0]
        self.assertIn("type: string", section)
        self.assertNotIn("type: choice", section)
        self.assertNotIn("options:", section)
        self.assertIn("environment: integration-apply", value)
        self.assertIn("group: bitchord-serial-integration", value)
        self.assertIn("cancel-in-progress: false", value)
        self.assertNotIn("\n  schedule:", value)
        self.assertNotIn("\n  pull_request:", value)
        self.assertNotIn("\n  push:", value)

    def test_python_ci_discovers_future_delivery_slices_without_workflow_edits(self):
        content = PY.read_text(encoding="utf-8")
        self.assertEqual(2, content.count("      - 'tools/delivery_*.py'"))
        self.assertEqual(2, content.count("      - 'tests/test_delivery_*.py'"))
        self.assertEqual(2, content.count("      - 'tests/test_workflow_sync_guard.py'"))
        self.assertIn("python -m unittest discover -s tests -v", content)
        self.assertIn("name: Integration queue · contract tests", content)

    def test_arbitrary_dispatch_cannot_be_approved_without_valid_reviewed_manifest(self):
        empty = {'version': 1, 'repository': 'jssantogit/BitChord',
                 'target': 'main', 'items': []}
        with self.assertRaises(StopIntegration):
            validate_manifest(empty, 'jssantogit/BitChord')

if __name__ == "__main__":
    unittest.main()

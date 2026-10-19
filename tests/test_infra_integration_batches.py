"""Validate the two owner-authorized infrastructure queue batch manifests.

This is static preflight only; exact live PR SHA/diff/CI checks are made by
the protected queue's plan and execute modes after installation on main.
"""
import json
from pathlib import Path
import unittest
from tools.integration_queue import validate_manifest

ROOT = Path(__file__).resolve().parents[1]
BATCHES = ROOT / ".github" / "integration-batches"
QUEUE = ROOT / ".github" / "workflows" / "integration-queue.yml"
EXPECTED = {"infra-workflows-1": [82, 84, 83], "infra-workflows-2": [85]}


class InfrastructureBatchTests(unittest.TestCase):
    def test_explicit_owner_approved_batches_are_pinned_and_lossless(self):
        all_prs = []
        for name, numbers in EXPECTED.items():
            with self.subTest(batch=name):
                manifest = json.loads((BATCHES / (name + ".json")).read_text())
                items = validate_manifest(manifest, "jssantogit/BitChord")
                self.assertEqual(numbers, [x["number"] for x in items])
                self.assertTrue(all(x["expected_base_ref"] == "main" for x in items))
                self.assertTrue(all(x["depends_on"] is None for x in items))
                all_prs.extend(x["number"] for x in items)
        self.assertEqual(len(all_prs), len(set(all_prs)))

    def test_choices_expose_only_the_two_new_explicit_batch_names(self):
        workflow = QUEUE.read_text(encoding="utf-8")
        for name in EXPECTED:
            self.assertIn("          - " + name, workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("environment: integration-apply", workflow)
        self.assertIn("group: bitchord-serial-integration", workflow)
        self.assertNotIn("\n  pull_request:", workflow)
        self.assertNotIn("\n  schedule:", workflow)


if __name__ == "__main__":
    unittest.main()

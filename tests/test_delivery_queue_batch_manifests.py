"""Static contract for infrastructure manifests; NOT merge authorization.

The queue's live plan and execute recheck every pinned ref and status.
"""
import json
from pathlib import Path
import unittest
from tools.integration_queue import MAX_ITEMS, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
FOLDER = ROOT / ".github/integration-batches"
EXPECTED = {
    "infra-di4-foundations": [(90, None, "main"), (91, 90, "infra/di4-checkpoint-journal-v0"),
                              (95, 91, "infra/di4-readonly-reconciler-v0")],
    "infra-delivery-reliability": [(92, None, "main"), (93, None, "main")],
}
FILES = {
    90: ["docs/delivery-di4a-journal.md", "tests/test_delivery_journal.py", "tools/delivery_journal.py"],
    91: ["docs/delivery-di4b-reconcile.md", "tests/test_delivery_reconcile.py", "tools/delivery_reconcile.py"],
    95: ["docs/delivery-di4c-transfer.md", "tests/test_delivery_preflight.py", "tests/test_delivery_transfer.py", "tools/delivery_transfer.py"],
    92: ["docs/delivery-di5-ci-observations.md", "tests/test_delivery_ci_observations.py", "tools/delivery_ci_observations.py"],
    93: ["docs/delivery-di6-workflow-sync-guard.md", "tests/test_workflow_sync_guard.py", "tools/integration_queue.py"],
}
HEADS = {
    90: "e1546aa6b6897bb2a2425172ef0c22833a0c555c",
    91: "d15c316007418f734fe6b2af455d44cc24797999",
    95: "1c7b63b032bdb479816c7e8cb31b7e966e603d87",
    92: "be47e330bfddf04ae5eef20517c8724c090cf1d6",
    93: "6837786430a30b1e702fed32bb3a1f52f94f3136",
}

class NewInfraManifestTests(unittest.TestCase):
    def test_staged_manifests_are_exact_and_contain_no_functional_prs(self):
        self.assertEqual(3, MAX_ITEMS)
        all_seen = set()
        for slug, expected in EXPECTED.items():
            with self.subTest(slug=slug):
                rows = validate_manifest(
                    json.loads((FOLDER / (slug + ".json")).read_text()),
                    "jssantogit/BitChord")
                self.assertLessEqual(len(rows), MAX_ITEMS)
                self.assertEqual(expected, [
                    (row["number"], row["depends_on"], row["expected_base_ref"])
                    for row in rows])
                for row in rows:
                    n = row["number"]
                    self.assertGreaterEqual(n, 90)
                    self.assertNotIn(n, all_seen)
                    all_seen.add(n)
                    self.assertEqual(HEADS[n], row["expected_head_sha"])
                    self.assertEqual(FILES[n], row["allowed_files"])
        self.assertEqual({90, 91, 95, 92, 93}, all_seen)

if __name__ == "__main__":
    unittest.main()

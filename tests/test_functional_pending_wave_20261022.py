"""Static, read-only safety gate for the first 2026-10-22 functional batch.

The workflow/executor independently revalidates these pins against live
GitHub. This contract is NOT permission to ready or merge any draft PR.
"""
import json
from pathlib import Path
import unittest

from tools.integration_queue import validate_manifest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / ".github/integration-batches/functional-pending-20261022-wave-01.json"
EXPECTED = {
    138: ("15f7f5058f66e723b0d869b5505be0c829b56b91", 2),
    139: ("32ddc678ecd6fd9f1fa53446a8342dfe2a4e547d", 5),
    141: ("10d2e43137b3317b7e75212e91d45bee29154268", 16),
}


class FunctionalBatchGate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    def test_pinned_exactly_three_unmerged_live_sha_snapshots(self):
        records = validate_manifest(cls_manifest := self.manifest, "jssantogit/BitChord")
        self.assertEqual([138, 139, 141], [r["number"] for r in records])
        self.assertEqual(3, len(records))
        for item in records:
            sha, file_count = EXPECTED[item["number"]]
            self.assertEqual(sha, item["expected_head_sha"])
            self.assertEqual(file_count, len(item["allowed_files"]))
            self.assertEqual("main", item["expected_base_ref"])
            self.assertIsNone(item["depends_on"])

    def test_diff_file_ownership_and_no_shared_workflow_paths(self):
        items = self.manifest["items"]
        all_paths = [f for item in items for f in item["allowed_files"]]
        self.assertEqual(len(all_paths), len(set(all_paths)))
        self.assertFalse(any(p.startswith(".github/workflows/") for p in all_paths))
        diagnostic = items[0]["allowed_files"]
        self.assertTrue(all("/diagnostics/" in f for f in diagnostic))
        listening = items[1]["allowed_files"]
        self.assertTrue(all("/ui/screens/" in f or
            f.startswith("sharedUi/src/commonMain/composeResources/") for f in listening))
        catalog = items[2]["allowed_files"]
        self.assertTrue(all("/data/catalog/" in f or
            f.startswith("app/src/test/java/com/music/bitchord/") for f in catalog))
        self.assertFalse(any(f.startswith(".github/integration-batches/") for f in all_paths))

    def test_queue_is_review_only_and_does_not_change_protected_gate(self):
        doc = (ROOT / "docs/functional-wave-20261022-preflight.md").read_text(encoding="utf-8")
        self.assertIn("DRAFT", doc)
        self.assertIn("NO MERGE", doc)
        self.assertIn("QUEUE_METRIC", doc)
        self.assertIn("postmain_ci", doc)
        self.assertIn("expected_main_sha", doc)
        workflow = (ROOT / ".github/workflows/integration-queue.yml").read_text(encoding="utf-8")
        self.assertIn("integration-apply", workflow)
        self.assertIn("--mode execute", workflow)
        self.assertIn("--mode plan", workflow)
        self.assertIn("integration-apply", workflow)
        android = (ROOT / ".github/workflows/check.yml").read_text(encoding="utf-8")
        self.assertIn("name: Android · test and build", android)
        self.assertIn("queue: max", android)
        self.assertIn("run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest", android)


if __name__ == "__main__":
    unittest.main()

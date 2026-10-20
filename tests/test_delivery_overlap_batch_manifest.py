"""Static checks for the staged, NOT YET AUTHORIZED infrastructure batch.

Publishing this manifest or making its tests GREEN is never permission
to switch a PR out of draft, merge anything or dispatch protected execute.
"""
import copy
import json
from pathlib import Path
import unittest

from tools.integration_queue import MAX_ITEMS, StopIntegration, validate_manifest


ROOT = Path(__file__).resolve().parents[1]
NAME = "infra-queue-overlap-wave-01"
MANIFEST = ROOT / ".github/integration-batches" / (NAME + ".json")
REPO = "jssantogit/BitChord"

EXPECTED = [
    (120, "a7b670ecb5be3e5fde87b6db6eb093eddf8627f9",
     "main", None, {
         "docs/integration-queue.md",
         "tests/test_integration_queue.py",
         "tools/integration_queue.py",
     }),
    (125, "1b1686332b6f50876c568d67e6bc9316b2c46e41",
     "infra/integration-postci-next-preflight-overlap-v0", 120, {
         "docs/integration-queue.md",
         "tests/test_integration_queue.py",
         "tools/integration_queue.py",
     }),
    (128, "a326f506e507acee90262d7e5adb8fb83cedeb80",
     "main", None, {
         "docs/delivery-di5-ci-observations.md",
         "tests/test_delivery_ci_observations.py",
         "tests/test_delivery_metrics.py",
         "tools/delivery_ci_observations.py",
         "tools/delivery_metrics.py",
     }),
]


class StagedOverlapBatchManifestTests(unittest.TestCase):
    def load(self):
        return json.loads(MANIFEST.read_text(encoding="utf-8"))

    def test_pinned_dependency_order_exact_heads_and_diff_allowlists(self):
        entries = validate_manifest(self.load(), REPO)
        self.assertEqual(3, MAX_ITEMS)
        self.assertEqual(len(EXPECTED), len(entries))
        for entry, (number, sha, base, parent, paths) in zip(entries, EXPECTED):
            with self.subTest(pr=number):
                self.assertEqual(number, entry["number"])
                self.assertEqual(sha, entry["expected_head_sha"])
                self.assertEqual(base, entry["expected_base_ref"])
                self.assertEqual(parent, entry["depends_on"])
                self.assertEqual(paths, set(entry["allowed_files"]))
                self.assertEqual(len(paths), len(entry["allowed_files"]))
                self.assertFalse(any(path.startswith(".github/workflows/")
                                     for path in entry["allowed_files"]))

    def test_workflow_writer_and_revoked_or_functional_prs_are_excluded(self):
        entries = self.load()["items"]
        self.assertEqual([120, 125, 128], [x["number"] for x in entries])
        self.assertNotIn(131, [x["number"] for x in entries])
        self.assertNotIn(101, [x["number"] for x in entries])
        self.assertNotIn(49, [x["number"] for x in entries])
        self.assertTrue(all(p["number"] >= 120 for p in entries))

    def test_parent_order_and_approved_base_cannot_be_silently_changed(self):
        source = self.load()
        for mutation in ("reverse", "drop_parent", "wrong_parent_base",
                         "extra_item", "unapproved_path", "unapproved_head"):
            with self.subTest(mutation=mutation):
                altered = copy.deepcopy(source)
                items = altered["items"]
                if mutation == "reverse":
                    items.reverse()
                elif mutation == "drop_parent":
                    del items[0]
                elif mutation == "wrong_parent_base":
                    items[1]["expected_base_ref"] = "main"
                elif mutation == "extra_item":
                    items.append(copy.deepcopy(items[0]))
                    items[-1]["number"] = 131
                    items[-1]["expected_head_sha"] = "f" * 40
                elif mutation == "unapproved_path":
                    items[0]["allowed_files"].append("../unapproved")
                elif mutation == "unapproved_head":
                    items[0]["expected_head_sha"] = "not-a-sha"
                with self.assertRaises(StopIntegration):
                    validate_manifest(altered, REPO)

    def test_new_manifest_does_not_install_or_modify_merge_workflow(self):
        workflow = (ROOT / ".github/workflows/integration-queue.yml").read_text()
        self.assertIn("environment: integration-apply", workflow)
        self.assertIn("group: bitchord-serial-integration", workflow)
        self.assertNotIn("\n  push:", workflow)
        self.assertNotIn("\n  pull_request:", workflow)
        self.assertNotIn("\n  schedule:", workflow)
        self.assertIn("expected_main_sha:", workflow)


if __name__ == "__main__":
    unittest.main()

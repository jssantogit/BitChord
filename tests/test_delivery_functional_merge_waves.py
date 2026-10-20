"""Protected functional integration wave candidates: no automatic merge authority.

These static fixtures pin exact reviewed PR originals. They intentionally do
not include blocked Playback B13, cross-front L13, or Acquisition downloads.
"""
import json
from pathlib import Path
import unittest

from tools.integration_queue import MAX_ITEMS, StopIntegration, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
FOLDER = ROOT / ".github" / "integration-batches"
REPO = "jssantogit/BitChord"

CANDIDATES = {
    "delivery-playback-b9-b11-v0": [
        (113, "83b91deb370c0143883cb6e08a13d91b3636b248",
         "main", None, 2),
        (114, "fa4dfcf42894348cabd60cf659b7c4a4d71fc418",
         "playback/b9-canonical-collection-playback-intent-v0", 113, 3),
        (117, "6a5e7da42cb67014e6bc3b9d1d1a133bd9e4af13",
         "playback/b10-canonical-batch-queue-execution-v0", 114, 23),
    ],
    "delivery-listening-l9-l11-v0": [
        (109, "593db4644f47fde0ddee54975d4474fad9cc65f2",
         "main", None, 5),
        (110, "808849bd9f08f3ee76b7c723b7cc8df7bc975994",
         "intelligence/l9-canonical-song-rating-ux-v0", 109, 7),
        (111, "011e5f622248b98bb2fd0243d2530138fdbf18df",
         "intelligence/l10-my-ratings-library-v0", 110, 5),
    ],
    "delivery-catalog-c9-c11-v0": [
        (115, "fd281a4324c88f37a14543e81827e3a3b1ad6b33",
         "main", None, 3),
        (116, "c879bbfd650b6d5d2361867af2b2ecf96d6664c4",
         "catalog/c9-provider-stream-capability-evidence-v0", 115, 2),
        (122, "7529f8032481838907726803c8ececf3938fba8c",
         "catalog/c10-credential-safe-stream-variant-contract-v0", 116, 4),
    ],
    "delivery-performance-d6-d8-v0": [
        (118, "157cf9199d37e879ab9885ff0988201157be3bc9",
         "main", None, 2),
        (119, "5d1c5ae4bc8807b6119334fbfc833122811c7a9d",
         "perf/d6-causal-user-journey-v0", 118, 3),
        (121, "6c19d3ae5377a6c4e781dce2528c49a9cf478d6d",
         "perf/d7-process-exit-forensics-v0", 119, 2),
    ],
}


class FunctionalMergeWavesTests(unittest.TestCase):
    def test_exact_heads_bases_file_counts_and_dependencies(self):
        self.assertEqual(3, MAX_ITEMS)
        observed = set()
        for name, expected in CANDIDATES.items():
            with self.subTest(batch=name):
                source = json.loads((FOLDER / (name + ".json")).read_text())
                items = validate_manifest(source, REPO)
                self.assertEqual(3, len(items))
                for item, (n, sha, ref, depends, count) in zip(items, expected):
                    self.assertEqual(n, item["number"])
                    self.assertNotIn(n, observed)
                    observed.add(n)
                    self.assertEqual(sha, item["expected_head_sha"])
                    self.assertEqual(ref, item["expected_base_ref"])
                    self.assertEqual(depends, item["depends_on"])
                    self.assertEqual(count, len(item["allowed_files"]))
                    self.assertEqual(count, len(set(item["allowed_files"])))
                    self.assertTrue(all(
                        path.startswith(("app/src/", "sharedUi/src/"))
                        for path in item["allowed_files"]))
        self.assertEqual(12, len(observed))

    def test_exclude_blocked_and_unrelated_prs_and_no_workflow_changes(self):
        forbidden = {49, 63, 101, 127, 128, 131, 133}
        selected = {n for group in CANDIDATES.values()
                    for n, *_ in group}
        self.assertFalse(selected & forbidden)
        self.assertEqual(
            [113, 114, 117, 109, 110, 111, 115, 116, 122, 118, 119, 121],
            [n for batch in CANDIDATES.values() for n, *_ in batch])
        workflow = (ROOT / ".github/workflows/integration-queue.yml").read_text()
        self.assertIn("environment: integration-apply", workflow)
        self.assertIn("group: bitchord-serial-integration", workflow)
        self.assertIn("cancel-in-progress: false", workflow)

    def test_malformed_stack_or_oversized_batch_is_rejected(self):
        import copy
        for name in CANDIDATES:
            source = json.loads((FOLDER / (name + ".json")).read_text())
            for mutation in ("reverse", "extra", "missing_parent", "bad_path"):
                with self.subTest(batch=name, mutation=mutation):
                    m = copy.deepcopy(source)
                    if mutation == "reverse":
                        m["items"].reverse()
                    elif mutation == "extra":
                        m["items"].append(copy.deepcopy(m["items"][0]))
                    elif mutation == "missing_parent":
                        del m["items"][0]
                    else:
                        m["items"][0]["allowed_files"].append("../unsafe")
                    with self.assertRaises(StopIntegration):
                        validate_manifest(m, REPO)


if __name__ == "__main__":
    unittest.main()

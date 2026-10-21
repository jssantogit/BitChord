"""Guard the reviewed independent B12/C12/L12 tail integration wave.

This fixture supplies no merge authority: the protected queue still requires
a read-only plan, explicit execute, exact main SHA and post-merge Android CI.
"""
import json
from pathlib import Path
import unittest

from tools.integration_queue import MAX_ITEMS, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
NAME = "delivery-tail-b12-c12-l12-v0"
REPO = "jssantogit/BitChord"

EXPECTED = [
    (129, "826c0b1bf93f3f618c97e638a59fcab008f82dab", {
        "app/src/main/java/com/music/bitchord/playback/CanonicalRemoteQualityIntent.kt",
        "app/src/test/java/com/music/bitchord/playback/CanonicalRemoteQualityIntentTest.kt",
    }),
    (126, "6418fb9f4f9ad7b83576e780bf170038fe67b2c3", {
        "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicMedia3Access.kt",
        "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicStreamTruth.kt",
        "app/src/test/java/com/music/bitchord/OpenSubsonicStreamTruthTest.kt",
    }),
    (112, "818efc8de1f6279e3a0c20cc16b6ebf105e06d7e", {
        "app/src/main/java/com/music/bitchord/MainActivity.kt",
        "app/src/main/java/com/music/bitchord/data/listening/SmartPlaylistDefinitionStore.kt",
        "app/src/main/java/com/music/bitchord/ui/screens/SmartPlaylistBuilderScreen.kt",
        "app/src/test/java/com/music/bitchord/data/listening/SmartPlaylistDefinitionStoreTest.kt",
        "sharedUi/src/commonMain/composeResources/values-pt/strings.xml",
        "sharedUi/src/commonMain/composeResources/values/strings.xml",
    }),
]


class TailFunctionalWaveTests(unittest.TestCase):
    def test_retargeted_independent_heads_and_exact_file_allowlists(self):
        self.assertEqual(3, MAX_ITEMS)
        path = ROOT / ".github/integration-batches" / (NAME + ".json")
        items = validate_manifest(json.loads(path.read_text(encoding="utf-8")), REPO)
        self.assertEqual(3, len(items))
        for item, (number, sha, files) in zip(items, EXPECTED):
            with self.subTest(pr=number):
                self.assertEqual(number, item["number"])
                self.assertEqual(sha, item["expected_head_sha"])
                self.assertEqual("main", item["expected_base_ref"])
                self.assertIsNone(item["depends_on"])
                self.assertEqual(files, set(item["allowed_files"]))
                self.assertEqual(len(files), len(item["allowed_files"]))

    def test_no_blocked_pr_or_cross_front_file_overlap(self):
        self.assertFalse({63, 101, 127, 133, 49} & {n for n, _, _ in EXPECTED})
        seen = set()
        for number, _, files in EXPECTED:
            with self.subTest(pr=number):
                self.assertFalse(seen & files)
                self.assertTrue(all(
                    f.startswith(("app/src/", "sharedUi/src/"))
                    for f in files))
                seen |= files


if __name__ == "__main__":
    unittest.main()

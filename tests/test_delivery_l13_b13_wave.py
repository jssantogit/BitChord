"""TDD: exact Listening L13 and Playback B13 two-PR manifest, no write authority."""
import json
from pathlib import Path
import unittest

from tools.integration_queue import MAX_ITEMS, StopIntegration, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
REPO = "jssantogit/BitChord"
MANIFEST = ROOT / ".github/integration-batches/delivery-final-l13-b13-v0.json"

L13_FILES = {
    "app/src/main/java/com/music/bitchord/MainActivity.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/AdaptiveCollectionsLibraryScreen.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/SmartCollectionBatchHandoff.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/SmartPlaylistBuilderScreen.kt",
    "app/src/test/java/com/music/bitchord/ui/screens/AdaptiveCollectionsLibraryTest.kt",
    "app/src/test/java/com/music/bitchord/ui/screens/SmartCollectionBatchHandoffTest.kt",
    "sharedUi/src/commonMain/composeResources/values-pt/strings.xml",
    "sharedUi/src/commonMain/composeResources/values/strings.xml",
}
B13_FILES = {
    "app/src/main/java/com/music/bitchord/playback/CanonicalRemoteQualityOpenGate.kt",
    "app/src/main/java/com/music/bitchord/playback/CanonicalRemoteQualityRuntime.kt",
    "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt",
    "app/src/test/java/com/music/bitchord/playback/CanonicalRemoteQualityOpenGateTest.kt",
    "app/src/test/java/com/music/bitchord/playback/CanonicalRemoteQualityRuntimeTest.kt",
    "app/src/test/java/com/music/bitchord/playback/CanonicalRemoteTruthGateTest.kt",
}

EXPECTED = (
    (127, "01c5adce17bdb9f4d468c27eb509db5338de80d7", "main", None, L13_FILES),
    (133, "a2d7fb490f72d480919dfcf590252bd7324534c3", "main", None, B13_FILES),
)


class ReviewedL13B13WaveTests(unittest.TestCase):
    def test_manifest_exact_sha_base_files_and_ownership(self):
        self.assertGreaterEqual(MAX_ITEMS, 2)
        items = validate_manifest(json.loads(MANIFEST.read_text(encoding="utf-8")), REPO)
        self.assertEqual(len(items), 2)
        self.assertFalse(L13_FILES.intersection(B13_FILES))
        for item, (number, head, base, dependency, allowed) in zip(items, EXPECTED):
            with self.subTest(number=number):
                self.assertEqual(item["number"], number)
                self.assertEqual(item["expected_head_sha"], head)
                self.assertEqual(item["expected_base_ref"], base)
                self.assertEqual(item["depends_on"], dependency)
                self.assertEqual(set(item["allowed_files"]), allowed)
                self.assertEqual(len(item["allowed_files"]), len(allowed))

    def test_blocked_and_unrelated_prs_excluded(self):
        items = validate_manifest(json.loads(MANIFEST.read_text(encoding="utf-8")), REPO)
        ids = {item["number"] for item in items}
        self.assertEqual(ids, {127, 133})
        self.assertFalse(ids.intersection({49, 63, 101, 130}))

    def test_l13_must_not_reintroduce_playback_owned_files(self):
        self.assertFalse(any("/playback/" in name for name in L13_FILES))
        self.assertNotIn("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt", L13_FILES)
        self.assertIn("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt", B13_FILES)

    def test_manifest_fails_closed_for_extra_files_or_stale_head(self):
        source = json.loads(MANIFEST.read_text(encoding="utf-8"))
        source["items"][0]["allowed_files"].append("../unsafe")
        with self.assertRaises(StopIntegration):
            validate_manifest(source, REPO)
        source = json.loads(MANIFEST.read_text(encoding="utf-8"))
        source["items"][1]["expected_head_sha"] = "not-a-sha"
        with self.assertRaises(StopIntegration):
            validate_manifest(source, REPO)


if __name__ == "__main__":
    unittest.main()

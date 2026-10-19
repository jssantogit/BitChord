"""Contract regression tests for the reviewed, two-dispatch successor queue.

These checks are static. Live PR base/draft/CI gates remain mandatory; a
manifest file by itself does not grant authority to execute.
"""
import copy
import json
from pathlib import Path
import unittest

from tools.integration_queue import MAX_ITEMS, StopIntegration, validate_manifest


ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / ".github" / "integration-batches"
REPO = "jssantogit/BitChord"

EXPECTED = {
    "playback-b2-b3": (
        (51, "35d9752216d369bdacbda2d5211e759c7a94f656",
         "main", None, {
             "app/src/main/java/com/music/bitchord/playback/DeletedLocalMediaAvailability.kt",
             "app/src/main/java/com/music/bitchord/playback/DeletedLocalMediaRestore.kt",
             "app/src/main/java/com/music/bitchord/playback/LastPlayed.kt",
             "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt",
             "app/src/test/java/com/music/bitchord/playback/DeletedLocalMediaLastPlayedPersistenceTest.kt",
             "app/src/test/java/com/music/bitchord/playback/DeletedLocalMediaRestoreTest.kt",
         }),
        (53, "56198f37890335b037c566bad6cc5f8798f91cbf",
         "playback/deleted-local-media-restore-v0", 51, {
             "app/src/main/java/com/music/bitchord/playback/CanonicalQueueMigration.kt",
             "app/src/main/java/com/music/bitchord/playback/CanonicalQueueResolver.kt",
             "app/src/test/java/com/music/bitchord/playback/CanonicalQueueSwitchingRestoreTest.kt",
         }),
    ),
    "catalog-listening-c3-l2": (
        (52, "0671b63740f8f1e95da1c46a0f93944a101fd547",
         "main", None, {
             "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicCatalogSync.kt",
             "app/src/test/java/com/music/bitchord/CanonicalCreditResyncIntegrityRoomTest.kt",
         }),
        (50, "cb58deecc0f2fc87114a9172ea934937fb99e938",
         "main", None, {
             "app/build.gradle.kts",
             "app/src/main/java/com/music/bitchord/MainActivity.kt",
             "app/src/main/java/com/music/bitchord/ui/screens/CanonicalRediscoveryRecordingDetailsScreen.kt",
             "app/src/main/java/com/music/bitchord/ui/screens/LibraryRediscoveryShelf.kt",
             "app/src/test/java/com/music/bitchord/data/listening/LibraryRediscoveryPreviewIntegrationTest.kt",
             "app/src/test/java/com/music/bitchord/ui/screens/LibraryRediscoveryShelfComposeTest.kt",
             "app/src/test/resources/robolectric.properties",
             "sharedUi/src/commonMain/composeResources/values-pt/strings.xml",
             "sharedUi/src/commonMain/composeResources/values/strings.xml",
             "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt",
         }),
    ),
}


def load(name):
    return json.loads((DIRECTORY / (name + ".json")).read_text(encoding="utf-8"))


class NextBatchManifestTests(unittest.TestCase):
    def test_exact_pr_order_head_base_dependency_and_file_allowlist(self):
        for slug, expected in EXPECTED.items():
            with self.subTest(batch=slug):
                manifest = load(slug)
                entries = validate_manifest(manifest, REPO)
                self.assertEqual(len(expected), len(entries))
                for entry, (number, sha, base, parent, paths) in zip(entries, expected):
                    self.assertEqual(number, entry["number"])
                    self.assertEqual(sha, entry["expected_head_sha"])
                    self.assertEqual(base, entry["expected_base_ref"])
                    self.assertEqual(parent, entry["depends_on"])
                    self.assertEqual(paths, set(entry["allowed_files"]))
                    self.assertEqual(len(paths), len(entry["allowed_files"]))

    def test_batches_respect_token_lifetime_and_exclude_forbidden_c2(self):
        self.assertEqual(3, MAX_ITEMS)
        numbers = [
            entry["number"] for name in EXPECTED
            for entry in load(name)["items"]
        ]
        self.assertEqual([51, 53, 52, 50], numbers)
        self.assertEqual(len(numbers), len(set(numbers)))
        self.assertNotIn(49, numbers)
        self.assertTrue(all(
            len(load(name)["items"]) <= MAX_ITEMS for name in EXPECTED
        ))

    def test_playback_child_can_only_follow_b2(self):
        batch = load("playback-b2-b3")
        for mutation in ("reorder", "drop_parent", "parent_to_main"):
            with self.subTest(mutation=mutation):
                altered = copy.deepcopy(batch)
                if mutation == "reorder":
                    altered["items"].reverse()
                elif mutation == "drop_parent":
                    altered["items"] = altered["items"][1:]
                else:
                    altered["items"][1]["expected_base_ref"] = "main"
                with self.assertRaises(StopIntegration):
                    validate_manifest(altered, REPO)

    def test_four_in_single_dispatch_is_rejected_not_silently_truncated(self):
        combined = copy.deepcopy(load("playback-b2-b3"))
        combined["items"].extend(load("catalog-listening-c3-l2")["items"])
        with self.assertRaises(StopIntegration):
            validate_manifest(combined, REPO)

    def test_c2_inclusion_never_allowed(self):
        changed = copy.deepcopy(load("catalog-listening-c3-l2"))
        changed["items"][0]["number"] = 49
        with self.assertRaises(StopIntegration):
            validate_manifest(changed, REPO)


if __name__ == "__main__":
    unittest.main()

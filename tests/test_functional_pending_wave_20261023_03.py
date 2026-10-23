"""Wave 03 protected integration preflight; inert until explicit approval.

The fixed inputs are reviewed original pull request HEADs, direct bases
and disjoint per-PR file allowlists. Android success on new HEADs remains
mandatory before any protected bootstrap or feature merge.
"""
import json
from pathlib import Path
import unittest

from tools.integration_queue import validate_manifest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / ".github/integration-batches/functional-pending-20261023-wave-03.json"
DOC = ROOT / "docs/functional-wave-20261023-preflight-03.md"
D15 = {
    "app/src/main/java/com/music/bitchord/diagnostics/DiagnosticLegacyPrivacy.kt",
    "app/src/main/java/com/music/bitchord/diagnostics/Diagnostics.kt",
    "app/src/test/java/com/music/bitchord/diagnostics/DiagnosticPrivacyOverheadD15Test.kt",
    "app/src/test/java/com/music/bitchord/diagnostics/LegacyDebugLogErrorD15Test.kt",
    "app/src/test/java/com/music/bitchord/diagnostics/LegacyPrivacyD15Test.kt",
}
LISTENING = {
    "app/src/main/java/com/music/bitchord/ui/screens/CanonicalMyRatingsScreen.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/MyRatingsRenderEvidence.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/RemoteRatingsPresentation.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/SmartPlaylistBuilderScreen.kt",
    "app/src/test/java/com/music/bitchord/ui/screens/MyRatingsRenderEvidenceTest.kt",
    "app/src/test/java/com/music/bitchord/ui/screens/RemoteRatingsPresentationTest.kt",
    "app/src/test/java/com/music/bitchord/ui/screens/SmartPlaylistSavedStateTest.kt",
    "sharedUi/src/commonMain/composeResources/values-pt/strings.xml",
    "sharedUi/src/commonMain/composeResources/values/strings.xml",
}


class FunctionalWave03Gate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    def test_only_exact_independent_prs_on_current_reviewed_heads(self):
        entries = validate_manifest(self.manifest, "jssantogit/BitChord")
        self.assertEqual([144, 147], [x["number"] for x in entries])
        self.assertEqual(
            ["c3fcd71f2655f1238b9da2c96917f5a4a01be578",
             "23f48c5640056d0168b13a0cd53b94e87b99b8e0"],
            [x["expected_head_sha"] for x in entries],
        )
        self.assertEqual([None, None], [x["depends_on"] for x in entries])
        self.assertEqual(["main", "main"], [x["expected_base_ref"] for x in entries])

    def test_strict_file_ownership_no_overlap_or_workflow_files(self):
        entries = self.manifest["items"]
        d15, listening = set(entries[0]["allowed_files"]), set(entries[1]["allowed_files"])
        self.assertEqual(D15, d15)
        self.assertEqual(LISTENING, listening)
        self.assertFalse(d15.intersection(listening))
        self.assertEqual(14, len(d15 | listening))
        self.assertEqual(len(D15), len(entries[0]["allowed_files"]))
        self.assertEqual(len(LISTENING), len(entries[1]["allowed_files"]))
        self.assertTrue(all("/com/music/bitchord/diagnostics/" in p for p in d15))
        self.assertTrue(all(
            "/com/music/bitchord/ui/screens/" in p or
            p.startswith("sharedUi/src/commonMain/composeResources/")
            for p in listening
        ))
        self.assertFalse(any(p.startswith(".github/") for p in d15 | listening))

    def test_owner_approval_and_existing_protected_controller_are_retained(self):
        doc = DOC.read_text(encoding="utf-8")
        for requirement in (
            "DRAFT / REVIEW ONLY / NO MERGE",
            "NO WORKFLOW DISPATCH",
            "Android CI #40003102952",
            "IN_PROGRESS",
            "expected_main_sha",
            "PLAN OK: read-only, zero writes",
            "integration-apply",
            "post-main Android GREEN",
            "QUEUE_WAIT",
            "QUEUE_METRIC",
            "Samsung Galaxy A54",
        ):
            self.assertIn(requirement, doc)
        workflow = (ROOT / ".github/workflows/integration-queue.yml").read_text(
            encoding="utf-8"
        )
        android = (ROOT / ".github/workflows/check.yml").read_text(encoding="utf-8")
        controller = (ROOT / "tools/integration_queue.py").read_text(
            encoding="utf-8"
        )
        self.assertIn("environment: integration-apply", workflow)
        self.assertIn("--mode plan", workflow)
        self.assertIn("--mode execute", workflow)
        self.assertIn("MAX_ITEMS = 3", controller)
        self.assertIn("QUEUE_WAIT v=1", controller)
        self.assertIn("strict_required_status_checks_policy", controller)
        self.assertIn("name: Android · test and build", android)
        self.assertIn("queue: max", android)
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            android,
        )


if __name__ == "__main__":
    unittest.main()

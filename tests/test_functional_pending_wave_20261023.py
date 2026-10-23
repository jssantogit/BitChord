"""Inert source-controlled preflight for the protected 2026-10-23 Wave 02.

These tests pin reviewed original PR HEADs, direct parent bases and exclusive
file scopes. Only the protected integration controller can retarget a child
after its approved parent has merged and passed post-main Android CI.
"""
import json
from pathlib import Path
import unittest

from tools.integration_queue import validate_manifest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / ".github/integration-batches/functional-pending-20261023-wave-02.json"
DOC = ROOT / "docs/functional-wave-20261023-preflight.md"
EXPECTED = [
    (140, "bc0c08a407f87c169011de33420a5d3c68cc4b31", None, "main",
     {"app/src/main/java/com/music/bitchord/diagnostics/RemoteRatingEvidence.kt",
      "app/src/test/java/com/music/bitchord/diagnostics/RemoteRatingEvidenceD12Test.kt"}),
    (142, "770bd2b04bb5d84f73e82e5855b6b28bd229fe62", 140,
     "perf/d12-remote-rating-evidence-v0",
     {"app/src/main/java/com/music/bitchord/diagnostics/DiagnosticEvidenceJourney.kt",
      "app/src/main/java/com/music/bitchord/diagnostics/DiagnosticNavigationWitness.kt",
      "app/src/test/java/com/music/bitchord/diagnostics/DiagnosticNavigationTruthD13Test.kt"}),
    (143, "307b4441c30d944c861ae5f8d3898dcec80dbad8", 142,
     "perf/d13-navigation-truth-v0",
     {"app/src/main/java/com/music/bitchord/diagnostics/MainThreadStallDetector.kt",
      "app/src/main/java/com/music/bitchord/diagnostics/RecentUiWindowCorrelation.kt",
      "app/src/main/java/com/music/bitchord/diagnostics/RuntimePerformanceMonitor.kt",
      "app/src/test/java/com/music/bitchord/diagnostics/UiStallCorrelationD14Test.kt"}),
]


class FunctionalWave02Gate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    def test_exact_dag_prs_heads_bases_and_allowlists(self):
        entries = validate_manifest(self.manifest, "jssantogit/BitChord")
        self.assertEqual(3, len(entries))
        for actual, (number, sha, parent, base, paths) in zip(entries, EXPECTED):
            self.assertEqual(number, actual["number"])
            self.assertEqual(sha, actual["expected_head_sha"])
            self.assertEqual(parent, actual["depends_on"])
            self.assertEqual(base, actual["expected_base_ref"])
            self.assertEqual(paths, set(actual["allowed_files"]))
            self.assertEqual(len(paths), len(actual["allowed_files"]))
        self.assertEqual([140, 142, 143], [x["number"] for x in entries])

    def test_incremental_file_ownership_no_cross_pr_or_workflow_edits(self):
        entries = self.manifest["items"]
        paths = [p for entry in entries for p in entry["allowed_files"]]
        self.assertEqual(9, len(paths))
        self.assertEqual(9, len(set(paths)))
        self.assertTrue(all(p.startswith("app/src/") and
                            "/com/music/bitchord/diagnostics/" in p
                            for p in paths))
        self.assertFalse(any(p.startswith(".github/") for p in paths))
        self.assertFalse(any(p.startswith("sharedUi/") for p in paths))

    def test_explicit_owner_gate_and_next_wave_excluded(self):
        text = DOC.read_text(encoding="utf-8")
        self.assertIn("DRAFT / REVIEW ONLY / NO MERGE", text)
        self.assertIn("NO WORKFLOW DISPATCH", text)
        self.assertIn("expected_main_sha", text)
        self.assertIn("QUEUE_WAIT", text)
        self.assertIn("QUEUE_METRIC", text)
        self.assertIn("post-merge", text)
        self.assertIn("integration-apply", text)
        self.assertIn("#144 D15", text)
        self.assertIn("#147 Listening", text)
        self.assertIn("firstDraw", text)
        self.assertNotIn(144, [x["number"] for x in self.manifest["items"]])
        self.assertNotIn(147, [x["number"] for x in self.manifest["items"]])

    def test_production_required_gate_and_serial_workflow_unchanged(self):
        workflow = (ROOT / ".github/workflows/integration-queue.yml").read_text(
            encoding="utf-8")
        required = (ROOT / ".github/workflows/check.yml").read_text(encoding="utf-8")
        controller = (ROOT / "tools/integration_queue.py").read_text(
            encoding="utf-8")
        self.assertIn("environment: integration-apply", workflow)
        self.assertIn("--mode execute", workflow)
        self.assertIn("--mode plan", workflow)
        self.assertIn("MAX_ITEMS = 3", controller)
        self.assertIn("QUEUE_WAIT v=1", controller)
        self.assertIn("strict_required_status_checks_policy", controller)
        self.assertIn("name: Android · test and build", required)
        self.assertIn("queue: max", required)
        self.assertIn(
            "run: ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest",
            required,
        )


if __name__ == "__main__":
    unittest.main()

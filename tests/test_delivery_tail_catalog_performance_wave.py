"""Exact reviewed C13/D9/D10 integration candidate, no merge authority."""
import json
from pathlib import Path
import unittest

from tools.integration_queue import MAX_ITEMS, StopIntegration, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
REPO = "jssantogit/BitChord"
MANIFEST = ROOT / ".github/integration-batches/delivery-tail-c13-d9-d10-v0.json"

EXPECTED = [
    (130, "c65bc1de6ade14d03f1aa760b996ccbaafe76622",
     "main", None, {
         "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicAccount.kt",
         "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicMedia3Access.kt",
         "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicMediaAccess.kt",
         "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicPlaybackStreamAdapter.kt",
         "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicStreamLifecycle.kt",
         "app/src/main/java/com/music/bitchord/data/catalog/provider/opensubsonic/OpenSubsonicTranscodeDecision.kt",
         "app/src/test/java/com/music/bitchord/OpenSubsonicPlaybackStreamAdapterTest.kt",
         "app/src/test/java/com/music/bitchord/OpenSubsonicStreamLifecycleTest.kt",
         "app/src/test/java/com/music/bitchord/OpenSubsonicStreamPolicyGatewayTest.kt",
     }),
    (123, "1d5c819c26e0380da2392d485b55fa0e46561042",
     "main", None, {
         "app/src/main/java/com/music/bitchord/diagnostics/DiagnosticFailureIncidentSynthesizer.kt",
         "app/src/test/java/com/music/bitchord/diagnostics/DiagnosticFunctionalLifecycleD9Test.kt",
     }),
    (124, "abd7f85ff9e6dc4be902fe435ccf87689acbc2fb",
     "perf/d9-functional-recovery-lifecycles-v0", 123, {
         "app/src/dev/java/com/music/bitchord/diagnostics/DiagnosticsActivity.kt",
         "app/src/main/java/com/music/bitchord/diagnostics/DiagnosticFileStore.kt",
         "app/src/main/java/com/music/bitchord/diagnostics/DiagnosticIncidentTriage.kt",
         "app/src/main/java/com/music/bitchord/diagnostics/Diagnostics.kt",
         "app/src/test/java/com/music/bitchord/diagnostics/DiagnosticIncidentExportIntegrationD10Test.kt",
         "app/src/test/java/com/music/bitchord/diagnostics/DiagnosticIncidentTriageD10Test.kt",
         "app/src/test/java/com/music/bitchord/diagnostics/DiagnosticIntelligenceOverheadD10Test.kt",
         "docs/DIAGNOSTICS.md",
     }),
]


class CatalogPerformanceTailManifestTests(unittest.TestCase):
    def test_exact_heads_bases_order_dependencies_and_files(self):
        self.assertEqual(MAX_ITEMS, 3)
        items = validate_manifest(json.loads(MANIFEST.read_text(encoding="utf-8")), REPO)
        self.assertEqual(len(EXPECTED), len(items))
        seen_files = set()
        for item, (n, sha, base, depends, files) in zip(items, EXPECTED):
            with self.subTest(pr=n):
                self.assertEqual(n, item["number"])
                self.assertEqual(sha, item["expected_head_sha"])
                self.assertEqual(base, item["expected_base_ref"])
                self.assertEqual(depends, item["depends_on"])
                self.assertEqual(files, set(item["allowed_files"]))
                self.assertEqual(len(files), len(item["allowed_files"]))
                self.assertFalse(seen_files.intersection(files))
                seen_files.update(files)

    def test_no_blocked_or_unrelated_prs(self):
        ids = {x[0] for x in EXPECTED}
        self.assertFalse(ids.intersection({49, 63, 101, 127, 133}))
        self.assertEqual(ids, {130, 123, 124})

    def test_fail_closed_if_stack_is_reordered(self):
        source = json.loads(MANIFEST.read_text(encoding="utf-8"))
        source["items"].reverse()
        with self.assertRaises(StopIntegration):
            validate_manifest(source, REPO)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""No-network tests for the non-authorizing Discogs-first hypothesis audit."""
import json
import tempfile
from pathlib import Path
import unittest

from catalog_211_discogs_primary_baseline import baseline, DEPENDENCIES

MANIFEST = Path("docs/plans/catalog-211-discogs-corpus-manifest.json")


class DiscogsPrimaryBaselineTest(unittest.TestCase):
    def test_actual_manifest_is_exploratory_and_cannot_switch_runtime(self):
        with MANIFEST.open(encoding="utf-8") as handle:
            result = baseline(json.load(handle), Path("."))
        self.assertEqual("research_only_not_ready", result["status"])
        self.assertEqual("exploratory_not_approved", result["decision"])
        self.assertFalse(result["automatic_switch_authorized"])
        self.assertFalse(result["corpus_complete"])
        self.assertEqual(0, result["corpus_reviewed"])
        self.assertEqual(30, result["corpus_required"])
        self.assertEqual(5, result["corpus_selected_unreviewed"]["unlinked"])
        self.assertEqual(3, result["corpus_selected_unreviewed"]["source_only"])
        self.assertIsNone(result["corpus_metrics"])

    def test_existing_runtime_sources_still_bind_to_musicbrainz(self):
        document = json.loads(MANIFEST.read_text(encoding="utf-8"))
        result = baseline(document, Path("."))
        self.assertTrue(all(
            item["state"] == "dependency_observed"
            for item in result["source_dependencies"].values()
        ))
        self.assertIn("musicbrainz_identity_and_discovery_coupling", result["unresolved"])

    def test_missing_sources_never_count_as_successful_migration(self):
        document = json.loads(MANIFEST.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as root:
            result = baseline(document, Path(root))
        self.assertTrue(all(
            item["state"] == "unknown_requires_code_review"
            for item in result["source_dependencies"].values()
        ))
        self.assertFalse(result["automatic_switch_authorized"])
        self.assertIn("source_contracts_require_manual_review", result["unresolved"])

    def test_corrupt_corpus_is_fail_closed(self):
        for invalid in ({}, {"schema_version": 1, "cases": []}, []):
            result = baseline(invalid, Path("."))
            self.assertFalse(result["automatic_switch_authorized"])
            self.assertFalse(result["corpus_complete"])
            self.assertNotEqual("ready", result["status"])

    def test_all_proven_code_markers_are_not_operator_approval(self):
        self.assertEqual(5, len(DEPENDENCIES))
        document = json.loads(MANIFEST.read_text(encoding="utf-8"))
        result = baseline(document, Path("."))
        self.assertIn("owner_has_not_approved_provider_inversion", result["unresolved"])
        self.assertIn("recording_and_local_user_identity_migration_not_verified", result["unresolved"])


if __name__ == "__main__":
    unittest.main()

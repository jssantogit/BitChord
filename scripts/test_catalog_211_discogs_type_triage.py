#!/usr/bin/env python3
"""A cohort: offline type triage is NOT an editorial correction or Reviewed."""
import copy
import json
from pathlib import Path
import unittest

from catalog_211_discogs_type_triage import triage, classify

GROUP = "00000000-0000-4000-8000-000000000001"
MB = "d0c0cfe6-0d31-469e-965a-3a5c18b1eb10"
DID = 31008160

def seed():
    return {"id": "CTRL-A-TYPE", "artist": "ENHYPEN",
            "musicbrainz_artist_mbid": "95663592-1447-4579-90f7-c94733476667",
            "discogs_artist_id": 8303009,
            "musicbrainz_release_mbid": MB, "discogs_release_id": DID,
            "reviewed": False}

def observation():
    return {**seed(), "status": "edition_facts_compared_not_reviewed",
            "musicbrainz_group_mbid": GROUP,
            "musicbrainz_group_primary_type": "EP",
            "musicbrainz_release_status": "Official",
            "artist_identity_confirmed_by_source_ids": True,
            "direct_release_relation": "points_to_this_release",
            "comparison": {"discogs_explicit_type": "Album",
                           "tracklist_matches": True,
                           "barcode_identical_on_both": True}}

def controls():
    return {"schema_version": 1, "pairs": [seed()]}

def report(item=None):
    return {"schema_version": 1, "reviewed_corpus_cases": 0,
            "cases": [observation() if item is None else item]}

class TypeTriageTest(unittest.TestCase):
    def test_direct_type_disagreement_is_reviewer_lead_never_type_correction(self):
        item = triage(controls(), report())
        r = item["cases"][0]
        self.assertEqual("linked_type_disagreement_needs_review", r["state"])
        self.assertEqual("EP", r["musicbrainz_primary_type"])
        self.assertEqual("Album", r["discogs_type"])
        self.assertEqual(GROUP, r["musicbrainz_group_mbid"])
        self.assertFalse(r["verified"])
        self.assertFalse(r["reviewed"])
        self.assertEqual(0, item["reviewed_corpus_cases"])
        self.assertEqual(1, item["linked_type_disagreement_leads"])

    def test_unlinked_same_or_conflicting_types_are_context_only(self):
        raw = observation()
        raw["direct_release_relation"] = "no_direct_release_link"
        self.assertEqual("unlinked_type_context_only",
                         triage(controls(), report(raw))["cases"][0]["state"])
        raw["comparison"]["discogs_explicit_type"] = "EP"
        self.assertEqual("unlinked_type_context_only",
                         triage(controls(), report(raw))["cases"][0]["state"])
        self.assertEqual(0, triage(controls(), report(raw))["linked_type_disagreement_leads"])

    def test_direct_same_type_is_not_counted_as_correction(self):
        raw = observation()
        raw["comparison"]["discogs_explicit_type"] = "EP"
        r = triage(controls(), report(raw))
        self.assertEqual("linked_type_agreement_not_adjudicated", r["cases"][0]["state"])
        self.assertEqual(0, r["linked_type_disagreement_leads"])

    def test_unknown_or_ambiguous_discogs_type_cannot_correct_musicbrainz(self):
        for kind in ("unknown", "ambiguous"):
            raw = observation()
            raw["comparison"]["discogs_explicit_type"] = kind
            r = triage(controls(), report(raw))
            self.assertEqual("source_type_insufficient", r["cases"][0]["state"])
            self.assertEqual(0, r["linked_type_disagreement_leads"])

    def test_provider_unavailable_and_unofficial_do_not_infer_type(self):
        raw = observation()
        raw["status"] = "source_unavailable"
        self.assertEqual("provider_unavailable",
                         triage(controls(), report(raw))["cases"][0]["state"])
        raw = observation()
        raw["musicbrainz_release_status"] = "Bootleg"
        self.assertEqual("musicbrainz_release_not_official",
                         triage(controls(), report(raw))["cases"][0]["state"])
        raw = observation()
        raw["artist_identity_confirmed_by_source_ids"] = False
        self.assertEqual("source_identity_unconfirmed",
                         triage(controls(), report(raw))["cases"][0]["state"])

    def test_report_rejects_mismatched_ids_or_fake_reviewed_corpus(self):
        raw = observation()
        raw["discogs_release_id"] = DID + 1
        with self.assertRaises(ValueError):
            triage(controls(), report(raw))
        with self.assertRaises(ValueError):
            triage(controls(), {**report(), "reviewed_corpus_cases": 1})
        with self.assertRaises(ValueError):
            triage(controls(), {**report(), "cases": [observation(), observation()]})

    def test_checked_in_six_controls_are_nonreviewed_unique_source_ids(self):
        controls_real = json.loads(Path(
            "docs/plans/catalog-211-discogs-edition-pairs.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(6, len(controls_real["pairs"]))
        self.assertEqual(6, len({p["discogs_release_id"] for p in controls_real["pairs"]}))
        self.assertTrue(all(p["reviewed"] is False for p in controls_real["pairs"]))

if __name__ == "__main__":
    unittest.main()

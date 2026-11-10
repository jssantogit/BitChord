#!/usr/bin/env python3
"""Offline identity, pagination, barcode and evidence controls for B cohort."""
import json
from pathlib import Path
import unittest
from catalog_211_discogs_unlinked_group_probe import (
    barcode, selected, group_page, investigate, observe, MAX_REQUESTS,
    same_year_alternatives,
)

GROUP = "4e219d4e-c5e0-4ed9-acec-b948c7319b37"
RELEASE = "d0c0cfe6-0d31-469e-965a-3a5c18b1eb10"
ALT = "00000000-0000-4000-8000-000000000019"
ARTIST = "95663592-1447-4579-90f7-c94733476667"
DID, AID = 31008160, 8303009
BARCODE = "012345678905"

def docs():
    seed = {
        "id": "B-01", "cohort": "unlinked", "reviewed": False,
        "selection_state": "identity_observed",
        "direct_release_relation": "no_direct_release_link",
        "musicbrainz_release_mbid": RELEASE, "musicbrainz_group_mbid": GROUP,
        "discogs_release_id": DID,
        "evidence_links": {
            "musicbrainz_release": f"https://musicbrainz.org/release/{RELEASE}",
            "discogs_release": f"https://www.discogs.com/release/{DID}"},
    }
    pair = {"musicbrainz_release_mbid": RELEASE, "discogs_release_id": DID,
            "musicbrainz_artist_mbid": ARTIST, "discogs_artist_id": AID,
            "reviewed": False}
    return {"schema_version": 1, "cases": [seed]}, {"schema_version": 1, "pairs": [pair]}

def release_index():
    return {"release-count": 2, "release-offset": 0, "releases": [
        {"id": RELEASE, "barcode": "000000000000"},
        {"id": ALT, "barcode": BARCODE, "status": "Official", "date": "2024-05-13",
         "release-group": {"id": GROUP}}]}

def response(url):
    if url == f"https://api.discogs.com/releases/{DID}":
        return {"id": DID, "artists": [{"id": AID, "name": "ENHYPEN"}],
                "title": "Edition", "year": 2024, "country": "South Korea",
                "formats": [{"name": "CD", "descriptions": ["EP"]}],
                "labels": [{"name": "Label"}],
                "identifiers": [{"type": "Barcode", "value": BARCODE}],
                "tracklist": [{"type_": "track", "title": "Track One"}]}
    if "/ws/2/release/?" in url:
        return release_index()
    if f"/ws/2/release/{ALT}" in url:
        return {"id": ALT, "release-group": {"id": GROUP}, "title": "Edition",
                "status": "Official", "artist-credit": [
                    {"artist": {"id": ARTIST}, "name": "ENHYPEN"}],
                "barcode": BARCODE, "date": "2024-05-13",
                "media": [{"track-count": 1, "tracks": [{"id": "1", "title": "Track One"}]}],
                "relations": []}
    raise AssertionError("Not an allowed URL: " + url)

class UnlinkedGroupProbeTests(unittest.TestCase):
    def test_barcode_checksum_and_whitespace_are_strict(self):
        self.assertEqual(BARCODE, barcode("0 12345 67890 5"))
        self.assertIsNone(barcode("012345678906"))
        self.assertIsNone(barcode("0123456789"))
        self.assertIsNone(barcode("012345678905?x=1"))
        self.assertIsNone(barcode(True))

    def test_only_seeded_unreviewed_exact_id_b_cases(self):
        m, c = docs()
        self.assertEqual(1, len(selected(m, c)))
        m["cases"][0]["reviewed"] = True
        with self.assertRaises(ValueError):
            selected(m, c)
        m, c = docs()
        c["pairs"][0]["discogs_release_id"] = 2
        with self.assertRaises(ValueError):
            selected(m, c)
        m, c = docs()
        m["cases"].append(dict(m["cases"][0], id="B-02"))
        with self.assertRaises(ValueError):
            selected(m, c)

    def test_group_page_must_be_complete_and_exact(self):
        rows, status = group_page(release_index(), GROUP)
        self.assertEqual("complete_group_browse", status)
        self.assertEqual(2, len(rows))
        self.assertEqual("group_browse_incomplete",
                         group_page({**release_index(), "release-count": 26}, GROUP)[1])
        self.assertEqual("invalid_group_browse",
                         group_page({**release_index(), "release-offset": 25}, GROUP)[1])
        index = release_index()
        index["releases"][1]["release-group"]["id"] = ARTIST
        self.assertEqual("release_group_identity_conflict", group_page(index, GROUP)[1])

    def test_same_group_barcode_lead_must_stay_unreviewed(self):
        m, p = docs()
        seen = []
        def get(url):
            seen.append(url)
            return response(url)
        result = observe(m, p, get)
        self.assertEqual(0, result["reviewed_corpus_cases"])
        self.assertEqual(3, result["provider_requests"])
        self.assertLessEqual(result["provider_requests"], MAX_REQUESTS)
        row = result["findings"][0]
        self.assertEqual("barcode_candidates_inspected_not_verified", row["status"])
        self.assertEqual([ALT], row["matching_barcode_release_ids"])
        self.assertTrue(row["candidates"][0]["comparison"]["tracklist_matches"])
        self.assertEqual("none", row["candidates"][0]["direct_discogs_relation"])
        self.assertFalse(row["reviewed"])
        self.assertFalse(any("token=" in url for url in seen))

    def test_incomplete_group_cannot_claim_no_match(self):
        m, p = docs()
        def get(url):
            return {**release_index(), "release-count": 30} if "release/?" in url else response(url)
        result = observe(m, p, get)
        self.assertEqual("group_browse_incomplete", result["findings"][0]["status"])
        self.assertEqual([], result["findings"][0]["matching_barcode_release_ids"])
        self.assertEqual(2, result["provider_requests"])

    def test_missing_source_barcode_or_artist_conflict_is_not_negative(self):
        m, p = docs()
        def get(url):
            doc = response(url)
            if "api.discogs.com" in url:
                return {**doc, "identifiers": []}
            return doc
        no_barcode = observe(m, p, get)
        self.assertEqual("year_only_content_candidates_not_verified",
                         no_barcode["findings"][0]["status"])
        self.assertEqual("same_year_official_content_only",
                         no_barcode["findings"][0]["candidates"][0]["selection_basis"])
        self.assertEqual(3, no_barcode["provider_requests"])
        def bad(url):
            doc = response(url)
            if "api.discogs.com" in url:
                return {**doc, "artists": [{"id": 12}]}
            return doc
        self.assertEqual("discogs_artist_or_release_identity_conflict",
                         observe(m, p, bad)["findings"][0]["status"])

    def test_no_barcode_and_no_year_is_not_negative_evidence(self):
        m, p = docs()
        def get(url):
            item = response(url)
            return {**item, "year": None, "identifiers": []} if (
                "api.discogs.com" in url
            ) else item
        report = observe(m, p, get)
        self.assertEqual("no_same_year_official_candidate_in_complete_group_index",
                         report["findings"][0]["status"])
        self.assertEqual([], report["findings"][0]["candidates"])
        self.assertEqual(0, report["reviewed_corpus_cases"])

    def test_barcode_conflict_does_not_become_verified_through_year_hint(self):
        m, p = docs()
        def get(url):
            item = response(url)
            if "api.discogs.com" in url:
                return {**item, "identifiers": [
                    {"type": "Barcode", "value": "000000000000"}]}
            if "release/?" in url:
                return {**release_index(), "releases": [
                    {**release_index()["releases"][0], "barcode": None},
                    {**release_index()["releases"][1], "barcode": None}]}
            return item
        row = observe(m, p, get)["findings"][0]
        self.assertEqual("year_only_content_candidates_not_verified", row["status"])
        self.assertEqual("same_year_official_content_only", row["selection_basis"])
        self.assertFalse(row["candidates"][0]["comparison"]["barcode_identical_on_both"])

    def test_non_seed_official_year_alternatives_have_priority(self):
        rows = [
            {"id": RELEASE, "status": "Official", "date": "2024-01-01"},
            {"id": ALT, "status": "Official", "date": "2024-12-01"},
            {"id": "00000000-0000-4000-8000-000000000020",
             "status": "Official", "date": "2024-04-15"},
            {"id": "00000000-0000-4000-8000-000000000021",
             "status": "Bootleg", "date": "2024-07-01"},
        ]
        picked = same_year_alternatives(rows, 2024, RELEASE)
        self.assertEqual(2, len(picked))
        self.assertNotIn(RELEASE, picked)
        self.assertIn(ALT, picked)
        self.assertEqual([], same_year_alternatives(rows, None, RELEASE))
        self.assertEqual([], same_year_alternatives(rows, 2025, RELEASE))

    def test_checked_in_b_samples_are_joined_to_exact_control_pairs(self):
        m = json.loads(Path("docs/plans/catalog-211-discogs-corpus-manifest.json").read_text())
        p = json.loads(Path("docs/plans/catalog-211-discogs-edition-pairs.json").read_text())
        self.assertEqual(5, len(selected(m, p)))

if __name__ == "__main__":
    unittest.main()

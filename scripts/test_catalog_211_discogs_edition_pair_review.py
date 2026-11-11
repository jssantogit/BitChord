#!/usr/bin/env python3
"""Offline RED/GREEN regressions for case-scoped Discogs/MB exact-ID comparison."""
import unittest
import json
from pathlib import Path

from catalog_211_discogs_edition_pair_review import (
    case_list, inspect, observe, release_relations, MAX_REQUESTS,
)

MB_ARTIST = "95663592-1447-4579-90f7-c94733476667"
MB_RELEASE = "d0c0cfe6-0d31-469e-965a-3a5c18b1eb10"
MB_GROUP = "00000000-0000-4000-8000-000000000001"
D_ARTIST, D_RELEASE = 8303009, 31008160

def row():
    return {"id": "CTRL-MEMORABILIA", "artist": "ENHYPEN",
            "musicbrainz_artist_mbid": MB_ARTIST, "discogs_artist_id": D_ARTIST,
            "musicbrainz_release_mbid": MB_RELEASE, "discogs_release_id": D_RELEASE,
            "reviewed": False}

def artist():
    return {"id": MB_ARTIST, "relations": [{"type": "discogs",
            "url": {"resource": f"https://www.discogs.com/artist/{D_ARTIST}"}}]}

def mb():
    return {"id": MB_RELEASE, "title": "MEMORABILIA", "status": "Official",
            "artist-credit": [{"name": "ENHYPEN",
                               "artist": {"id": MB_ARTIST, "name": "ENHYPEN"}}],
            "release-group": {"id": MB_GROUP, "title": "MEMORABILIA",
                              "primary-type": "EP"},
            "barcode": "12345678", "date": "2024-05-13",
            "media": [{"track-count": 2, "tracks": [
                {"id": "t1", "title": "One"}, {"id": "t2", "title": "Two"}]}],
            "relations": []}

def discogs():
    return {"id": D_RELEASE, "title": "MEMORABILIA", "year": 2024,
            "artists": [{"id": D_ARTIST, "name": "Enhypen"}],
            "formats": [{"name": "CD", "descriptions": ["EP"]}],
            "labels": [{"name": "Label"}],
            "identifiers": [{"type": "Barcode", "value": "12345678"}],
            "tracklist": [{"type_": "track", "title": "One"},
                          {"type_": "track", "title": "Two"}]}

def fetch(url):
    if "/artist/" in url:
        return artist()
    if "/ws/2/release/" in url:
        return mb()
    if url == f"https://api.discogs.com/releases/{D_RELEASE}":
        return discogs()
    raise AssertionError(f"unexpected URL: {url}")

class EditionPairReviewTest(unittest.TestCase):
    def test_exact_checked_in_pairs_never_claim_review(self):
        self.assertEqual([row()], case_list({"schema_version": 1, "pairs": [row()]}))
        for invalid in ({**row(), "reviewed": True},
                        {**row(), "discogs_release_id": True},
                        {**row(), "musicbrainz_release_mbid": "bad"},
                        {**row(), "extra": "unapproved_url"}):
            with self.assertRaises(ValueError):
                case_list({"schema_version": 1, "pairs": [invalid]})
        with self.assertRaises(ValueError):
            case_list({"schema_version": 1, "pairs": [row(), row()]})

    def test_checked_in_public_controls_have_six_unique_exact_id_pairs(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-edition-pairs.json"
        ).read_text(encoding="utf-8"))
        cases = case_list(manifest)
        self.assertEqual(6, len(cases))
        self.assertEqual(6, len({c["id"] for c in cases}))
        self.assertTrue(all(c["reviewed"] is False for c in cases))
        self.assertTrue(all(isinstance(c["discogs_release_id"], int) for c in cases))

    def test_comparison_is_factual_even_if_all_fields_match(self):
        report = observe({"schema_version": 1, "pairs": [row()]}, fetch)
        entry = report["cases"][0]
        self.assertEqual("edition_facts_compared_not_reviewed", entry["status"])
        self.assertEqual("no_direct_release_link", entry["direct_release_relation"])
        self.assertEqual(MB_GROUP, entry["musicbrainz_group_mbid"])
        self.assertTrue(entry["artist_identity_confirmed_by_source_ids"])
        self.assertTrue(entry["comparison"]["tracklist_matches"])
        self.assertTrue(entry["comparison"]["barcode_identical_on_both"])
        self.assertEqual(0, report["reviewed_corpus_cases"])
        self.assertEqual(3, report["provider_requests"])
        self.assertNotIn("absent_in_musicbrainz", str(report))
        self.assertLessEqual(report["provider_requests"], MAX_REQUESTS)

    def test_direct_url_relationship_is_still_not_proof_of_same_editions(self):
        m = mb()
        m["relations"] = [{"type": "discogs",
            "url": {"resource": f"https://www.discogs.com/release/{D_RELEASE}-anything"}}]
        result = inspect(row(), lambda url: m if "/ws/2/release/" in url else fetch(url))
        self.assertEqual("points_to_this_release", result["direct_release_relation"])
        self.assertEqual("edition_facts_compared_not_reviewed", result["status"])
        self.assertEqual({D_RELEASE}, release_relations(m))

    def test_reject_artist_conflicts_and_malformed_urls_without_suspect_requests(self):
        bad = discogs()
        bad["artists"][0]["id"] = 1
        urls = []
        def wrong(url):
            urls.append(url)
            return bad if "api.discogs.com" in url else fetch(url)
        result = inspect(row(), wrong)
        self.assertEqual("discogs_release_artist_unconfirmed", result["status"])
        self.assertIsNone(result["comparison"])
        self.assertTrue(all("token" not in url for url in urls))
        self.assertIsNone(release_relations({**mb(), "relations": [
            {"type": "discogs", "url": {
                "resource": f"https://evil.test/release/{D_RELEASE}"}}]}))
        unlinked = inspect(row(), lambda url: {"id": "wrong"} if "/artist/" in url else fetch(url))
        self.assertEqual("artist_relation_unconfirmed", unlinked["status"])

    def test_source_failure_is_not_a_negative_catalog_claim(self):
        def offline(url):
            raise TimeoutError("connection refused")
        result = observe({"schema_version": 1, "pairs": [row()]}, offline)
        self.assertEqual("source_unavailable", result["cases"][0]["status"])
        self.assertEqual("unknown", result["cases"][0]["direct_release_relation"])
        self.assertIsNone(result["cases"][0]["comparison"])
        self.assertEqual(1, result["provider_requests"])

    def test_missing_or_different_tracks_does_not_become_verified(self):
        other = discogs()
        other["tracklist"] = [{"type_": "track", "title": "Another"}]
        result = inspect(row(),
                         lambda url: other if "api.discogs.com" in url else fetch(url))
        self.assertFalse(result["comparison"]["tracklist_matches"])
        self.assertEqual("edition_facts_compared_not_reviewed", result["status"])

if __name__ == "__main__":
    unittest.main()

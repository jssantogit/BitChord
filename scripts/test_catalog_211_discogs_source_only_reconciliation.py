#!/usr/bin/env python3
"""Offline regressions for C-cohort barcode reconciliation."""
import unittest

from catalog_211_discogs_corpus_candidates import ARTISTS
from catalog_211_discogs_source_only_reconciliation import (
    reconcile, read_barcode_search, eligible_barcode, MAX_MB_REQUESTS,
)

ARTIST = "95663592-1447-4579-90f7-c94733476667"
RELEASE = "5f600fc6-5c84-4c84-838e-eb87bd7363c7"


def index():
    return {"schema_version": 1, "artists": [
        {"artist": artist, "musicbrainz_artist_mbid": mbid,
         "discogs_artist_id": 100 + i, "status": "index_compared_not_adjudicated",
         "distinct_mb_titles_count": 3,
         "candidate_index_hints": ([{
             "source_kind": "release", "source_id": 987, "title": "Possible",
             "url": "https://www.discogs.com/release/987",
             "assessment": "index_title_not_exact_mb_group_title",
         }] if i == 0 else [])}
        for i, (artist, mbid) in enumerate(ARTISTS)
    ]}


def editions(barcodes=None, status="edition_metadata_observed_not_adjudicated"):
    return {"schema_version": 1, "observations": [{
        "artist": "ENHYPEN", "musicbrainz_artist_mbid": ARTIST,
        "discogs_artist_id": 100, "source_kind": "release", "source_id": 987,
        "discogs_release_id": 987, "status": status,
        "artist_id_matches_relation": True,
        "track_rows": 3, "labels": ["Record Company"],
        "barcodes": ["8801234567890"] if barcodes is None else barcodes,
    }]}


def search(rows, count=None):
    return {"release-count": len(rows) if count is None else count,
            "releases": rows}


def mb_release(barcode="8801234567890", artist=ARTIST, rid=RELEASE):
    return {"id": rid, "barcode": barcode,
            "artist-credit": [{"artist": {"id": artist}}]}


class ReconciliationTest(unittest.TestCase):
    def test_strict_barcode_normalization(self):
        self.assertEqual("8801234567890", eligible_barcode("880 1234-567890"))
        for value in ("", "1234567", "123456789012345", "12?3456789",
                      "0000000000000", "1" * 12 + "x"):
            self.assertIsNone(eligible_barcode(value))

    def test_exact_artist_barcode_proves_existing_mb_record_not_pressing(self):
        calls = []
        def get(url):
            calls.append(url)
            return search([mb_release()])
        result = reconcile(index(), editions(), get)
        row = result["observations"][0]
        self.assertEqual("existing_mb_artist_barcode", row["status"])
        self.assertEqual([RELEASE], row["matched_mb_release_mbids"])
        self.assertFalse(row["confirmed_missing_from_musicbrainz"])
        self.assertFalse(row["reviewed"])
        self.assertEqual(1, result["provider_requests"])
        self.assertIn("query=barcode%3A8801234567890", calls[0])
        self.assertFalse(any("token=" in url for url in calls))

    def test_no_hits_cannot_prove_absence(self):
        result = reconcile(index(), editions(), lambda _: search([]))
        row = result["observations"][0]
        self.assertEqual("no_exact_barcode_result_not_absence", row["status"])
        self.assertFalse(row["confirmed_missing_from_musicbrainz"])

    def test_other_artist_or_wrong_barcode_does_not_match(self):
        other = "49204a7a-ed85-407a-828f-6fd46f1d8126"
        r = reconcile(index(), editions(), lambda _: search([mb_release(artist=other)]))
        self.assertEqual("barcode_hit_other_artist_only", r["observations"][0]["status"])
        r = reconcile(index(), editions(), lambda _: search([mb_release(barcode="9912345678901")]))
        self.assertEqual("invalid_mb_barcode_search", r["observations"][0]["status"])

    def test_partial_or_oversized_search_is_not_evidence(self):
        for payload in (search([mb_release()], 20), search([mb_release()], 2),
                        {"release-count": "1", "releases": [mb_release()]},
                        {"release-count": 1, "releases": [mb_release(rid="bad")]},
                        None):
            self.assertIsNone(read_barcode_search(payload, "8801234567890", ARTIST))
            r = reconcile(index(), editions(), lambda _: payload)
            self.assertEqual("invalid_mb_barcode_search", r["observations"][0]["status"])

    def test_missing_barcode_and_ineligible_edition_do_not_query_network(self):
        def fail(_):
            raise AssertionError("unexpected HTTP request")
        r = reconcile(index(), editions(barcodes=[]), fail)
        self.assertEqual("source_barcode_unavailable", r["observations"][0]["status"])
        self.assertEqual(0, r["provider_requests"])
        r = reconcile(index(), editions(status="video_only_format"), fail)
        self.assertEqual("excluded_source_metadata", r["observations"][0]["status"])

    def test_invalid_join_never_checks_wrong_edition(self):
        wrong = editions()
        wrong["observations"][0]["source_id"] = 988
        r = reconcile(index(), wrong, lambda _: 1)
        self.assertEqual("source_identity_mismatch", r["observations"][0]["status"])
        self.assertEqual(0, r["provider_requests"])
        self.assertEqual([], reconcile({"schema_version": 2}, editions(), lambda _: 1)["observations"])

    def test_outage_keeps_unknown_and_limits_provider_requests(self):
        def fail(_):
            raise OSError("network offline")
        r = reconcile(index(), editions(), fail)
        self.assertEqual("mb_provider_unavailable", r["observations"][0]["status"])
        self.assertEqual(1, r["provider_requests"])
        self.assertLessEqual(r["provider_requests"], MAX_MB_REQUESTS)
        self.assertFalse(r["observations"][0]["confirmed_missing_from_musicbrainz"])


if __name__ == "__main__":
    unittest.main()

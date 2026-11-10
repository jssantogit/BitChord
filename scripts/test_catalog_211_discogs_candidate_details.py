#!/usr/bin/env python3
"""No-network controls for Discogs #211 public edition detail review packets."""
import unittest
from catalog_211_discogs_candidate_details import (
    candidate_queue, hydrate, observe, MAX_CANDIDATES, MAX_REQUESTS,
)
from catalog_211_discogs_corpus_candidates import ARTISTS

def source(title="Possible", kind="master", rid=555):
    return {"source_kind": kind, "source_id": rid, "title": title,
            "url": f"https://www.discogs.com/{kind}/{rid}",
            "assessment": "index_title_not_exact_mb_group_title"}

def document(hints=None):
    if hints is None:
        hints = [source()]
    return {"schema_version": 1, "artists": [
        {"artist": artist, "musicbrainz_artist_mbid": mbid,
         "discogs_artist_id": 900 + n,
         "status": "index_compared_not_adjudicated",
         "candidate_index_hints": hints if n == 0 else []}
        for n, (artist, mbid) in enumerate(ARTISTS)
    ]}

def edition(rid=777, master=555, name="ENHYPEN", formats=None, artist_id=900):
    return {"id": rid, "master_id": master, "title": "Possible",
            "artists": [{"id": artist_id, "name": name}], "year": 2026,
            "country": "South Korea", "labels": [{"name": "Label"}],
            "formats": formats if formats is not None else
                [{"name": "CD", "descriptions": ["EP"]}],
            "tracklist": [{"type_": "heading", "title": "Part 1"},
                          {"type_": "track", "position": "1", "title": "One"}]}

class CandidateDetailsTest(unittest.TestCase):
    def test_only_whitelisted_typed_public_ids_are_queued(self):
        self.assertEqual(1, len(candidate_queue(document())))
        invalid = document([source(rid=555), source(rid=True),
                            {**source(), "url": "https://evil.test/album/555"}])
        self.assertEqual(1, len(candidate_queue(invalid)))
        self.assertEqual([], candidate_queue({**document(), "schema_version": 2}))
        mismatched = document()
        mismatched["artists"][0]["musicbrainz_artist_mbid"] = ARTISTS[1][1]
        self.assertEqual([], candidate_queue(mismatched))

    def test_round_robin_deduplicates_and_caps_requests(self):
        doc = document([])
        for n, artist in enumerate(doc["artists"]):
            artist["candidate_index_hints"] = [source(rid=n * 3 + i + 1)
                                                for i in range(3)]
        selected = candidate_queue(doc)
        self.assertEqual(MAX_CANDIDATES, len(selected))
        self.assertEqual(MAX_CANDIDATES, len({s["artist"] for s in selected}))

    def test_real_master_id_must_resolve_back_to_same_release_master(self):
        calls = []
        def get(url):
            calls.append(url)
            return {"id": 555, "main_release": 777} if "/masters/" in url else edition()
        result = hydrate(candidate_queue(document())[0], get)
        self.assertEqual("edition_metadata_observed_not_adjudicated", result["status"])
        self.assertEqual(777, result["discogs_release_id"])
        self.assertEqual(1, result["track_rows"])
        self.assertEqual(["EP"], result["format_descriptions"])
        self.assertEqual(2, len(calls))
        mismatch = hydrate(candidate_queue(document())[0], lambda url:
                           {"id": 555, "main_release": 777} if "/masters/" in url else
                           edition(master=999))
        self.assertEqual("master_release_disagrees", mismatch["status"])

    def test_video_only_or_wrong_artist_is_not_presented_as_music(self):
        entry = candidate_queue(document([source(kind="release", rid=777)]))[0]
        video = hydrate(entry, lambda _: edition(formats=[{"name": "DVD"}]))
        self.assertEqual("video_only_format", video["status"])
        alias = hydrate(entry, lambda _: edition(name="ENHYPEN (12)"))
        self.assertEqual("edition_metadata_observed_not_adjudicated", alias["status"])
        self.assertTrue(alias["artist_id_matches_relation"])
        wrong = hydrate(entry, lambda _: edition(artist_id=901))
        self.assertEqual("artist_id_disagrees_with_relation", wrong["status"])
        self.assertEqual("invalid_release", hydrate(entry, lambda _: edition(rid=1))["status"])

    def test_report_never_makes_corpus_claim_or_accepts_untrusted_url(self):
        urls = []
        def get(url):
            urls.append(url)
            return {"id": 555, "main_release": 777} if "/masters/" in url else edition()
        result = observe(document(), get)
        self.assertEqual(0, result["reviewed_corpus_cases"])
        self.assertEqual(2, result["provider_requests"])
        self.assertLessEqual(result["provider_requests"], MAX_REQUESTS)
        self.assertFalse(any("token" in url or "evil" in url for url in urls))
        self.assertTrue(all(row["status"] != "verified" for row in result["observations"]))
        self.assertIn("no MusicBrainz absence", result["warning"])
        self.assertNotIn("absent_in_musicbrainz", str(result))

if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Offline safeguards for corpus scouting; no network or token required."""
import unittest

from catalog_211_discogs_corpus_candidates import (
    complete_mb_titles, discogs_artist_relation, public_index_hints,
    scout_artist, normalized, comparable_title, known_group_title, lexical_signature, ARTISTS, MBID as SCOUT_MBID,
)

MBID = "95663592-1447-4579-90f7-c94733476667"
GROUP_A = "00000000-0000-4000-8000-000000000001"
GROUP_B = "00000000-0000-4000-8000-000000000002"


class CorpusCandidateScoutingTest(unittest.TestCase):
    def relation(self, url="https://www.discogs.com/artist/8303009-ENHYPEN"):
        return {"id": MBID, "relations": [
            {"type": "discogs", "url": {"resource": url}}
        ]}

    def test_public_artist_scout_has_ten_distinct_exact_ids(self):
        self.assertEqual(10, len(ARTISTS))
        self.assertEqual(10, len({mbid for _, mbid in ARTISTS}))
        self.assertTrue(all(SCOUT_MBID.fullmatch(mbid) and name.strip()
                            for name, mbid in ARTISTS))

    def test_artist_anchor_requires_single_typed_exact_relation(self):
        self.assertEqual(8303009, discogs_artist_relation(self.relation(), MBID))
        self.assertIsNone(discogs_artist_relation(self.relation(), GROUP_A))
        for bad_url in ("https://evil.example/artist/8303009",
                        "https://discogs.com/release/8303009",
                        "https://discogs.com/artist/8303009?token=secret",
                        "https://user@discogs.com/artist/8303009"):
            self.assertIsNone(discogs_artist_relation(self.relation(bad_url), MBID))
        two = self.relation()
        two["relations"].append({
            "type": "discogs",
            "url": {"resource": "https://www.discogs.com/artist/7777"}
        })
        self.assertIsNone(discogs_artist_relation(two, MBID))

    def test_only_complete_group_pages_allow_title_comparison(self):
        seen = []
        def fetch(url):
            seen.append(url)
            if "offset=0" in url:
                return {"release-group-offset": 0, "release-group-count": 2,
                        "release-groups": [{"id": GROUP_A, "title": "Known"}]}
            return {"release-group-offset": 1, "release-group-count": 2,
                    "release-groups": [{"id": GROUP_B, "title": "Another"}]}
        self.assertEqual({"known", "another"}, complete_mb_titles(MBID, fetch))
        self.assertEqual(2, len(seen))
        self.assertEqual("the sin: vanish", normalized("THE SIN : VANISH"))
        self.assertEqual("known", comparable_title("ENHYPEN - KNOWN", "ENHYPEN"))
        self.assertEqual("romance: untold", normalized("ROMANCE : UNTOLD"))
        self.assertEqual("i'll like you", normalized("I’LL LIKE YOU"))
        self.assertTrue(known_group_title("ICONIC BY MISTAKE (Explicit)",
                                          "ILLIT", {"iconic by mistake"}))
        self.assertFalse(known_group_title("KNOWN (Live)", "ENHYPEN", {"known"}))
        self.assertTrue(known_group_title("Easy-Crazy-Hot", "LE SSERAFIM",
                                          {"easy crazy hot"}))
        self.assertTrue(known_group_title("'Pureflow' Pt. 1", "LE SSERAFIM",
                                          {"‘pureflow’ pt.1"}))
        self.assertFalse(known_group_title("Love-Me", "IVE", {"love me"}))
        self.assertFalse(known_group_title("Fashion / ファッション", "IVE", {"fashion"}))
        self.assertEqual(("easy", "crazy", "hot"), lexical_signature("easy-crazy-hot"))
        self.assertTrue(known_group_title("HOP", "Stray Kids", {"合 (hop)"}))
        self.assertTrue(known_group_title("Jewelry", "LE SSERAFIM",
                                          {"ジュエリー (jewelry)"}))
        self.assertFalse(known_group_title("HOP (Remix)", "Stray Kids",
                                           {"合 (hop)"}))
        self.assertFalse(known_group_title("Live", "ENHYPEN", {"공연 (live)"}))
        self.assertFalse(known_group_title("Fashion / ファッション", "IVE",
                                           {"fashion"}))
        self.assertTrue(known_group_title("誓い = Chikai", "TOMORROW X TOGETHER",
                                          {"誓い (chikai)"}))
        self.assertTrue(known_group_title("TOMORROW X TOGETHER - 誓い = Chikai",
                                          "TOMORROW X TOGETHER", {"誓い (chikai)"}))
        self.assertFalse(known_group_title("誓い = Chikai (Remix)",
                                           "TOMORROW X TOGETHER", {"誓い (chikai)"}))
        self.assertFalse(known_group_title("誓い = Other",
                                           "TOMORROW X TOGETHER", {"誓い (chikai)"}))
        self.assertFalse(known_group_title("Hello = Chikai", "TOMORROW X TOGETHER",
                                           {"hello (chikai)"}))
        self.assertIsNone(complete_mb_titles(MBID, lambda _: {
            "release-group-offset": 0, "release-group-count": 2,
            "release-groups": [{"id": GROUP_A, "title": "Only One"}]
        }))
        self.assertIsNone(complete_mb_titles(MBID, lambda _: {
            "release-group-count": 1,
            "release-groups": [{"id": GROUP_A, "title": "Known"}]
        }))
        self.assertIsNone(complete_mb_titles(MBID, lambda _: {
            "release-group-offset": 0, "release-group-count": 301,
            "release-groups": []
        }))

    def test_duplicate_index_ids_and_non_main_roles_do_not_inflate_leads(self):
        page = {"pagination": {"page": 1, "pages": 1}, "releases": [
            {"id": 10, "type": "master", "role": "Main", "title": "Known"},
            {"id": 10, "type": "master", "role": "Main", "title": "Different"},
            {"id": 11, "type": "release", "role": "Producer", "title": "Other"},
            {"id": 12, "type": "release", "role": "Main", "title": "Possible"},
        ]}
        self.assertEqual(
            [(10, "Known"), (12, "Possible")],
            [(r["source_id"], r["title"]) for r in public_index_hints(page)]
        )
        self.assertIsNone(public_index_hints({
            "pagination": {"page": 2, "pages": 1}, "releases": []
        }))

    def test_scouting_is_not_accepted_as_verified_or_absent(self):
        calls = []
        def fetch(url):
            calls.append(url)
            if "/artist/" in url and "musicbrainz.org" in url:
                return self.relation()
            if "/release-group/" in url:
                return {"release-group-offset": 0, "release-group-count": 1,
                        "release-groups": [{"id": GROUP_A, "title": "Known"}]}
            return {"pagination": {"page": 1, "pages": 1}, "releases": [
                {"id": 10, "type": "release", "role": "Main", "title": "ENHYPEN - Known"},
                {"id": 11, "type": "release", "role": "Main", "title": "Possible"},
            ]}
        result = scout_artist("ENHYPEN", MBID, fetch)
        self.assertEqual("index_compared_not_adjudicated", result["status"])
        self.assertEqual(1, result["exact_title_matches"])
        self.assertEqual([11], [r["source_id"] for r in result["candidate_index_hints"]])
        self.assertEqual("index_title_not_exact_mb_group_title",
                         result["candidate_index_hints"][0]["assessment"])
        self.assertEqual(3, len(calls))
        self.assertFalse(any("token=" in url for url in calls))

    def test_only_inspects_second_public_page_if_first_has_no_title_leads(self):
        seen = []
        def fetch(url):
            seen.append(url)
            if "/artist/" in url and "musicbrainz.org" in url:
                return self.relation()
            if "/release-group/" in url:
                return {"release-group-offset": 0, "release-group-count": 1,
                        "release-groups": [{"id": GROUP_A, "title": "Known"}]}
            page = 2 if "page=2" in url else 1
            return {"pagination": {"page": page, "pages": 2}, "releases": [
                {"id": page + 100, "type": "release", "role": "Main",
                 "title": "Known" if page == 1 else "Possible New"}]}
        result = scout_artist("ENHYPEN", MBID, fetch)
        self.assertEqual("index_compared_not_adjudicated", result["status"])
        self.assertEqual([1, 2], result["pages_checked"])
        self.assertEqual(4, len(seen))
        self.assertEqual([102], [x["source_id"] for x in result["candidate_index_hints"]])
        def first_has_lead(url):
            if "page=2" in url:
                raise AssertionError("page two is not permitted after new page-one hint")
            if "/artist/" in url and "musicbrainz.org" in url:
                return self.relation()
            if "/release-group/" in url:
                return {"release-group-offset": 0, "release-group-count": 1,
                        "release-groups": [{"id": GROUP_A, "title": "Known"}]}
            return {"pagination": {"page": 1, "pages": 2}, "releases": [
                {"id": 202, "type": "release", "role": "Main", "title": "New"}]}
        self.assertEqual([1], scout_artist("ENHYPEN", MBID, first_has_lead)["pages_checked"])

    def test_invalid_second_page_is_unavailable_not_an_empty_complete_catalog(self):
        def fetch(url):
            if "/artist/" in url and "musicbrainz.org" in url:
                return self.relation()
            if "/release-group/" in url:
                return {"release-group-offset": 0, "release-group-count": 1,
                        "release-groups": [{"id": GROUP_A, "title": "Known"}]}
            return {"pagination": {"page": 1, "pages": 2}, "releases": [
                {"id": 101, "type": "release", "role": "Main", "title": "Known"}]}
        result = scout_artist("ENHYPEN", MBID, fetch)
        self.assertEqual("invalid_discogs_second_page", result["status"])
        self.assertEqual([1], result["pages_checked"])
        self.assertEqual([], result["candidate_index_hints"])

    def test_missing_identity_means_no_discogs_request(self):
        calls = []
        def fetch(url):
            calls.append(url)
            return {"id": MBID, "relations": []}
        result = scout_artist("ENHYPEN", MBID, fetch)
        self.assertEqual("no_unique_artist_relation", result["status"])
        self.assertEqual(1, len(calls))
        self.assertEqual([], result["candidate_index_hints"])


if __name__ == "__main__":
    unittest.main()

"""Fixture-only tests for two live typed-Master B-lead pair matrices."""
import unittest
from urllib.error import HTTPError
from catalog_211_discogs_master_pair_matrix import inspect, observe, MAX_REQUESTS, SEEDS

ARTIST = "95663592-1447-4579-90f7-c94733476667"
GROUP = "849d8582-3422-4d61-bfbe-9b65f4bfdc06"
RELEASE = "5af3b323-b4d2-46d3-aeef-f5a335518d35"
MASTER = 1887999
DISCOGS_ARTIST = 8303009
SEED = {"artist": "ENHYPEN", "artist_mbid": ARTIST,
        "group_mbid": GROUP, "master_id": MASTER,
        "discogs_artist_id": DISCOGS_ARTIST}

def mb_full():
    return {"id": RELEASE, "title": "BORDER : DAY ONE", "status": "Official",
            "release-group": {"id": GROUP},
            "artist-credit": [{"name": "ENHYPEN", "artist": {"id": ARTIST}}],
            "date": "2020-11-30", "country": "KR", "barcode": "8809704420624",
            "media-count": 1, "media": [{"format": "CD", "track-count": 2,
                "tracks": [{"id": "track1", "title": "Intro : Walk The Line"},
                           {"id": "track2", "title": "Given-Taken"}]}],
            "relations": []}

def discogs(did):
    return {"id": did, "master_id": MASTER, "title": "BORDER : DAY ONE",
            "artists": [{"id": DISCOGS_ARTIST, "name": "ENHYPEN"}],
            "year": 2020, "country": "South Korea",
            "formats": [{"name": "CD", "descriptions": ["EP"]}],
            "identifiers": [{"type": "Barcode", "value": "8809704420624"}],
            "labels": [{"name": "BELIFT LAB", "catno": "XYZ"}],
            "tracklist": [{"type_": "track", "title": "Intro : Walk The Line"},
                          {"type_": "track", "title": "Given-Taken"}]}

def source(*, missing_detail=False, large=False, bad_artist=False, bad_group=False):
    data = {
        f"https://musicbrainz.org/ws/2/release-group/{GROUP}?fmt=json&inc=url-rels":
            {"id": GROUP, "relations": [{"type": "discogs",
             "url": {"resource": f"https://www.discogs.com/master/{MASTER}"}}]},
        f"https://api.discogs.com/masters/{MASTER}":
            {"id": MASTER, "main_release": 100},
        f"https://api.discogs.com/masters/{MASTER}/versions?page=1&per_page=8":
            {"pagination": {"page": 1, "pages": 2 if large else 1,
                 "per_page": 8, "items": 10 if large else 2},
             "versions": [{"id": 100}, {"id": 101}]},
        ("https://musicbrainz.org/ws/2/release/?release-group=" + GROUP +
         "&fmt=json&limit=25&offset=0&inc=artist-credits%2Bmedia%2Brelease-groups"):
            {"release-count": 1, "release-offset": 0, "releases": [{
                "id": RELEASE, "title": "BORDER : DAY ONE", "status": "Official",
                "release-group": {"id": GROUP},
                "artist-credit": [{"artist": {"id": ARTIST}}],
                "media": [{"format": "CD"}]}]},
        (f"https://musicbrainz.org/ws/2/release/{RELEASE}"
         "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels"):
            {**mb_full(), "release-group": {"id": "wrong"} if bad_group
             else {"id": GROUP}},
        "https://api.discogs.com/releases/100": discogs(100),
        "https://api.discogs.com/releases/101":
            {**discogs(101), "artists": [
                {"id": 42 if bad_artist else DISCOGS_ARTIST, "name": "ENHYPEN"}]},
    }
    if missing_detail:
        del data["https://api.discogs.com/releases/101"]
    def get(url):
        if url not in data:
            raise OSError("upstream unavailable")
        return data[url]
    return get

class MasterPairMatrixTest(unittest.TestCase):
    def test_two_real_ive_masters_are_distinct_exact_source_controls(self):
        # IDs observed via public typed Master lead scout #43117680744.
        controls = [x for x in SEEDS if x["artist"] == "IVE"]
        self.assertEqual(2, len(controls))
        self.assertEqual({
            ("18271616-7122-4bf6-8fc8-11467cc923c1", 2531575),
            ("07fb58fa-2007-4e40-aa57-88e69a9e19be", 2911144),
        }, {(x["group_mbid"], x["master_id"]) for x in controls})
        self.assertTrue(all(x["artist_mbid"] ==
            "b2f2216a-d7a9-4ce0-8b8f-f494d9a8c196" and
            x["discogs_artist_id"] == 7122521 for x in controls))

    def test_all_versions_and_mb_editions_are_compared_but_never_verified(self):
        result = inspect(SEED, source())
        self.assertEqual("all_pairs_compared_not_adjudicated", result["status"])
        self.assertEqual(2, len(result["pairs"]))
        self.assertEqual([100, 101],
            [p["discogs_release_id"] for p in result["pairs"]])
        self.assertTrue(all(p["comparison"]["tracklist_matches"]
                            for p in result["pairs"]))
        self.assertTrue(all(p["comparison"]["barcode_identical_on_both"]
                            for p in result["pairs"]))
        self.assertTrue(all(p["verified_discogs_edition"] is False
                            for p in result["pairs"]))
        self.assertEqual(0, result["verified_edition_count"])
        self.assertFalse(result["reviewed_by_this_probe"])

    def test_large_master_missing_detail_artist_mismatch_are_not_uniqueness(self):
        for options, expected in [
            ({"large": True}, "master_too_large_not_exhaustive"),
            ({"missing_detail": True}, "incomplete_source_hydration"),
            ({"bad_artist": True}, "source_identity_conflict"),
            ({"bad_group": True}, "source_identity_conflict"),
        ]:
            with self.subTest(options=options):
                found = inspect(SEED, source(**options))
                self.assertEqual(expected, found["status"])
                self.assertEqual([], found["pairs"])
                self.assertEqual(0, found["verified_edition_count"])
                if options.get("missing_detail"):
                    self.assertEqual("discogs_release:101",
                                     found["unavailable_source_id"])

    def test_exact_label_catalog_differences_are_visible_for_each_competing_version(self):
        base = source()
        def get(url):
            payload = base(url)
            if url == (f"https://musicbrainz.org/ws/2/release/{RELEASE}"
                       "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels"):
                return {**payload, "label-info": [
                    {"label": {"name": "BELIFT LAB"}, "catalog-number": "BEL-001"}]}
            if url.endswith("/releases/100"):
                return {**payload, "labels": [
                    {"name": "BELIFT LAB", "catno": "BEL-001"}]}
            if url.endswith("/releases/101"):
                return {**payload, "labels": [
                    {"name": "BELIFT LAB", "catno": "BEL-002"}]}
            return payload
        result = inspect(SEED, get)
        self.assertEqual("all_pairs_compared_not_adjudicated", result["status"])
        by_id = {p["discogs_release_id"]: p for p in result["pairs"]}
        self.assertEqual("exact_label_catalog_pair_observed_not_verified",
                         by_id[100]["label_catalog"]["assessment"])
        self.assertEqual("same_label_different_catalog_numbers",
                         by_id[101]["label_catalog"]["assessment"])
        self.assertEqual(0, result["verified_edition_count"])
        self.assertTrue(all(not x["verified_discogs_edition"]
                            for x in result["pairs"]))

    def test_no_link_pair_cannot_ignore_another_exact_mb_release_direct_owner(self):
        # A complete ReleaseGroup matrix can observe that Discogs release
        # 100 is explicitly linked to a different exact MusicBrainz Release.
        # The same UPC+tracks do not cancel that typed relation.
        second = "00000000-0000-4000-8000-000000000099"
        base = source()
        index_url = ("https://musicbrainz.org/ws/2/release/?release-group=" + GROUP +
                     "&fmt=json&limit=25&offset=0&inc=artist-credits%2Bmedia%2Brelease-groups")
        detail_url = (f"https://musicbrainz.org/ws/2/release/{second}"
                      "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
        def get(url):
            if url == index_url:
                payload = base(url)
                other = {**payload["releases"][0], "id": second}
                return {**payload, "release-count": 2,
                        "releases": payload["releases"] + [other]}
            if url == detail_url:
                return {**mb_full(), "id": second, "relations": [
                    {"type": "discogs",
                     "url": {"resource": "https://www.discogs.com/release/100"}}]}
            return base(url)
        result = inspect(SEED, get)
        self.assertEqual("all_pairs_compared_not_adjudicated", result["status"])
        self.assertEqual(4, len(result["pairs"]))
        by_ids = {(p["musicbrainz_release_mbid"], p["discogs_release_id"]): p
                  for p in result["pairs"]}
        self.assertEqual([second],
            by_ids[(RELEASE, 100)]["competing_direct_release_owner_mbids"])
        self.assertEqual([],
            by_ids[(RELEASE, 101)]["competing_direct_release_owner_mbids"])
        self.assertEqual([],
            by_ids[(second, 100)]["competing_direct_release_owner_mbids"])
        self.assertEqual(0, result["verified_edition_count"])

    def test_a_type_discrepancy_needs_direct_edition_link_and_no_conflicting_facts(self):
        # Source format "EP" is not independent editorial proof of an
        # Album/EP group reclassification, even when the pair is direct.
        raw = source()
        mb_url = (f"https://musicbrainz.org/ws/2/release/{RELEASE}"
                  "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
        group_url = (f"https://musicbrainz.org/ws/2/release-group/{GROUP}"
                     "?fmt=json&inc=url-rels")
        def get(url):
            value = raw(url)
            if url == group_url:
                return {**value, "primary-type": "Album"}
            if url == mb_url:
                return {**value, "relations": [{
                    "type": "discogs",
                    "url": {"resource": "https://www.discogs.com/release/100"}}]}
            return value
        result = inspect(SEED, get)
        self.assertEqual("all_pairs_compared_not_adjudicated", result["status"])
        self.assertEqual("Album", result["musicbrainz_group_primary_type"])
        by_id = {row["discogs_release_id"]: row for row in result["pairs"]}
        self.assertTrue(by_id[100]["source_type_disagreement"])
        self.assertTrue(by_id[100]["a_type_reviewer_lead_not_adjudicated"])
        self.assertTrue(by_id[101]["source_type_disagreement"])
        self.assertFalse(by_id[101]["a_type_reviewer_lead_not_adjudicated"])
        self.assertEqual(0, result["verified_edition_count"])
        self.assertFalse(result["reviewed_by_this_probe"])

    def test_a_type_disagreement_rejected_if_barcode_or_tracks_disagree(self):
        base = source()
        mb_url = (f"https://musicbrainz.org/ws/2/release/{RELEASE}"
                  "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
        group_url = (f"https://musicbrainz.org/ws/2/release-group/{GROUP}"
                     "?fmt=json&inc=url-rels")
        def make_get(*, wrong_barcode=False, wrong_tracks=False):
            def get(url):
                value = base(url)
                if url == group_url:
                    return {**value, "primary-type": "Album"}
                if url == mb_url:
                    return {**value, "relations": [{
                        "type": "discogs",
                        "url": {"resource": "https://www.discogs.com/release/100"}}]}
                if url.endswith("/releases/100"):
                    update = {}
                    if wrong_barcode:
                        update["identifiers"] = [
                            {"type": "Barcode", "value": "1234567890123"}]
                    if wrong_tracks:
                        update["tracklist"] = [
                            {"type_": "track", "title": "Wrong"},
                            {"type_": "track", "title": "Given-Taken"}]
                    return {**value, **update}
                return value
            return get
        for k in ("wrong_barcode", "wrong_tracks"):
            with self.subTest(kind=k):
                found = inspect(SEED, make_get(**{k: True}))
                pair = next(x for x in found["pairs"]
                            if x["discogs_release_id"] == 100)
                self.assertTrue(pair["source_type_disagreement"])
                self.assertFalse(pair["a_type_reviewer_lead_not_adjudicated"])

    def test_shared_budget_and_preserved_b_corpus_are_explicit(self):
        found = observe([SEED], source())
        self.assertEqual(0, found["reviewed_corpus_cases"])
        self.assertEqual(0, found["verified_edition_count"])
        self.assertEqual(7, found["source_http_requests"])
        self.assertLessEqual(found["source_http_requests"], MAX_REQUESTS)
        self.assertEqual("all_pairs_compared_not_adjudicated",
                         found["findings"][0]["status"])

    def test_single_musicbrainz_503_may_retry_within_global_budget(self):
        target = (f"https://musicbrainz.org/ws/2/release/{RELEASE}"
                  "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
        inner = source()
        attempts = {"value": 0}
        def transient(url):
            if url == target:
                attempts["value"] += 1
                if attempts["value"] == 1:
                    raise HTTPError(url, 503, "Service temporarily unavailable", {}, None)
            return inner(url)
        report = observe([SEED], transient)
        self.assertEqual(2, attempts["value"])
        self.assertEqual(8, report["source_http_requests"])
        self.assertEqual("all_pairs_compared_not_adjudicated",
                         report["findings"][0]["status"])
        self.assertEqual(0, report["verified_edition_count"])

    def test_transient_group_503_is_retried_once_without_rewriting_master(self):
        url = f"https://musicbrainz.org/ws/2/release-group/{GROUP}?fmt=json&inc=url-rels"
        inner = source()
        attempts = {"value": 0}
        def transient(request):
            if request == url:
                attempts["value"] += 1
                if attempts["value"] == 1:
                    raise HTTPError(request, 503, "Transient", {}, None)
            return inner(request)
        report = observe([SEED], transient)
        self.assertEqual(2, attempts["value"])
        self.assertEqual(8, report["source_http_requests"])
        self.assertEqual("all_pairs_compared_not_adjudicated",
                         report["findings"][0]["status"])
        self.assertEqual(0, report["verified_edition_count"])

    def test_untrusted_seeds_cannot_route_arbitrary_urls(self):
        for field, value in [("master_id", 0), ("group_mbid", "bad"),
                             ("artist_mbid", "bad"), ("discogs_artist_id", 0)]:
            with self.subTest(field=field):
                bad = {**SEED, field: value}
                with self.assertRaises(ValueError):
                    observe([bad], source())

if __name__ == "__main__":
    unittest.main()

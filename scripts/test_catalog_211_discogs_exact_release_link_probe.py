"""Offline tests: investigate exact MB→Discogs links, not big-Master guesses."""
import unittest

from catalog_211_discogs_exact_release_link_probe import (
    probe, select_editions, MAX_PER_GROUP, MAX_REQUESTS,
)

ARTIST = "95663592-1447-4579-90f7-c94733476667"
GROUP = "849d8582-3422-4d61-bfbe-9b65f4bfdc06"
RELEASE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
DISC_ARTIST = 8303009
MASTER = 1887999
DISC_RELEASE = 7654321
SEED = {"artist": "ENHYPEN", "artist_mbid": ARTIST,
        "group_mbid": GROUP, "master_id": MASTER,
        "discogs_artist_id": DISC_ARTIST}


def artist_record():
    return {"id": ARTIST, "relations": [{"type": "discogs",
        "url": {"resource": f"https://www.discogs.com/artist/{DISC_ARTIST}"}}]}


def group_record():
    return {"id": GROUP, "primary-type": "EP", "relations": [{
        "type": "discogs",
        "url": {"resource": f"https://www.discogs.com/master/{MASTER}"}}]}


def listing(releases=None, count=None):
    records = [release_index()] if releases is None else releases
    return {"release-count": len(records) if count is None else count,
            "release-offset": 0, "releases": records}


def release_index(rid=RELEASE, fmt="CD", barcode="8809704427821"):
    return {"id": rid, "title": "Album Title", "status": "Official",
            "barcode": barcode, "release-group": {"id": GROUP},
            "country": "KR", "date": "2024-01-01",
            "artist-credit": [{"artist": {"id": ARTIST}}],
            "media": [{"format": fmt}]}


def release_detail(relations=None, status="Official"):
    ids = [{"type": "discogs", "url": {
        "resource": f"https://www.discogs.com/release/{DISC_RELEASE}"}}] \
        if relations is None else relations
    return {"id": RELEASE, "title": "Album Title", "status": status,
            "release-group": {"id": GROUP}, "artist-credit": [{
                "artist": {"id": ARTIST}}],
            "relations": ids, "country": "KR", "date": "2024-01-01",
            "barcode": "8809704427821",
            "media": [{"format": "CD", "track-count": 1, "tracks": [{
                "number": "1", "title": "Track", "length": 200000}]}]}


def discogs_release(master_id=MASTER, artist_id=DISC_ARTIST):
    return {"id": DISC_RELEASE, "master_id": master_id,
            "title": "Album Title",
            "artists": [{"id": artist_id, "name": "Enhypen"}],
            "year": 2024, "country": "South Korea",
            "tracklist": [{"type_": "track", "position": "1",
                           "title": "Track", "duration": "3:20"}],
            "formats": [{"name": "CD", "descriptions": ["EP"]}],
            "identifiers": [{"type": "Barcode", "value": "8809704427821"}],
            "labels": [{"name": "Label", "catno": "CAT-1"}]}


def data(get_release=None, get_discogs=None, browse=None, group=None, artist=None):
    inputs = {
        f"https://musicbrainz.org/ws/2/artist/{ARTIST}?fmt=json&inc=url-rels":
            artist_record() if artist is None else artist,
        f"https://musicbrainz.org/ws/2/release-group/{GROUP}?fmt=json&inc=url-rels":
            group_record() if group is None else group,
        f"https://musicbrainz.org/ws/2/release/?release-group={GROUP}&fmt=json&limit=25&offset=0&inc=artist-credits%2Bmedia%2Brelease-groups":
            listing() if browse is None else browse,
        f"https://musicbrainz.org/ws/2/release/{RELEASE}?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels":
            release_detail() if get_release is None else get_release,
        f"https://api.discogs.com/releases/{DISC_RELEASE}":
            discogs_release() if get_discogs is None else get_discogs,
    }
    calls = []
    def get(url):
        calls.append(url)
        if url not in inputs:
            raise AssertionError("unexpected network " + url)
        return inputs[url]
    return get, calls


class ExactLinkProbeTest(unittest.TestCase):
    def test_exact_direct_link_inside_master_is_observed_never_verified(self):
        get, calls = data()
        output = probe([SEED], get)
        self.assertEqual(1, len(output["groups"]))
        group = output["groups"][0]
        self.assertEqual("exact_releases_examined_not_adjudicated",
                         group["status"])
        self.assertEqual("direct_release_in_typed_master_not_adjudicated",
                         group["editions"][0]["status"])
        self.assertEqual(DISC_RELEASE, group["editions"][0]["discogs_release_id"])
        self.assertFalse(group["editions"][0]["verified"])
        self.assertFalse(group["editions"][0]["type_correction_applied"])
        self.assertEqual(0, output["verified_edition_count"])
        self.assertEqual(0, output["reviewed_corpus_cases"])
        self.assertEqual(len(calls), output["source_http_requests"])
        self.assertLessEqual(len(calls), MAX_REQUESTS)

    def test_no_link_cannot_hydrate_a_similar_discogs_pressing(self):
        get, calls = data(get_release=release_detail(relations=[]))
        output = probe([SEED], get)
        self.assertEqual("no_direct_release_link",
                         output["groups"][0]["editions"][0]["status"])
        self.assertFalse(any("/api.discogs.com/" in u for u in calls))
        self.assertEqual(0, output["verified_edition_count"])

    def test_two_different_direct_discogs_urls_are_ambiguous_and_not_hydrated(self):
        rels = [{"type": "discogs", "url": {"resource":
            f"https://www.discogs.com/release/{i}"}} for i in (DISC_RELEASE, 7654322)]
        get, calls = data(get_release=release_detail(relations=rels))
        observed = probe([SEED], get)["groups"][0]["editions"][0]
        self.assertEqual("ambiguous_direct_release_links", observed["status"])
        self.assertFalse(any("api.discogs.com" in u for u in calls))

    def test_direct_link_outside_typed_master_never_proves_same_group(self):
        get, _ = data(get_discogs=discogs_release(master_id=MASTER + 1))
        row = probe([SEED], get)["groups"][0]["editions"][0]
        self.assertEqual("discogs_release_other_master", row["status"])
        self.assertFalse(row["verified"])

    def test_wrong_discogs_artist_cannot_be_exact_edition(self):
        get, _ = data(get_discogs=discogs_release(artist_id=1234))
        row = probe([SEED], get)["groups"][0]["editions"][0]
        self.assertEqual("discogs_release_artist_conflict", row["status"])

    def test_incomplete_mb_browse_is_not_an_exhaustive_shortlist(self):
        get, calls = data(browse=listing(count=26))
        output = probe([SEED], get)
        self.assertEqual("incomplete_canonical_release_index",
                         output["groups"][0]["status"])
        self.assertEqual([], output["groups"][0]["editions"])
        self.assertFalse(any(f"/release/{RELEASE}" in u for u in calls))

    def test_unsafe_artist_or_group_links_prevent_release_requests(self):
        get, calls = data(artist={"id": ARTIST, "relations": []})
        result = probe([SEED], get)
        self.assertEqual("artist_relation_unconfirmed", result["groups"][0]["status"])
        self.assertEqual(1, len(calls))
        get, calls = data(group={"id": GROUP, "relations": []})
        result = probe([SEED], get)
        self.assertEqual("typed_master_relation_unconfirmed", result["groups"][0]["status"])
        self.assertEqual(2, len(calls))

    def test_pick_physical_candidates_without_claiming_exhaustive_presses(self):
        items = [release_index(
            rid=f"00000000-0000-4000-8000-{i:012x}", fmt=fmt,
            barcode="" if i % 2 else "8809704427821"
        ) for i, fmt in enumerate(["Digital Media", "CD", "Vinyl", "CD", "Cassette", "Digital Media"])]
        candidates, state = select_editions(listing(releases=items), GROUP, ARTIST)
        self.assertEqual("complete_official_release_index", state)
        self.assertEqual(MAX_PER_GROUP, len(candidates))
        self.assertTrue(all("Digital Media" not in x["media_formats"] for x in candidates))

    def test_malformed_seed_is_rejected_without_network(self):
        get, calls = data()
        bad = dict(SEED, master_id=True)
        with self.assertRaises(ValueError):
            probe([bad], get)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()

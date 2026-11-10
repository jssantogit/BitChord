import unittest
from catalog_211_discogs_public_link_probe import (
    compare_edition, explicit_discogs_type, exact_discogs_artist_relationship,
    parse_public_artist_page,
)

MB = {
    "id": "5f600fc6-5c84-4c84-838e-eb87bd7363c7",
    "title": "THE SIN : VANISH",
    "status": "Official",
    "country": "KR",
    "date": "2026-01-16",
    "barcode": "198704835247",
    "artist-credit": [{"artist": {"name": "ENHYPEN"}, "name": "ENHYPEN"}],
    "media": [{"track-count": 2, "tracks": [
        {"title": "사건의 발단", "id": "one"},
        {"title": "No Way Back", "id": "two"},
    ]}],
}
DISCOGS = {
    "id": 36297418,
    "title": "ENHYPEN - THE SIN : VANISH",
    "artists": [{"name": "ENHYPEN"}],
    "country": "South Korea",
    "year": 2026,
    "formats": [{"name": "CD", "descriptions": ["Mini-Album"]}],
    "identifiers": [{"type": "Barcode", "value": "198704835247"}],
    "tracklist": [
        {"type_": "heading", "title": "Introduction"},
        {"type_": "track", "position": "1", "title": "사건의 발단"},
        {"type_": "track", "position": "2", "title": "No Way Back"},
    ],
}

class PublicLinkProbeTests(unittest.TestCase):
    def test_realistic_two_track_edition_matches_without_stripping_track_data(self):
        outcome = compare_edition(MB, DISCOGS)
        self.assertTrue(outcome["title_matches"])
        self.assertTrue(outcome["artist_matches"])
        self.assertTrue(outcome["tracklist_complete"])
        self.assertTrue(outcome["tracklist_matches"])
        self.assertTrue(outcome["year_matches"])
        self.assertTrue(outcome["country_compatible"])
        self.assertTrue(outcome["barcode_compatible"])
        self.assertEqual("EP", outcome["discogs_explicit_type"])

    def test_one_track_missing_does_not_verify_the_edition(self):
        altered = {**DISCOGS, "tracklist": DISCOGS["tracklist"][:-1]}
        self.assertFalse(compare_edition(MB, altered)["tracklist_matches"])

    def test_title_mismatch_diagnostics_are_bounded_and_do_not_verify(self):
        altered = {**DISCOGS, "tracklist": [
            DISCOGS["tracklist"][0],
            DISCOGS["tracklist"][1],
            {**DISCOGS["tracklist"][2], "title": "No-Way Back"},
        ]}
        result = compare_edition(MB, altered)
        self.assertFalse(result["tracklist_matches"])
        self.assertEqual(2, result["first_title_mismatch_position"])
        self.assertEqual({"musicbrainz": "no way back",
                          "discogs": "no-way back"},
                         result["first_title_mismatch_titles"])
        self.assertEqual([2], result["punctuation_only_track_mismatch_positions"])
        unrelated = {**DISCOGS, "tracklist": [
            DISCOGS["tracklist"][0],
            DISCOGS["tracklist"][1],
            {**DISCOGS["tracklist"][2], "title": "New Remix"},
        ]}
        self.assertEqual([], compare_edition(MB, unrelated)[
            "punctuation_only_track_mismatch_positions"])
        self.assertIsNone(compare_edition(MB, DISCOGS)["first_title_mismatch_titles"])

    def test_conflicting_barcode_is_never_compatible(self):
        altered = {**DISCOGS, "identifiers": [
            {"type": "Barcode", "value": "12345678"},
        ]}
        self.assertFalse(compare_edition(MB, altered)["barcode_compatible"])

    def test_no_track_count_or_ambiguous_type_is_inconclusive(self):
        altered = {**MB, "media": [{"tracks": MB["media"][0]["tracks"]}]}
        self.assertFalse(compare_edition(altered, DISCOGS)["tracklist_complete"])
        self.assertEqual("ambiguous", explicit_discogs_type([
            {"descriptions": ["EP", "Single"]},
        ]))

    def test_no_unofficial_or_guessed_type(self):
        self.assertEqual("unknown", explicit_discogs_type([
            {"descriptions": ["LP", "Vinyl"]},
        ]))
        self.assertEqual("Single", explicit_discogs_type([
            {"descriptions": ["Maxi-Single"]},
        ]))

class ArtistCatalogProbeTests(unittest.TestCase):
    def test_must_have_exact_typed_artist_relation_for_anonymous_discography(self):
        artist = {"relations": [{"type": "discogs", "url": {
            "resource": "https://www.discogs.com/artist/8303009-ENHYPEN",
        }}]}
        self.assertTrue(exact_discogs_artist_relationship(artist, 8303009))
        self.assertFalse(exact_discogs_artist_relationship(artist, 8303010))
        self.assertFalse(exact_discogs_artist_relationship({
            "relations": [{"type": "discogs", "url": {
                "resource": "https://evil.example/artist/8303009",
            }}]
        }, 8303009))

    def test_artist_discography_requires_valid_pagination_and_main_role(self):
        page = parse_public_artist_page({
            "pagination": {"page": 1, "pages": 2},
            "releases": [
                {"id": 101, "type": "master", "role": "Main"},
                {"id": 102, "type": "release", "role": "Main"},
                {"id": 103, "type": "release", "role": "Producer"},
                {"id": 104, "type": "release", "role": "Main"},
            ],
        }, 1)
        self.assertEqual([("master", 101), ("release", 102),
                          ("release", 104)], page["source_ids"])
        self.assertEqual(2, page["total_pages"])
        self.assertIsNone(parse_public_artist_page({
            "pagination": {"page": 9, "pages": 2},
            "releases": [],
        }, 1))

if __name__ == "__main__":
    unittest.main()

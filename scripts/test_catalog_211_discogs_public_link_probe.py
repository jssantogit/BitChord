import unittest
from catalog_211_discogs_public_link_probe import compare_edition, explicit_discogs_type

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

if __name__ == "__main__":
    unittest.main()

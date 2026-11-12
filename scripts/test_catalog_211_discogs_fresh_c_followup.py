#!/usr/bin/env python3
"""Tests for bounded C-only continuation without recycling B-case identities."""
import copy
import json
from pathlib import Path
import unittest

from catalog_211_discogs_candidate_details import candidate_queue
from catalog_211_discogs_corpus_candidates import ARTISTS
from catalog_211_discogs_fresh_c_followup import build_followup_index, probe_followup


MANIFEST = json.loads(Path("docs/plans/catalog-211-discogs-corpus-manifest.json").read_text())


def hint(rid, kind="release"):
    return {"source_kind": kind, "source_id": rid, "title": "Possible new release",
            "url": f"https://www.discogs.com/{kind}/{rid}",
            "assessment": "index_title_not_exact_mb_group_title"}


def index():
    return {"schema_version": 1, "artists": [
        {"artist": name, "musicbrainz_artist_mbid": mbid,
         "status": "index_compared_not_adjudicated",
         "discogs_artist_id": i + 100,
         "distinct_mb_titles_count": 3, "candidate_index_hints":
            ([hint(301), hint(302), hint(303)] if i == 0 else
             [hint(401), hint(402), hint(4200420, "master")] if i == 7 else
             [hint(501), hint(502), hint(503)] if i == 9 else [])}
        for i, (name, mbid) in enumerate(ARTISTS)
    ]}


def observed(index_payload):
    return {"schema_version": 1, "observations": [
        {**row, "discogs_release_id": row["source_id"],
         "status": "edition_metadata_observed_not_adjudicated"}
        for row in candidate_queue(index_payload)
    ]}


def provider(url):
    if "/masters/" in url:
        return {"id": 4200420, "main_release": 37110012}
    if "/releases/" in url and "discogs.com" in url:
        rid = int(url.split("/releases/")[1].split("?")[0])
        artist = (100 if rid == 303 else 107 if rid == 37110012 else 109)
        return {
            "id": rid,
            "master_id": 4200420 if rid == 37110012 else 0,
            "title": "Possible New",
            "artists": [{"id": artist, "name": "Artist"}],
            "formats": [{"name": "File", "descriptions": ["Single"]}],
            "labels": [{"name": "Label"}],
            "tracklist": [{"title": "First", "type_": "track"}],
            "identifiers": [],
            "year": 2026,
        }
    if "musicbrainz.org" in url:
        # Both search surfaces have independently typed response collections.
        return {"count": 0, "releases": []} if "/ws/2/release/?" in url else {
            "count": 0, "release-groups": []}
    raise AssertionError("Unexpected provider request " + url)


class FreshCFollowupTest(unittest.TestCase):
    def test_new_index_excludes_all_previously_hydrated_sources(self):
        doc = index()
        previous = observed(doc)
        original = copy.deepcopy(doc)
        prepared = build_followup_index(doc, previous, MANIFEST)
        self.assertIsNotNone(prepared)
        self.assertEqual(doc, original)
        self.assertEqual({303, 503, 4200420},
                         {row["source_id"] for row in candidate_queue(prepared)})
        self.assertEqual(0, len({(row["source_kind"], row["source_id"])
                                 for row in candidate_queue(doc)} &
                                {(row["source_kind"], row["source_id"])
                                 for row in candidate_queue(prepared)}))

    def test_reviewed_c04_cannot_reappear_as_a_new_source_only_recovery(self):
        # C-04 was independently reviewed: MB already has Stray Kids'
        # Social Path / Super Bowl -Japanese ver.- EP, notwithstanding an
        # exact-title search miss. Future Discogs index rows must exclude
        # its known Release ID rather than recycling a false recovery.
        doc = index()
        previous = observed(doc)
        doc["artists"][9]["candidate_index_hints"][-1] = hint(38526540)
        prepared = build_followup_index(doc, previous, MANIFEST)
        self.assertIsNotNone(prepared)
        self.assertNotIn(38526540,
            {row["source_id"] for row in candidate_queue(prepared)})
        output = probe_followup(doc, previous, MANIFEST, provider)
        self.assertFalse(any(row["discogs_release_id"] == 38526540
                             for row in output["observations"]))
        self.assertEqual(0, output["confirmed_source_only_cases"])

    def test_reviewed_c05_repackage_release_is_excluded_from_future_c_scans(self):
        # A different MB Release title can live inside a canonical RG named
        # for the original album. C-05 already exists as a MusicBrainz
        # Release; never recycle its Discogs Release ID as 'Discogs-only'.
        doc = index()
        prev = observed(doc)
        doc["artists"][0]["candidate_index_hints"][-1] = hint(32437206)
        prepared = build_followup_index(doc, prev, MANIFEST)
        self.assertIsNotNone(prepared)
        self.assertNotIn(32437206, {
            item["source_id"]
            for artist in prepared["artists"]
            for item in artist["candidate_index_hints"]
        })
        followup = probe_followup(doc, prev, MANIFEST, provider)
        self.assertFalse(any(x["discogs_release_id"] == 32437206
                             for x in followup["observations"]))
        self.assertEqual(0, followup["confirmed_source_only_cases"])

    def test_cannot_revisit_existing_b_release_as_new_c_case(self):
        result = probe_followup(index(), observed(index()), MANIFEST, provider)
        rows = result["observations"]
        seeded = [x for x in rows if x["discogs_release_id"] == 37110012]
        self.assertEqual(1, len(seeded))
        self.assertEqual("already_seeded_b_or_c_release", seeded[0]["status"])
        self.assertEqual("not_checked", seeded[0]["title_status"])
        self.assertEqual(2, result["independent_edition_leads_not_adjudicated"])
        self.assertFalse(any(x["confirmed_missing_from_musicbrainz"] for x in rows))
        self.assertEqual(0, result["reviewed_corpus_cases"])
        self.assertEqual(0, result["confirmed_source_only_cases"])

    def test_new_audio_releases_without_barcode_are_title_checked_not_promoted(self):
        result = probe_followup(index(), observed(index()), MANIFEST, provider)
        statuses = [(row["status"], row["title_status"]) for row in result["observations"]]
        self.assertEqual(2, statuses.count((
            "edition_metadata_observed_not_adjudicated", "no_title_index_hit_not_absence")))
        self.assertLessEqual(result["discogs_requests"], 16)
        self.assertLessEqual(result["musicbrainz_requests"], 24)

    def test_exact_mb_release_title_ids_survive_to_public_followup_report(self):
        # The title triage can find an exact MB Release while its RG uses a
        # different title. The follow-up must not drop that typed identity.
        release = "4c42b226-24c8-4481-91ce-744ae59eaa5b"
        group = "0c668bdb-328c-4dba-8407-8eddb5373a5d"
        expected_artist = ARTISTS[0][1]
        def get(url):
            if "/ws/2/release/?" in url:
                if "arid%3A" + expected_artist in url:
                    return {"count": 1, "releases": [{
                        "id": release, "title": "Possible New",
                        "artist-credit": [{"artist": {"id": expected_artist}}],
                        "release-group": {"id": group},
                    }]}
                return {"count": 0, "releases": []}
            return provider(url)
        result = probe_followup(index(), observed(index()), MANIFEST, get)
        hit = next(row for row in result["observations"]
                   if row["discogs_release_id"] == 303)
        self.assertEqual("mb_release_title_lead_not_edition", hit["title_status"])
        self.assertEqual([release], hit["mb_release_candidate_mbids"])
        self.assertEqual([group], hit["mb_group_candidate_mbids"])
        self.assertFalse(hit["confirmed_missing_from_musicbrainz"])
        self.assertEqual(0, result["confirmed_source_only_cases"])
        self.assertEqual(0, result["reviewed_corpus_cases"])

    def test_fail_closed_for_mismatched_prior_index(self):
        doc = index()
        prev = observed(doc)
        prev["observations"][0]["source_id"] = 9000
        self.assertIsNone(build_followup_index(doc, prev, MANIFEST))
        self.assertEqual(0, probe_followup(doc, prev, MANIFEST, provider)["discogs_requests"])

    def test_fail_closed_for_invalid_manifest(self):
        doc = index()
        prev = observed(doc)
        bad = copy.deepcopy(MANIFEST)
        bad["cases"][10]["discogs_release_id"] = True
        self.assertIsNone(build_followup_index(doc, prev, bad))
        result = probe_followup(doc, prev, bad, provider)
        self.assertEqual([], result["observations"])


if __name__ == "__main__":
    unittest.main()

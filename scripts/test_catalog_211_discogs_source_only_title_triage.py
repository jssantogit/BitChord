#!/usr/bin/env python3
"""Offline tests: group C title-index leads cannot prove identity or absence."""
import unittest
from catalog_211_discogs_source_only_title_triage import triage, parse_mb_group_search, parse_mb_release_search
from test_catalog_211_discogs_source_only_reconciliation import index, editions, ARTIST

GROUP = '4e219d4e-c5e0-4ed9-acec-b948c7319b37'


def prior(status='source_barcode_unavailable'):
    return {'schema_version':1,'observations':[{'source_kind':'release','source_id':987,
        'discogs_release_id':987,'status':status,'matched_mb_release_mbids':[],
        'confirmed_missing_from_musicbrainz':False,'reviewed':False}]}


def response(title='Possible', artist=ARTIST, count=1):
    return {'count':count, 'release-groups':[
        {'id':GROUP,'title':title,'artist-credit':[{'artist':{'id':artist}}]}
    ] if count else []}


class TitleTriageTest(unittest.TestCase):
    def test_existing_group_index_is_only_a_title_lead(self):
        document=editions(barcodes=[])
        document['observations'][0]['title']='Possible'
        calls=[]
        def get(url):
            calls.append(url)
            return response()
        result=triage(index(),document,prior(),get)
        self.assertEqual(1,result['provider_requests'])
        self.assertIn('release-group/',calls[0])
        self.assertIn('arid%3A',calls[0])
        self.assertEqual('mb_title_index_lead_not_edition',result['observations'][0]['status'])
        self.assertEqual([GROUP], result['observations'][0]['mb_group_candidate_mbids'])
        self.assertFalse(result['observations'][0]['confirmed_missing_from_musicbrainz'])

    def test_no_title_match_is_never_proof_absent(self):
        doc=editions(barcodes=[]);doc['observations'][0]['title']='Possible'
        r=triage(index(),doc,prior(),lambda _: response(count=0))
        self.assertEqual('no_title_index_hit_not_absence',r['observations'][0]['status'])
        self.assertFalse(r['observations'][0]['confirmed_missing_from_musicbrainz'])

    def test_slash_separated_multiword_title_falls_back_to_literal_group_prefix(self):
        # Live C lead #43118510232: Discogs "Social Path / Superbowl
        # (Japanese Ver.)" was missed by exact release-title lookup, while
        # MB has a group titled "Social Path / Super Bowl -Japanese ver.-".
        doc = editions(barcodes=[])
        doc["observations"][0]["title"] = "Social Path / Superbowl (Japanese Ver.)"
        calls = []
        def get(url):
            calls.append(url)
            if "releasegroup%3A" in url:
                return response(title="Social Path / Super Bowl -Japanese ver.-")
            return response(count=0)
        result = triage(index(), doc, prior(), get)
        self.assertEqual(2, result["provider_requests"])
        self.assertEqual("mb_title_prefix_lead_not_edition",
                         result["observations"][0]["status"])
        self.assertEqual([GROUP], result["observations"][0]["mb_group_candidate_mbids"])
        self.assertTrue(all("arid%3A" in u for u in calls))
        self.assertTrue(all("release-group/" in u for u in calls))
        self.assertFalse(result["observations"][0]["confirmed_missing_from_musicbrainz"])
        self.assertEqual(0, result["confirmed_source_only_cases"])

    def test_prefix_fallback_is_not_a_generalized_fuzzy_alias_or_absence_claim(self):
        doc = editions(barcodes=[])
        doc["observations"][0]["title"] = "Jewel / Japanese Version"
        urls = []
        def get(url):
            urls.append(url)
            return response(count=0)
        result = triage(index(), doc, prior(), get)
        self.assertEqual(1, len(urls))
        self.assertEqual("no_title_index_hit_not_absence",
                         result["observations"][0]["status"])
        self.assertFalse(result["observations"][0]["confirmed_missing_from_musicbrainz"])

    def test_invalid_prefix_search_is_not_a_release_group_or_missing_claim(self):
        doc = editions(barcodes=[])
        doc["observations"][0]["title"] = "Social Path / Superbowl (Japanese Ver.)"
        def get(url):
            if "releasegroup%3A" in url:
                return response(title="Wrong Artist", artist="49204a7a-ed85-407a-828f-6fd46f1d8126")
            return response(count=0)
        result = triage(index(), doc, prior(), get)
        self.assertEqual("invalid_mb_title_search",
                         result["observations"][0]["status"])
        self.assertEqual([], result["observations"][0]["mb_group_candidate_mbids"])
        self.assertFalse(result["observations"][0]["confirmed_missing_from_musicbrainz"])

    def test_invalid_payload_is_not_evidence(self):
        for invalid in (None,response(count=20),
                        response(title='Wrong Artist',artist='49204a7a-ed85-407a-828f-6fd46f1d8126'),
                        {'count':1,'release-groups':[]}):
            self.assertIsNone(parse_mb_group_search(invalid,ARTIST))
            doc=editions(barcodes=[]);doc['observations'][0]['title']='Possible'
            r=triage(index(),doc,prior(),lambda _:invalid)
            self.assertEqual('invalid_mb_title_search',r['observations'][0]['status'])

    def test_ineligible_source_or_barcode_match_skips_title_network(self):
        doc=editions(barcodes=[]);doc['observations'][0]['title']='Possible'
        def fail(url):raise AssertionError('Unexpected GET')
        self.assertEqual(0,triage(index(),doc,prior('existing_mb_artist_barcode'),fail)['provider_requests'])
        doc['observations'][0]['status']='video_only_format'
        self.assertEqual(0,triage(index(),doc,prior(),fail)['provider_requests'])

    def test_title_is_not_read_from_untrusted_query_syntax(self):
        doc=editions(barcodes=[]);doc['observations'][0]['title']='Possible\nAND arid:evil'
        r=triage(index(),doc,prior(),lambda _:1)
        self.assertEqual('invalid_source_title',r['observations'][0]['status'])
        self.assertEqual(0,r['provider_requests'])

    def test_network_unavailable_and_join_mismatch_are_not_absence(self):
        doc=editions(barcodes=[]);doc['observations'][0]['title']='Possible'
        def unavailable(url):raise OSError('unavailable')
        r=triage(index(),doc,prior(),unavailable)
        self.assertEqual('mb_title_provider_unavailable',r['observations'][0]['status'])
        different=prior(); different['observations'][0]['source_id']=123
        r=triage(index(),doc,different,unavailable)
        self.assertEqual(0,r['provider_requests'])


    def test_musicbrainz_total_count_above_limit_keeps_first_page_group_leads(self):
        # MusicBrainz search 'count' can exceed the 10 records returned.
        page = {'count': 11, 'release-groups': [
            {'id': GROUP if n == 9 else f"00000000-0000-4000-8000-{n:012x}",
             'title': 'Possible',
             'artist-credit': [{'artist': {'id': ARTIST}}]}
            for n in range(10)
        ]}
        self.assertEqual(10, len(parse_mb_group_search(page, ARTIST)))
        doc = editions(barcodes=[])
        doc['observations'][0]['title'] = 'Possible'
        result = triage(index(), doc, prior(), lambda _: page)
        self.assertEqual(1, result['provider_requests'])
        self.assertEqual('mb_title_index_lead_not_edition',
                         result['observations'][0]['status'])
        self.assertIn(GROUP, result['observations'][0]['mb_group_candidate_mbids'])
        self.assertFalse(result['observations'][0]['confirmed_missing_from_musicbrainz'])

    def test_partial_first_page_is_still_invalid_not_absence_evidence(self):
        page = {'count': 11, 'release-groups': [
            {'id': GROUP, 'title': 'Possible',
             'artist-credit': [{'artist': {'id': ARTIST}}]}
        ]}
        self.assertIsNone(parse_mb_group_search(page, ARTIST))


    def test_c05_release_title_found_within_older_named_group_after_rg_index_miss(self):
        # Discogs "Romance: Untold -Daydream-" is an MB Release title,
        # while its RG is "ROMANCE : UNTOLD" without the repackage suffix.
        doc = editions(barcodes=[])
        doc["observations"][0]["title"] = "Romance: Untold -Daydream-"
        release_id = "4c42b226-24c8-4481-91ce-744ae59eaa5b"
        group_id = "0c668bdb-328c-4dba-8407-8eddb5373a5d"
        calls = []
        def get(url):
            calls.append(url)
            if "/ws/2/release-group/?" in url:
                return response(count=0)
            if "/ws/2/release/?" in url:
                return {"count": 1, "releases": [{
                    "id": release_id, "title": "ROMANCE : UNTOLD -daydream-",
                    "artist-credit": [{"artist": {"id": ARTIST}}],
                    "release-group": {"id": group_id}
                }]}
            raise AssertionError("Unexpected source GET " + url)
        result = triage(index(), doc, prior("mb_provider_unavailable"), get)
        row = result["observations"][0]
        self.assertEqual(2, result["provider_requests"])
        self.assertEqual("mb_release_title_lead_not_edition", row["status"])
        self.assertEqual([release_id], row["mb_release_candidate_mbids"])
        self.assertEqual([group_id], row["mb_group_candidate_mbids"])
        self.assertFalse(row["confirmed_missing_from_musicbrainz"])
        self.assertEqual(0, result["confirmed_source_only_cases"])
        self.assertTrue(all("arid%3A" in u for u in calls))

    def test_c05_wrong_artist_release_index_cannot_claim_existing_release(self):
        doc = editions(barcodes=[])
        doc["observations"][0]["title"] = "Romance: Untold -Daydream-"
        def get(url):
            if "/ws/2/release-group/?" in url:
                return response(count=0)
            return {"count": 1, "releases": [{
                "id": "4c42b226-24c8-4481-91ce-744ae59eaa5b",
                "title": "ROMANCE : UNTOLD -daydream-",
                "release-group": {"id": "0c668bdb-328c-4dba-8407-8eddb5373a5d"},
                "artist-credit": [{"artist": {"id": "49204a7a-ed85-407a-828f-6fd46f1d8126"}}]
            }]}
        out = triage(index(), doc, prior(), get)["observations"][0]
        self.assertEqual("invalid_mb_release_title_search", out["status"])
        self.assertEqual([], out["mb_group_candidate_mbids"])
        self.assertFalse(out["confirmed_missing_from_musicbrainz"])

    def test_invalid_or_incomplete_mb_release_search_never_becomes_absence(self):
        valid_row = {"id": "4c42b226-24c8-4481-91ce-744ae59eaa5b",
                     "title": "ROMANCE : UNTOLD -daydream-",
                     "release-group": {"id": "0c668bdb-328c-4dba-8407-8eddb5373a5d"},
                     "artist-credit": [{"artist": {"id": ARTIST}}]}
        self.assertEqual(([valid_row["id"]], [valid_row["release-group"]["id"]]),
                         parse_mb_release_search(
                             {"count": 1, "releases": [valid_row]},
                             ARTIST, "Romance: Untold -Daydream-"))
        for invalid in (
            {"count": 11, "releases": [valid_row]},
            {"count": 1, "releases": [dict(valid_row, id="invalid")]},
            {"count": 2, "releases": [valid_row, valid_row]},
            {"count": 1, "releases": [dict(valid_row, title="Different Remix")]},
        ):
            self.assertIsNone(parse_mb_release_search(
                invalid, ARTIST, "Romance: Untold -Daydream-"))


if __name__=='__main__': unittest.main()

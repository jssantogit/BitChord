#!/usr/bin/env python3
"""Offline tests: group C title-index leads cannot prove identity or absence."""
import unittest
from catalog_211_discogs_source_only_title_triage import triage, parse_mb_group_search
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


if __name__=='__main__': unittest.main()

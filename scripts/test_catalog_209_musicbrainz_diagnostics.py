#!/usr/bin/env python3
"""Offline proof that a public MB probe cannot invent catalog/UI success."""
import json
from pathlib import Path
import unittest
from catalog_209_musicbrainz_diagnostics import (diagnose_case, validate_manifest,
                                                    make_report, probe_guest_browse_shape)

ARTIST = '6b128dd7-e998-4edb-a4f5-400fef190445'
GROUP = '529f87a1-1081-48a4-8ee1-3c08de229858'
WORLD = '541adf5a-48d4-4f7b-a7bb-afd3cea5af27'
JAPAN = '271b31f8-8ab6-483c-97d9-8f0fabb2d4f4'
RECORDING = 'e614e432-ca77-41c1-82c1-4e3a24ebbb0c'
TRACK = '00000000-0000-4000-8000-000000000006'
CASE = {
    'id': 'ILLIT-SUPER-REAL-ME', 'artist_mbid': ARTIST, 'artist_name': 'ILLIT',
    'group_mbid': GROUP, 'expected_primary_type': 'EP',
    'release_mbids': [WORLD, JAPAN], 'recording_mbid': RECORDING,
    'recording_title_contains': 'Magnetic',
}


class FakeClient:
    def __init__(self, *, incomplete_browse=False, missing_search=False, wrong_group=False,
                 unavailable=False, wrong_group_artist=False, missing_group_credit=False):
        self.calls = []
        self.incomplete_browse = incomplete_browse
        self.missing_search = missing_search
        self.wrong_group = wrong_group
        self.unavailable = unavailable
        self.wrong_group_artist = wrong_group_artist
        self.missing_group_credit = missing_group_credit

    def get(self, kind, ident, **params):
        self.calls.append((kind, ident, params))
        if self.unavailable:
            raise TimeoutError('network unavailable')
        if kind == 'artist' and ident == ARTIST:
            return {'id': ARTIST, 'name': 'ILLIT'}
        if kind == 'release-group' and ident == GROUP:
            group = {'id': GROUP, 'title': 'SUPER REAL ME', 'primary-type': 'EP'}
            if not self.missing_group_credit:
                group['artist-credit'] = [{'artist': {
                    'id': '11111111-1111-4111-8111-111111111111'
                          if self.wrong_group_artist else ARTIST, 'name': 'ILLIT'}}]
            return group
        if kind == 'release-group' and not ident and 'artist' in params:
            return {'release-group-count': 2 if self.incomplete_browse else 1,
                    'release-group-offset': 0, 'release-groups':
                    [{'id': GROUP, 'title': 'SUPER REAL ME', 'primary-type': 'EP'}]}
        if kind == 'release' and ident in (WORLD, JAPAN):
            return {'id': ident, 'title': 'SUPER REAL ME',
                    'status': 'Official', 'cover-art-archive': {'front': True}, 'release-group':
                        {'id': '00000000-0000-4000-8000-000000000009' if self.wrong_group else GROUP},
                    'media': [{'position': 1, 'track-count': 1, 'tracks': [
                        {'id': TRACK, 'title': 'Magnetic', 'position': 1,
                         'recording': {'id': RECORDING, 'title': 'Magnetic'}}]}]}
        if kind == 'release' and not ident and 'track_artist' in params:
            return {'release-count': 1, 'releases': [{
                'id': WORLD, 'artist-credit': [{'artist': {'id': ARTIST}}],
                'release-group': {'id': GROUP, 'primary-type': 'EP'},
            }]}
        if kind == 'recording' and ident == RECORDING:
            return {'id': RECORDING, 'title': 'Magnetic'}
        if kind == 'artist' and not ident and 'query' in params:
            return {'count': 1, 'artists': [{'id': ARTIST, 'name': 'ILLIT'}]}
        if kind == 'release-group' and not ident and 'query' in params:
            return {'count': 0 if self.missing_search else 1,
                    'release-groups': [] if self.missing_search else
                    [{'id': GROUP, 'title': 'SUPER REAL ME', 'primary-type': 'EP'}]}
        raise AssertionError(f'unexpected request: {kind} {ident} {params}')


class MusicBrainzDiagnosticsTest(unittest.TestCase):
    def test_manifest_is_bounded_and_explicit(self):
        self.assertEqual([CASE], validate_manifest({'schema_version': 1, 'cases': [CASE]}))
        for bad in (
            {'schema_version': 1, 'cases': []},
            {'schema_version': 1, 'cases': [CASE] * 4},
            {'schema_version': 1, 'cases': [{**CASE, 'artist_mbid': 'not-an-mbid'}]},
            {'schema_version': 1, 'cases': [{**CASE, 'release_mbids': [WORLD, WORLD]}]},
            {'schema_version': 1, 'cases': [{**CASE, 'expected_primary_type': 'Guess'}]},
            {'schema_version': 1, 'cases': [{**CASE, 'id': 'bad/path'}]},
        ):
            with self.subTest(bad=bad):
                self.assertIsNone(validate_manifest(bad))

    def test_complete_public_fixture_yields_source_only_pass(self):
        client = FakeClient()
        results = diagnose_case(CASE, client.get)
        self.assertEqual(8, len(results))
        self.assertEqual({'PASS'}, {row['status'] for row in results})
        self.assertEqual(8, len(client.calls))
        release = next(row for row in results if row['check'] == 'release_1')
        self.assertEqual(RECORDING, release['evidence']['sample_recording_mbid'])
        self.assertEqual(TRACK, release['evidence']['sample_track_mbid'])
        self.assertIs(release['evidence']['cover_art_front_hint'], True)
        self.assertTrue(all(row['evidence_type'] == 'PUBLIC_API_ONLY' for row in results))
        # The previous diagnose_case call already consumed eight GETs.
        # Count this report on a fresh transport to enforce the 8+1 budget.
        report_client = FakeClient()
        report = make_report({'schema_version': 1, 'cases': [CASE]}, report_client)
        self.assertEqual('PUBLIC_API_NOT_ANDROID_UI', report['scope'])
        self.assertEqual(0, report['fail_count'])
        self.assertEqual(0, report['error_count'])
        self.assertFalse(report['android_ui_verified'])
        self.assertFalse(report['local_library_verified'])
        self.assertFalse(report['provider_priority_changed'])
        self.assertEqual('OBSERVED', report['guest_track_artist_shape']['status'])
        self.assertEqual(1, report['extra_guest_shape_requests'])
        self.assertEqual(9, report['http_request_count'])

    def test_search_index_miss_is_not_a_claim_of_missing_release(self):
        rows = diagnose_case(CASE, FakeClient(missing_search=True).get)
        search = next(row for row in rows if row['check'] == 'group_search_index')
        self.assertEqual('INCONCLUSIVE', search['status'])
        self.assertFalse(search['evidence'].get('confirmed_missing', False))
        self.assertEqual('PASS', next(row for row in rows if row['check'] == 'group_identity')['status'])

    def test_browse_page_can_be_partial_without_falsely_proving_absence(self):
        rows = diagnose_case(CASE, FakeClient(incomplete_browse=True).get)
        browse = next(row for row in rows if row['check'] == 'artist_group_browse')
        self.assertEqual('PASS', browse['status'])
        self.assertEqual(2, browse['evidence']['server_total'])
        self.assertEqual(1, browse['evidence']['raw_rows_consumed'])
        self.assertFalse(browse['evidence']['browse_complete'])

    def test_release_to_group_mismatch_is_detected(self):
        rows = diagnose_case(CASE, FakeClient(wrong_group=True).get)
        self.assertEqual(2, sum(x['status'] == 'FAIL' for x in rows))
        self.assertTrue(all(x['check'].startswith('release_') for x in rows if x['status'] == 'FAIL'))

    def test_exact_group_credit_requires_artist_mbid_not_edition_or_name(self):
        rows = diagnose_case(CASE, FakeClient(wrong_group_artist=True).get)
        group = next(x for x in rows if x['check'] == 'group_identity')
        self.assertEqual('FAIL', group['status'])
        self.assertEqual(ARTIST, group['evidence']['expected_artist_mbid'])
        self.assertNotIn(ARTIST, group['evidence']['observed_artist_mbids'])

    def test_missing_structured_group_credit_is_unresolved_not_approved(self):
        rows = diagnose_case(CASE, FakeClient(missing_group_credit=True).get)
        group = next(x for x in rows if x['check'] == 'group_identity')
        self.assertEqual('INCONCLUSIVE', group['status'])
        self.assertFalse(group['evidence']['group_credit_verified'])

    def test_group_lookup_requests_structured_artist_credit_without_extra_get(self):
        client = FakeClient()
        rows = diagnose_case(CASE, client.get)
        self.assertEqual(8, len(rows))
        self.assertEqual(8, len(client.calls))
        self.assertEqual('artist-credits', next(params['inc'] for kind, ident, params
                         in client.calls if kind == 'release-group' and ident == GROUP))
        group = next(x for x in rows if x['check'] == 'group_identity')
        self.assertEqual('PASS', group['status'])
        self.assertTrue(group['evidence']['group_credit_verified'])

    def test_collaboration_credits_accept_joinphrases_but_only_exact_mbids(self):
        class CollaborationClient(FakeClient):
            def get(self, kind, ident, **params):
                body = super().get(kind, ident, **params)
                if kind == 'release-group' and ident == GROUP:
                    body['artist-credit'] = [
                        {'name': 'ILLIT', 'artist': {'id': ARTIST}},
                        ' & ',
                        {'name': 'Other artist',
                         'artist': {'id': '11111111-1111-4111-8111-111111111111'}},
                    ]
                return body

        rows = diagnose_case(CASE, CollaborationClient().get)
        group = next(x for x in rows if x['check'] == 'group_identity')
        self.assertEqual('PASS', group['status'])
        self.assertEqual([ARTIST, '11111111-1111-4111-8111-111111111111'],
                         group['evidence']['observed_artist_mbids'])

    def test_malformed_group_credits_cannot_certify_relationship(self):
        class BrokenCreditClient(FakeClient):
            def get(self, kind, ident, **params):
                body = super().get(kind, ident, **params)
                if kind == 'release-group' and ident == GROUP:
                    body['artist-credit'] = [
                        {'name': 'ILLIT', 'artist': {'id': ARTIST}},
                        {'name': 'Another', 'artist': {'id': 'invalid'}},
                    ]
                return body

        rows = diagnose_case(CASE, BrokenCreditClient().get)
        group = next(x for x in rows if x['check'] == 'group_identity')
        self.assertEqual('INCONCLUSIVE', group['status'])
        self.assertFalse(group['evidence']['group_credit_complete'])

    def test_guest_browse_shape_does_not_invent_embedded_group_credits(self):
        calls = []
        def fake_get(kind, ident, **params):
            calls.append((kind, ident, params))
            return {'release-count': 1, 'releases': [{
                'id': WORLD, 'status': 'Official',
                'artist-credit': [{'artist': {'id': ARTIST}}],
                'release-group': {'id': GROUP, 'primary-type': 'EP'},
                'media': [{'tracks': [{'recording': {'id': RECORDING,
                         'artist-credit': [{'artist': {'id': ARTIST}}]}}]}],
            }]}
        outcome = probe_guest_browse_shape(fake_get)
        self.assertEqual('OBSERVED', outcome['status'])
        self.assertEqual(1, outcome['evidence']['sampled_releases'])
        self.assertEqual(1, outcome['evidence']['release_artist_credit_present'])
        self.assertEqual(0, outcome['evidence']['group_artist_credit_present'])
        self.assertEqual([GROUP], outcome['evidence']['sampled_release_group_mbids'])
        self.assertFalse(outcome['evidence']['group_membership_verified'])
        self.assertEqual(1, len(calls))
        self.assertEqual('release', calls[0][0])
        self.assertEqual('', calls[0][1])
        self.assertEqual('49204a7a-ed85-407a-828f-6fd46f1d8126',
                         calls[0][2]['track_artist'])
        self.assertIn('recordings', calls[0][2]['inc'])
        self.assertIn('release-groups', calls[0][2]['inc'])
        self.assertEqual('5', calls[0][2]['limit'])

    def test_guest_browse_schema_records_embedded_group_credit_when_present(self):
        def fake_get(_kind, _ident, **_params):
            return {'release-count': 1, 'releases': [{
                'id': WORLD,
                'release-group': {'id': GROUP,
                    'artist-credit': [{'artist': {'id': ARTIST}}]},
            }]}
        outcome = probe_guest_browse_shape(fake_get)
        self.assertEqual('OBSERVED', outcome['status'])
        self.assertEqual(1, outcome['evidence']['group_artist_credit_present'])
        self.assertFalse(outcome['evidence']['group_membership_verified'])

    def test_guest_browse_empty_page_is_inconclusive_not_missing(self):
        outcome = probe_guest_browse_shape(
            lambda _kind, _ident, **_params: {'release-count': 0, 'releases': []})
        self.assertEqual('INCONCLUSIVE', outcome['status'])
        self.assertEqual(0, outcome['evidence']['sampled_releases'])

    def test_public_manifest_covers_album_ep_single_by_exact_group_mbids(self):
        path = Path(__file__).resolve().parents[1] / (
            'docs/plans/catalog-209-musicbrainz-diagnostics-cases.json')
        manifest = json.loads(path.read_text(encoding='utf-8'))
        cases = validate_manifest(manifest)
        self.assertIsNotNone(cases)
        self.assertEqual(3, len(cases))
        self.assertEqual({'Album', 'EP', 'Single'},
                         {case['expected_primary_type'] for case in cases})
        self.assertEqual({
            'EP': GROUP,
            'Album': '3f5eba8d-4dc1-4599-a936-21f501787f8e',
            'Single': '23d1cd75-8dc5-436d-a0c6-cab894dd5f64',
        }, {case['expected_primary_type']: case['group_mbid'] for case in cases})
        # Six fixed checks + two editions per case + one shared schema probe.
        self.assertEqual(25, sum(6 + len(c['release_mbids']) for c in cases) + 1)

    def test_network_unavailable_never_becomes_not_found(self):
        client = FakeClient(unavailable=True)
        rows = diagnose_case(CASE, client.get)
        self.assertEqual({'ERROR'}, {x['status'] for x in rows})
        self.assertFalse(any('not_found' in str(x).lower() for x in rows))


if __name__ == '__main__':
    unittest.main()

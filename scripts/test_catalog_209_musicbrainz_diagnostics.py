#!/usr/bin/env python3
"""Offline proof that a public MB probe cannot invent catalog/UI success."""
import unittest
from catalog_209_musicbrainz_diagnostics import diagnose_case, validate_manifest, make_report

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
                 unavailable=False):
        self.calls = []
        self.incomplete_browse = incomplete_browse
        self.missing_search = missing_search
        self.wrong_group = wrong_group
        self.unavailable = unavailable

    def get(self, kind, ident, **params):
        self.calls.append((kind, ident, params))
        if self.unavailable:
            raise TimeoutError('network unavailable')
        if kind == 'artist' and ident == ARTIST:
            return {'id': ARTIST, 'name': 'ILLIT'}
        if kind == 'release-group' and ident == GROUP:
            return {'id': GROUP, 'title': 'SUPER REAL ME', 'primary-type': 'EP'}
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
        report = make_report({'schema_version': 1, 'cases': [CASE]}, client)
        self.assertEqual('PUBLIC_API_NOT_ANDROID_UI', report['scope'])
        self.assertEqual(0, report['fail_count'])
        self.assertEqual(0, report['error_count'])
        self.assertFalse(report['android_ui_verified'])
        self.assertFalse(report['local_library_verified'])
        self.assertFalse(report['provider_priority_changed'])

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

    def test_network_unavailable_never_becomes_not_found(self):
        client = FakeClient(unavailable=True)
        rows = diagnose_case(CASE, client.get)
        self.assertEqual({'ERROR'}, {x['status'] for x in rows})
        self.assertFalse(any('not_found' in str(x).lower() for x in rows))


if __name__ == '__main__':
    unittest.main()

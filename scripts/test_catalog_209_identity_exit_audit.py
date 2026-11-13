"""Offline regression suite for the bounded 20/60/30 MusicBrainz exit audit."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from catalog_209_identity_exit_audit import audit, validate_artists

MANIFEST = Path(__file__).resolve().parents[1] / 'docs/plans/catalog-209-identity-exit-artists.json'


class SourceFixture:
    def __init__(self, seeds, wrong_group=False, missing_credit=False, no_releases=False):
        self.seeds = seeds
        self.by_id = {row['mbid']: row for row in seeds}
        self.wrong_group = wrong_group
        self.missing_credit = missing_credit
        self.no_releases = no_releases
        self.calls = []
        self.groups = {}
        self.releases = {}
        for number, row in enumerate(seeds, 1):
            for pos in range(1, 4):
                group = f'{number:08x}-1234-4234-8234-{pos:012x}'
                release = f'{number:08x}-2345-4345-8345-{pos:012x}'
                track = f'{number:08x}-3456-4456-8456-{pos:012x}'
                recording = f'{number:08x}-4567-4567-8567-{pos:012x}'
                self.groups[group] = (row, release, track, recording)
                self.releases[release] = (row, group, track, recording)

    def get(self, kind, ident, **params):
        self.calls.append((kind, ident, params))
        if kind == 'artist' and ident in self.by_id:
            row = self.by_id[ident]
            return {'id': ident, 'name': row['name'], 'type': row['type']}
        if kind == 'release-group' and not ident:
            artist = params.get('artist')
            groups = [{'id': gid, 'primary-type': 'Album', 'title': f'Work {i}'}
                for i, (row, _release, _track, _rec) in enumerate(self.groups.values())
                if row['mbid'] == artist
                for gid in [list(self.groups)[i]]]
            return {'release-group-count': len(groups),
                    'release-group-offset': 0, 'release-groups': groups}
        if kind == 'release-group' and ident in self.groups:
            row, release, track, rec = self.groups[ident]
            credit_id = '99999999-9999-4999-8999-999999999999' if self.wrong_group else row['mbid']
            result = {'id': ident, 'title': 'Verified work', 'primary-type': 'Album',
                      'artist-credit': [{'artist': {'id': credit_id}}]}
            if self.missing_credit:
                result.pop('artist-credit')
            if not self.no_releases:
                result['releases'] = [{'id': release, 'status': 'Official'}]
            return result
        if kind == 'release' and ident in self.releases:
            row, group, track, rec = self.releases[ident]
            return {'id': ident, 'status': 'Official', 'release-group': {'id': group},
                    'media': [{'position': 1, 'tracks': [{
                        'id': track, 'position': 1,
                        'artist-credit': [{'artist': {'id': row['mbid']}}],
                        'recording': {'id': rec, 'title': 'Test recording'},
                    }]}]}
        raise AssertionError(f'Unexpected request {kind}:{ident}:{params}')


class IdentityExitAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
        cls.seeds = validate_artists(cls.manifest)

    def test_exactly_twenty_valid_and_unique_real_artist_seeds(self):
        self.assertEqual(20, len(self.seeds))
        self.assertEqual(20, len({row['mbid'] for row in self.seeds}))
        self.assertEqual(None, validate_artists({**self.manifest,
            'artists': self.manifest['artists'][:-1]}))
        self.assertEqual(None, validate_artists({**self.manifest,
            'artists': [*self.manifest['artists'][:-1], self.manifest['artists'][0]]}))
        self.assertEqual(None, validate_artists({**self.manifest,
            'artists': [*self.manifest['artists'][:-1],
                        {**self.manifest['artists'][-1], 'source': 'https://evil.test'}]}))

    def test_complete_bounded_source_graph_produces_20_60_30_only(self):
        client = SourceFixture(self.seeds)
        report = audit(self.seeds, client.get)
        self.assertEqual({'artists': 20, 'releases': 60, 'relationships': 30}, report['target'])
        self.assertEqual(20, len(report['artist_checks']))
        self.assertEqual(60, len(report['release_group_checks']))
        self.assertEqual(30, len(report['relationship_checks']))
        self.assertEqual(110, len(client.calls))
        self.assertTrue(all(item['status'] == 'PASS' for section in (
            'artist_checks', 'release_group_checks', 'relationship_checks')
            for item in report[section]))
        self.assertEqual(0, report['fail_count'])
        self.assertEqual(0, report['error_count'])
        self.assertEqual(0, report['inconclusive_count'])
        self.assertEqual(110, report['pass_count'])
        self.assertFalse(report['android_ui_verified'])
        self.assertFalse(report['physical_smoke_verified'])
        self.assertFalse(report['changes_canonical_identities'])

    def test_wrong_artist_group_credit_never_becomes_verified_relationship(self):
        report = audit(self.seeds, SourceFixture(self.seeds, wrong_group=True).get)
        self.assertEqual(0, sum(item['status'] == 'PASS' for item in report['release_group_checks']))
        self.assertEqual(60, sum(item['status'] == 'FAIL' for item in report['release_group_checks']))
        self.assertEqual(0, sum(item['status'] == 'PASS' for item in report['relationship_checks']))
        self.assertEqual(0, report['error_count'])

    def test_missing_group_credit_is_inconclusive_not_source_absence(self):
        report = audit(self.seeds, SourceFixture(self.seeds, missing_credit=True).get)
        self.assertEqual(60, sum(item['status'] == 'INCONCLUSIVE'
            for item in report['release_group_checks']))
        self.assertEqual(0, report['fail_count'])

    def test_absent_official_edition_cannot_prove_recording_relation(self):
        report = audit(self.seeds, SourceFixture(self.seeds, no_releases=True).get)
        self.assertEqual(10, sum(item['status'] == 'PASS'
            for item in report['relationship_checks']))
        self.assertEqual(20, sum(item['status'] == 'INCONCLUSIVE'
            for item in report['relationship_checks']))


if __name__ == '__main__':
    unittest.main()

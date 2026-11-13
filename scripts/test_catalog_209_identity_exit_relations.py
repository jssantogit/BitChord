"""Proof that the targeted remainder reuses exact Group/Release IDs only."""
import json
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from catalog_209_identity_exit_relations import validate_targets, audit_relations

PATH = Path(__file__).resolve().parents[1] / (
    'docs/plans/catalog-209-identity-exit-relationships.json')


class Transport:
    def __init__(self, targets, group_wrong=False, release_wrong=False,
                 no_track_credit=False, missing_edition=False):
        self.targets = targets
        self.groups = {x['group_mbid']: x for x in targets}
        self.releases = {x['release_mbid']: x for x in targets}
        self.group_wrong = group_wrong
        self.release_wrong = release_wrong
        self.no_track_credit = no_track_credit
        self.missing_edition = missing_edition
        self.calls = []

    def get(self, kind, ident, **params):
        self.calls.append((kind, ident, params))
        if kind == 'release-group' and ident in self.groups:
            row = self.groups[ident]
            artist = ('99999999-9999-4999-8999-999999999999' if self.group_wrong
                      else row['artist_mbid'])
            return {'id': ident, 'title': 'Verified source work',
                    'primary-type': row['primary_type'],
                    'artist-credit': [{'artist': {'id': artist}}],
                    'releases': [] if self.missing_edition else
                        [{'id': row['release_mbid'], 'status': 'Official'}]}
        if kind == 'release' and ident in self.releases:
            row = self.releases[ident]
            artist = row['artist_mbid']
            track = {'id': '11111111-1111-4111-8111-111111111111',
                     'position': 1,
                     'recording': {'id': '22222222-2222-4222-8222-222222222222'}}
            if not self.no_track_credit:
                track['artist-credit'] = [{'artist': {'id': artist}}]
            return {'id': ident, 'status': 'Official',
                    'release-group': {'id': 'ffffffff-ffff-4fff-8fff-ffffffffffff'
                                      if self.release_wrong else row['group_mbid']},
                    'media': [{'tracks': [track], 'position': 1}]}
        raise AssertionError(f'unexpected request: {kind} {ident} {params}')


class RelationExitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(PATH.read_text(encoding='utf-8'))
        cls.targets = validate_targets(cls.manifest)

    def test_exactly_ten_frozen_source_verified_relationship_seeds(self):
        self.assertEqual(10, len(self.targets))
        self.assertEqual(10, len({x['artist_mbid'] for x in self.targets}))
        self.assertIsNone(validate_targets({**self.manifest,
            'relationships': self.targets[:-1]}))
        self.assertIsNone(validate_targets({**self.manifest,
            'relationships': self.targets[:-1] + self.targets[:1]}))
        self.assertIsNone(validate_targets({**self.manifest,
            'relationships': self.targets[:-1] + [{
                **self.targets[-1], 'release_mbid': 'bad-id'}]}))

    def test_verified_sources_prove_exactly_thirty_relations_with_twenty_gets(self):
        client = Transport(self.targets)
        report = audit_relations(self.targets, client.get)
        self.assertEqual(30, len(report['relationship_checks']))
        self.assertEqual(30, report['pass_count'])
        self.assertEqual(0, report['fail_count'])
        self.assertEqual(0, report['inconclusive_count'])
        self.assertEqual(0, report['error_count'])
        self.assertEqual(20, len(client.calls))
        self.assertFalse(report['physical_smoke_verified'])
        self.assertFalse(report['android_ui_verified'])

    def test_wrong_artist_group_credit_fails_closed_without_edition_requests(self):
        client = Transport(self.targets, group_wrong=True)
        report = audit_relations(self.targets, client.get)
        self.assertEqual(10, report['fail_count'])
        self.assertEqual(0, report['pass_count'])
        self.assertEqual(10, len(client.calls))

    def test_wrong_release_group_is_a_source_contradiction_not_a_track_proof(self):
        report = audit_relations(self.targets,
                                 Transport(self.targets, release_wrong=True).get)
        self.assertEqual(10, report['fail_count'])
        self.assertEqual(10, report['pass_count'])
        self.assertEqual(10, report['inconclusive_count'])

    def test_missing_track_credits_is_inconclusive_not_fabricated_artist_identity(self):
        report = audit_relations(self.targets,
                                 Transport(self.targets, no_track_credit=True).get)
        self.assertEqual(20, report['pass_count'])
        self.assertEqual(10, report['inconclusive_count'])
        self.assertEqual(0, report['fail_count'])

    def test_release_not_present_in_exact_group_cannot_be_claimed_by_title(self):
        client = Transport(self.targets, missing_edition=True)
        report = audit_relations(self.targets, client.get)
        self.assertEqual(10, report['pass_count'])
        self.assertEqual(20, report['inconclusive_count'])
        self.assertEqual(10, len(client.calls))


if __name__ == '__main__':
    unittest.main()

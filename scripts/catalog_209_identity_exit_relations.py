#!/usr/bin/env python3
"""Finish the 30 relation checks from PR #209 without repeating 20/60 source calls.

The ten Artist→ReleaseGroup→Official Release IDs were captured by the
source-only #209 run 43425075506 and are pinned in the manifest. Twenty
additional exact-ID public GETs at most, plus rate-limited retries.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path

from catalog_178_musicbrainz_live_probe import PublicMusicBrainz
from catalog_209_identity_exit_audit import (
    SUPPORTED, group_check, item, release_and_track_checks, valid_id,
)

MANIFEST = Path('docs/plans/catalog-209-identity-exit-relationships.json')


def validate_targets(payload):
    if not isinstance(payload, dict) or payload.get('schema_version') != 1:
        return None
    rows = payload.get('relationships')
    if (not isinstance(rows, list) or len(rows) != 10 or
        payload.get('source_run_id') != 43425075506 or
        payload.get('source_artifact_id') != 14036152195 or
        payload.get('source_head_sha') !=
        '08aa8adf1a4cc19ed0d2642e803f3a3eae7e145f'):
        return None
    artists, groups, releases = set(), set(), set()
    for row in rows:
        if not isinstance(row, dict):
            return None
        name = row.get('name')
        a, g, r = (row.get(k) for k in (
            'artist_mbid', 'group_mbid', 'release_mbid'))
        if (not isinstance(name, str) or not name.strip() or
            not 1 <= len(name) <= 80 or row.get('primary_type') not in SUPPORTED or
            not all(valid_id(v) and v == v.lower() for v in (a, g, r)) or
            a in artists or g in groups or r in releases):
            return None
        artists.add(a)
        groups.add(g)
        releases.add(r)
    return rows


def audit_relations(targets, get):
    # The real artifact+manifest provenance remains immutable; never infer
    # Release parentage from names or a user-facing Apple editorial title.
    if validate_targets({
        'schema_version': 1, 'source_run_id': 43425075506,
        'source_artifact_id': 14036152195,
        'source_head_sha': '08aa8adf1a4cc19ed0d2642e803f3a3eae7e145f',
        'relationships': targets,
    }) is None:
        raise ValueError('invalid or non-frozen exact relation targets')

    artist_edges, release_edges, track_edges = [], [], []
    for seed in targets:
        artist, group, release = (
            seed['artist_mbid'], seed['group_mbid'], seed['release_mbid'])
        group_result, editions = group_check(
            get, artist, group, seed['primary_type'])
        artist_edges.append(item(
            group_result['status'], kind='artist_to_group',
            artist_mbid=artist, group_mbid=group,
            reason=group_result.get('reason')))

        if group_result['status'] == 'PASS' and release in editions:
            edition_result, track_result = release_and_track_checks(
                get, artist, group, release)
            release_edges.append(dict(
                edition_result, kind='release_to_group',
                artist_mbid=artist))
            track_edges.append(dict(
                track_result, kind='track_to_recording',
                artist_mbid=artist))
        else:
            # A missing edition in an otherwise valid Group is NOT proof it
            # belongs somewhere else. Partial nested Releases stay unknown.
            status = 'FAIL' if group_result['status'] == 'FAIL' else (
                'ERROR' if group_result['status'] == 'ERROR' else 'INCONCLUSIVE')
            reason = 'group_credit_unverified' if group_result['status'] != 'PASS' else (
                'official_edition_not_present_in_group_lookup')
            release_edges.append(item(
                status, kind='release_to_group', artist_mbid=artist,
                release_mbid=release, group_mbid=group, reason=reason))
            track_edges.append(item(
                'INCONCLUSIVE', kind='track_to_recording', artist_mbid=artist,
                group_mbid=group, release_mbid=release,
                reason='cannot_infer_track_without_verified_edition'))

    result = artist_edges + release_edges + track_edges
    return {
        'schema_version': 1,
        'scope': 'PUBLIC_API_NOT_ANDROID_UI',
        'observed_utc': datetime.now(timezone.utc).isoformat(),
        'head_sha': os.environ.get('GITHUB_SHA'),
        'source_first_pass_run_id': 43425075506,
        'source_first_pass_artifact_id': 14036152195,
        'relationship_checks': result,
        'pass_count': sum(r['status'] == 'PASS' for r in result),
        'fail_count': sum(r['status'] == 'FAIL' for r in result),
        'inconclusive_count': sum(r['status'] == 'INCONCLUSIVE' for r in result),
        'error_count': sum(r['status'] == 'ERROR' for r in result),
        'android_ui_verified': False,
        'physical_smoke_verified': False,
        'changes_canonical_identities': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=MANIFEST)
    parser.add_argument('--output', type=Path,
                        default=Path('catalog-209-identity-exit-relations.json'))
    args = parser.parse_args()
    try:
        payload = json.loads(args.manifest.read_text(encoding='utf-8'))
        targets = validate_targets(payload)
        if targets is None:
            raise ValueError('invalid ten frozen exact MBID triples')
    except (OSError, ValueError) as exc:
        parser.error(f'Invalid relation manifest: {type(exc).__name__}')

    client = PublicMusicBrainz(
        interval=1.1, timeout=12.0, max_http_requests=48)
    result = audit_relations(targets, client.get)
    result['http_request_count'] = len(client.requests)
    result['max_http_requests'] = client.max_http_requests
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n',
        encoding='utf-8')
    print(json.dumps({k: result[k] for k in (
        'pass_count', 'fail_count', 'inconclusive_count',
        'error_count', 'http_request_count', 'head_sha')},
        ensure_ascii=False))
    return 2 if result['fail_count'] or result['error_count'] else 0


if __name__ == '__main__':
    raise SystemExit(main())

#!/usr/bin/env python3
"""Bounded, repeatable MusicBrainz source diagnostics without a device.

Source facts are NOT a passing Android UI, a corrected release shelf, an
available cover image, a MusicBrainz edit, or a playable recording. Add a
stable exact-MBID case to the JSON manifest to reproduce a metadata problem.
Uses the mature #178 HTTP client (1.1s spacing, safe retry, user agent).
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from pathlib import Path
import json
import os
import re
from urllib.error import HTTPError, URLError

from catalog_178_musicbrainz_live_probe import PublicMusicBrainz

MBID = re.compile(r'^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$', re.I)
CASE_ID = re.compile(r'^[A-Z0-9][A-Z0-9-]{2,70}$')
MANIFEST = Path('docs/plans/catalog-209-musicbrainz-diagnostics-cases.json')
MAX_CASES = 3
MAX_RELEASES = 2


def validate_manifest(manifest: object) -> list[dict] | None:
    if not isinstance(manifest, dict) or manifest.get('schema_version') != 1:
        return None
    cases = manifest.get('cases')
    if not isinstance(cases, list) or not 1 <= len(cases) <= MAX_CASES:
        return None
    ids = set()
    for case in cases:
        if not isinstance(case, dict):
            return None
        cid = case.get('id')
        name = case.get('artist_name')
        releases = case.get('release_mbids')
        if (not isinstance(cid, str) or not CASE_ID.fullmatch(cid) or cid in ids or
            not isinstance(name, str) or not 1 <= len(name) <= 80 or not name.strip() or
            any(not isinstance(case.get(key), str) or
                not MBID.fullmatch(case[key]) for key in
                ('artist_mbid', 'group_mbid', 'recording_mbid')) or
            case.get('expected_primary_type') not in ('Album', 'EP', 'Single') or
            not isinstance(case.get('recording_title_contains'), str) or
            not 2 <= len(case['recording_title_contains']) <= 80 or
            not isinstance(releases, list) or not 1 <= len(releases) <= MAX_RELEASES or
            any(not isinstance(rid, str) or not MBID.fullmatch(rid) for rid in releases) or
            len(set(r.lower() for r in releases)) != len(releases)):
            return None
        ids.add(cid)
    return cases


def _valid_mbid(value: object) -> bool:
    return isinstance(value, str) and MBID.fullmatch(value) is not None


def _result(check: str, status: str, evidence: dict) -> dict:
    return {'check': check, 'status': status,
            'evidence_type': 'PUBLIC_API_ONLY', 'evidence': evidence}


# A tiny *schema* probe, not a guest-membership assertion. MusicBrainz's
# track_artist browse is not equivalent to an exact ReleaseGroup artist lookup.
# NewJeans has independently observed credits on other artists' releases; the
# public example makes it possible to inspect nested credit availability.
GUEST_BROWSE_ARTIST_MBID = '49204a7a-ed85-407a-828f-6fd46f1d8126'


def probe_guest_browse_shape(get) -> dict:
    """One extra rate-limited public GET. Never infer group ownership from omissions."""
    name = 'guest_track_artist_browse_shape'
    try:
        payload = get('release', '', track_artist=GUEST_BROWSE_ARTIST_MBID,
                      inc='release-groups+artist-credits+recordings+labels+isrcs',
                      limit='5', offset='0')
    except (HTTPError, URLError, TimeoutError, OSError, ValueError, KeyError,
            TypeError, UnicodeError) as exc:
        return _result(name, 'ERROR', {
            'provider_unavailable': True, 'error_kind': type(exc).__name__})
    if not isinstance(payload, dict) or not isinstance(payload.get('releases'), list):
        return _result(name, 'INCONCLUSIVE', {'unexpected_browse_response': True})
    releases = payload['releases']
    if len(releases) > 5 or any(not isinstance(r, dict) for r in releases):
        return _result(name, 'INCONCLUSIVE', {'unexpected_browse_response': True})
    groups = [r.get('release-group') for r in releases]
    observed_ids = list(dict.fromkeys(group['id'].lower()
        for group in groups if isinstance(group, dict) and
        _valid_mbid(group.get('id'))))
    group_credit = sum(isinstance(group, dict) and
                       isinstance(group.get('artist-credit'), list)
                       for group in groups)
    edition_credit = sum(isinstance(r.get('artist-credit'), list) for r in releases)
    # These booleans describe which JSON subobjects are present, not whether
    # any single ArtistId is truly credited to the nested ReleaseGroup.
    recording_credit = 0
    for release in releases:
        has_recording_credit = any(
            isinstance(track.get('recording'), dict) and
            isinstance(track['recording'].get('artist-credit'), list)
            for medium in release.get('media', [])
            if isinstance(medium, dict)
            for track in medium.get('tracks', [])
            if isinstance(track, dict)
        )
        recording_credit += int(has_recording_credit)
    return _result(name, 'OBSERVED' if observed_ids else 'INCONCLUSIVE', {
        'artist_mbid': GUEST_BROWSE_ARTIST_MBID,
        'server_total': payload.get('release-count'),
        'sampled_releases': len(releases),
        'sampled_release_group_mbids': observed_ids[:5],
        'release_artist_credit_present': edition_credit,
        'group_artist_credit_present': group_credit,
        'recording_artist_credit_present': recording_credit,
        'group_membership_verified': False,
    })


def diagnose_case(case: dict, get) -> list[dict]:
    """Eight fixed, independent queries; never interpret search miss as absence."""
    if validate_manifest({'schema_version': 1, 'cases': [case]}) is None:
        raise ValueError('Invalid or unbounded MusicBrainz case')
    rows = []

    def check(name: str, invoke, interpret):
        try:
            payload = invoke()
            status, facts = interpret(payload)
            rows.append(_result(name, status, facts))
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, KeyError,
                TypeError, UnicodeError) as exc:
            # Transient API failure is not a negative database assertion.
            rows.append(_result(name, 'ERROR', {'provider_unavailable': True,
                                                'error_kind': type(exc).__name__}))

    artist, group = case['artist_mbid'], case['group_mbid']

    def artist_check(payload):
        valid = (isinstance(payload, dict) and payload.get('id') == artist and
                 isinstance(payload.get('name'), str) and
                 payload['name'].casefold() == case['artist_name'].casefold())
        return ('PASS' if valid else 'FAIL',
                {'expected_artist_mbid': artist,
                 'observed_artist_mbid': payload.get('id') if isinstance(payload, dict) else None,
                 'observed_name': payload.get('name') if isinstance(payload, dict) else None})

    check('artist_identity', lambda: get('artist', artist), artist_check)

    def group_check(payload):
        valid = (isinstance(payload, dict) and payload.get('id') == group and
                 isinstance(payload.get('title'), str) and payload['title'].strip() and
                 payload.get('primary-type') == case['expected_primary_type'])
        credit = payload.get('artist-credit') if isinstance(payload, dict) else None
        # MusicBrainz artist-credit arrays may include string join phrases
        # between structured name-credit objects. Names alone prove nothing.
        ids = []
        malformed = False
        if isinstance(credit, list):
            for item in credit:
                if isinstance(item, str):
                    continue
                ref = item.get('artist') if isinstance(item, dict) else None
                mbid = ref.get('id') if isinstance(ref, dict) else None
                if not _valid_mbid(mbid):
                    malformed = True
                else:
                    ids.append(mbid.lower())
        # Missing/partial credit cannot certify the artist relationship;
        # an explicit credit for a different MBID contradicts this case.
        complete_credit = isinstance(credit, list) and bool(ids) and not malformed
        if not valid:
            status = 'FAIL'
        elif not complete_credit:
            status = 'INCONCLUSIVE'
        elif artist.lower() in ids:
            status = 'PASS'
        else:
            status = 'FAIL'
        return (status,
                {'group_mbid': payload.get('id') if isinstance(payload, dict) else None,
                 'title': payload.get('title') if isinstance(payload, dict) else None,
                 'primary_type': payload.get('primary-type') if isinstance(payload, dict) else None,
                 'expected_primary_type': case['expected_primary_type'],
                 'first_release_date': payload.get('first-release-date') if isinstance(payload, dict) else None,
                 'secondary_types': payload.get('secondary-types') if isinstance(payload, dict) else None,
                 'expected_artist_mbid': artist,
                 'observed_artist_mbids': list(dict.fromkeys(ids)),
                 'group_credit_verified': complete_credit and artist.lower() in ids,
                 'group_credit_complete': complete_credit})

    check('group_identity', lambda: get('release-group', group,
                                       inc='artist-credits'), group_check)

    def browse_check(payload):
        if not isinstance(payload, dict):
            return 'FAIL', {'invalid_browse_payload': True}
        raw = payload.get('release-groups')
        total, offset = payload.get('release-group-count'), payload.get('release-group-offset')
        if (not isinstance(raw, list) or type(total) is not int or
            type(offset) is not int or offset != 0 or total < 0 or
            len(raw) > 100 or len(raw) > total or
            any(not isinstance(x, dict) or not _valid_mbid(x.get('id')) for x in raw)):
            return 'FAIL', {'invalid_browse_contract': True}
        ids = [x['id'].lower() for x in raw]
        valid = group.lower() in ids
        return ('PASS' if valid else 'INCONCLUSIVE' if len(raw) < total else 'FAIL',
                {'server_total': total, 'raw_rows_consumed': len(raw),
                 'browse_complete': len(raw) == total,
                 'expected_group_seen': valid,
                 'next_offset': len(raw) if len(raw) < total else None})

    check('artist_group_browse', lambda: get('release-group', '', artist=artist,
                                          limit='100', offset='0'), browse_check)

    def release_check(payload, release):
        if not isinstance(payload, dict):
            return 'FAIL', {'release_mbid': release, 'invalid_release': True}
        media = payload.get('media')
        track_ids = []
        recording_ids = []
        complete = isinstance(media, list) and len(media) > 0
        if complete:
            for medium in media:
                if not isinstance(medium, dict) or not isinstance(medium.get('tracks'), list):
                    complete = False
                    break
                entries = medium['tracks']
                medium_count = medium.get('track-count')
                if type(medium_count) is int and medium_count != len(entries):
                    complete = False
                for track in entries:
                    if not isinstance(track, dict):
                        complete = False
                        continue
                    tid = track.get('id')
                    rec = track.get('recording')
                    rid = rec.get('id') if isinstance(rec, dict) else None
                    if not _valid_mbid(tid) or not _valid_mbid(rid):
                        complete = False
                    else:
                        track_ids.append(tid.lower())
                        recording_ids.append(rid.lower())
        if not track_ids or len(track_ids) != len(set(track_ids)):
            complete = False
        parent = payload.get('release-group')
        actual_group = parent.get('id') if isinstance(parent, dict) else None
        valid = (payload.get('id') == release and actual_group == group and
                 payload.get('status') == 'Official' and complete)
        return ('PASS' if valid else 'FAIL',
                {'release_mbid': release, 'observed_release_mbid': payload.get('id'),
                 'observed_group_mbid': actual_group, 'status': payload.get('status'),
                 'complete_track_graph': complete, 'track_count': len(track_ids),
                 'unique_recording_count': len(set(recording_ids)),
                 'sample_track_mbid': track_ids[0] if track_ids else None,
                 'sample_recording_mbid': recording_ids[0] if recording_ids else None,
                 'cover_art_front_hint': (payload.get('cover-art-archive') or {}).get('front')
                 if isinstance(payload.get('cover-art-archive'), dict) else None,
                 'track_and_recording_ids_distinct_scopes': True})

    for index, release in enumerate(case['release_mbids'], start=1):
        check(f'release_{index}', lambda release=release: get(
            'release', release, inc='recordings+artist-credits+release-groups'),
            lambda payload, release=release: release_check(payload, release))

    def recording_check(payload):
        valid = (isinstance(payload, dict) and
                 payload.get('id') == case['recording_mbid'] and
                 isinstance(payload.get('title'), str) and
                 case['recording_title_contains'].casefold() in payload['title'].casefold())
        return ('PASS' if valid else 'FAIL',
                {'recording_mbid': payload.get('id') if isinstance(payload, dict) else None,
                 'title': payload.get('title') if isinstance(payload, dict) else None})

    check('recording_identity', lambda: get('recording', case['recording_mbid'],
                                          inc='artist-credits'), recording_check)

    def index_check(payload, key, expected):
        if not isinstance(payload, dict):
            return 'FAIL', {'invalid_index_payload': True}
        count, results = payload.get('count'), payload.get(key)
        if (type(count) is not int or count < 0 or not isinstance(results, list) or
            len(results) > 25 or len(results) > count or
            any(not isinstance(row, dict) or not _valid_mbid(row.get('id'))
                for row in results)):
            return 'FAIL', {'invalid_search_contract': True}
        found = expected in {row['id'].lower() for row in results}
        # A missing hit in a Lucene index does NOT prove absent source data.
        return ('PASS' if found else 'INCONCLUSIVE',
                {'server_total': count, 'returned': len(results),
                 'expected_mbid_seen': found, 'confirmed_missing': False})

    check('artist_search_index', lambda: get('artist', '',
          query=case['artist_name'], limit='25', dismax='true'),
          lambda payload: index_check(payload, 'artists', artist))
    check('group_search_index', lambda: get('release-group', '',
          query='rgid:' + group, limit='25', dismax='false'),
          lambda payload: index_check(payload, 'release-groups', group))
    return rows


def make_report(manifest: dict, client) -> dict:
    cases = validate_manifest(manifest)
    if cases is None:
        raise ValueError('invalid manifest')
    start = datetime.now(timezone.utc).isoformat()
    observed = [
        {'case': case['id'], 'observations': diagnose_case(case, client.get)}
        for case in cases
    ]
    rows = [row for case in observed for row in case['observations']]
    guest_shape = probe_guest_browse_shape(client.get)
    return {
        'schema_version': 1,
        'scope': 'PUBLIC_API_NOT_ANDROID_UI',
        'observed_utc': start,
        'head_sha': os.environ.get('GITHUB_SHA'),
        'cases': observed,
        'guest_track_artist_shape': guest_shape,
        'extra_guest_shape_requests': 1,
        'pass_count': sum(x['status'] == 'PASS' for x in rows),
        'fail_count': sum(x['status'] == 'FAIL' for x in rows),
        'error_count': sum(x['status'] == 'ERROR' for x in rows) +
            int(guest_shape['status'] == 'ERROR'),
        'inconclusive_count': sum(x['status'] == 'INCONCLUSIVE' for x in rows) +
            int(guest_shape['status'] == 'INCONCLUSIVE'),
        'http_request_count': len(getattr(client, 'requests',
                                          getattr(client, 'calls', []))),
        'android_ui_verified': False,
        'local_library_verified': False,
        'provider_priority_changed': False,
        'limitations': [
            'Public API only: does not execute Kotlin parser, Room, search UI or device',
            'Search index empty result cannot prove missing release',
            'track_artist browse shape does not establish ReleaseGroup ownership',
            'Cover presence flag cannot prove UI image load',
            'Physical smoke remains separate and only requested when needed',
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=MANIFEST)
    parser.add_argument('--output', type=Path, default=Path('catalog-209-musicbrainz-diagnostics.json'))
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
        if validate_manifest(manifest) is None:
            raise ValueError('manifest invalid')
    except (OSError, ValueError) as exc:
        parser.error(f'Cannot read bounded public manifest: {type(exc).__name__}')
    client = PublicMusicBrainz(interval=1.1, timeout=22.0)
    report = make_report(manifest, client)
    args.output.write_text(json.dumps(report, ensure_ascii=False,
                                      indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print(json.dumps({key: report[key] for key in
                     ('scope', 'pass_count', 'fail_count', 'error_count',
                      'inconclusive_count', 'http_request_count')}, indent=2))
    return 0 if report['fail_count'] == 0 and report['error_count'] == 0 else 2


if __name__ == '__main__':
    raise SystemExit(main())

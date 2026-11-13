#!/usr/bin/env python3
"""One bounded public-source acceptance sweep for BitChord PR #209.

The 20 artist IDs are frozen. Up to three Album/EP/Single Release Groups per
artist are selected from a real artist-MBID browse, then verified by exact
Group ID and structured MBID credits. No title joins, no DB writes, no playback
claim, no device smoke. Source defects are not automatically app defects.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
from urllib.error import HTTPError, URLError

from catalog_178_musicbrainz_live_probe import PublicMusicBrainz

MANIFEST = Path('docs/plans/catalog-209-identity-exit-artists.json')
MBID = re.compile(r'^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$', re.I)
SUPPORTED = {'Album', 'EP', 'Single'}
SOURCE = 'https://musicbrainz.org/artist/'
FAILURES = (HTTPError, URLError, TimeoutError, OSError, ValueError,
            KeyError, TypeError, UnicodeError)


def valid_id(value):
    return isinstance(value, str) and bool(MBID.fullmatch(value))


def validate_artists(manifest):
    if not isinstance(manifest, dict) or manifest.get('schema_version') != 1:
        return None
    rows = manifest.get('artists')
    if not isinstance(rows, list) or len(rows) != 20:
        return None
    seen_names, seen_ids = set(), set()
    for row in rows:
        if not isinstance(row, dict):
            return None
        name, mbid = row.get('name'), row.get('mbid')
        if (not isinstance(name, str) or name != name.strip() or
            not 1 <= len(name) <= 80 or not valid_id(mbid) or
            mbid.lower() != mbid or row.get('type') not in ('Group', 'Person') or
            row.get('source') != SOURCE + mbid or
            name.casefold() in seen_names or mbid in seen_ids):
            return None
        seen_names.add(name.casefold())
        seen_ids.add(mbid)
    return rows


def checked_credits(credits):
    """None = missing/incomplete, not evidence of absence or different ownership."""
    if not isinstance(credits, list):
        return None
    ids = []
    for item in credits:
        if isinstance(item, str):
            continue
        artist = item.get('artist') if isinstance(item, dict) else None
        mid = artist.get('id') if isinstance(artist, dict) else None
        if not valid_id(mid):
            return None
        ids.append(mid.lower())
    return list(dict.fromkeys(ids)) if ids else None


def item(status, **facts):
    return {'status': status, 'evidence_type': 'PUBLIC_API_ONLY', **facts}


def attempt(get, kind, ident, **params):
    try:
        data = get(kind, ident, **params)
        return data if isinstance(data, dict) else None, None
    except FAILURES as exc:
        return None, type(exc).__name__


def browse_three(get, artist):
    data, error = attempt(get, 'release-group', '', artist=artist,
                          limit='100', offset='0')
    if error:
        return [], 'ERROR', error
    if not isinstance(data, dict):
        return [], 'INCONCLUSIVE', 'invalid_browse_response'
    groups = data.get('release-groups')
    count, offset = data.get('release-group-count'), data.get('release-group-offset')
    if (not isinstance(groups, list) or type(count) is not int or count < 0 or
        type(offset) is not int or offset != 0 or len(groups) > 100 or
        len(groups) > count):
        return [], 'INCONCLUSIVE', 'invalid_browse_contract'
    # A raw browse may return some ineligible or malformed rows. That does
    # not prove they are not MusicBrainz groups, nor can they be guessed into IDs.
    choices, seen = [], set()
    for row in groups:
        if not isinstance(row, dict) or not valid_id(row.get('id')) or (
            row.get('primary-type') not in SUPPORTED
        ):
            continue
        mid = row['id'].lower()
        if mid not in seen:
            choices.append((mid, row['primary-type']))
            seen.add(mid)
        if len(choices) == 3:
            break
    return choices, 'PASS', None


def group_check(get, artist_id, group_id, expected_kind):
    data, err = attempt(get, 'release-group', group_id,
                        inc='artist-credits+releases')
    if err:
        return item('ERROR', group_mbid=group_id, reason=err), []
    if not isinstance(data, dict):
        return item('INCONCLUSIVE', group_mbid=group_id,
                    reason='missing_or_invalid_group'), []
    if data.get('id', '').lower() != group_id or data.get('primary-type') != expected_kind:
        return item('FAIL', group_mbid=group_id,
                    observed_mbid=data.get('id'), expected_type=expected_kind,
                    observed_type=data.get('primary-type'),
                    reason='source_group_identity_or_type_contradiction'), []
    credits = checked_credits(data.get('artist-credit'))
    if credits is None:
        return item('INCONCLUSIVE', group_mbid=group_id,
                    reason='group_artist_credit_incomplete'), []
    if artist_id not in credits:
        return item('FAIL', group_mbid=group_id,
                    observed_artist_mbids=credits,
                    reason='group_artist_credit_contradicts_browse'), []
    releases = data.get('releases')
    editions = [row['id'].lower() for row in (releases if isinstance(releases, list) else [])
                if isinstance(row, dict) and valid_id(row.get('id')) and
                row.get('status') == 'Official']
    return item('PASS', group_mbid=group_id, primary_type=expected_kind,
                artist_mbid=artist_id, official_release_candidates=editions[:3]), editions


def release_and_track_checks(get, artist_id, group_id, release_id):
    release, err = attempt(get, 'release', release_id,
                           inc='recordings+artist-credits+release-groups')
    if err:
        return (item('ERROR', reason=err, release_mbid=release_id, group_mbid=group_id),
                item('INCONCLUSIVE', reason='release_unavailable_for_track_proof',
                     release_mbid=release_id, group_mbid=group_id))
    if not isinstance(release, dict):
        return (item('INCONCLUSIVE', reason='missing_release',
                     release_mbid=release_id, group_mbid=group_id),
                item('INCONCLUSIVE', reason='missing_release_track_graph',
                     release_mbid=release_id, group_mbid=group_id))
    parent = release.get('release-group')
    actual = parent.get('id') if isinstance(parent, dict) else None
    if release.get('id') != release_id or actual != group_id or release.get('status') != 'Official':
        return (item('FAIL', reason='release_to_group_or_status_contradiction',
                     release_mbid=release_id, group_mbid=group_id,
                     observed_group_mbid=actual),
                item('INCONCLUSIVE', reason='release_not_verified',
                     release_mbid=release_id, group_mbid=group_id))
    release_result = item('PASS', release_mbid=release_id, group_mbid=group_id)
    for medium in release.get('media', []) if isinstance(release.get('media'), list) else []:
        if not isinstance(medium, dict):
            continue
        for track in medium.get('tracks', []) if isinstance(medium.get('tracks'), list) else []:
            if not isinstance(track, dict):
                continue
            rec = track.get('recording')
            recording_id = rec.get('id') if isinstance(rec, dict) else None
            if not valid_id(track.get('id')) or not valid_id(recording_id):
                continue
            credits = checked_credits(track.get('artist-credit'))
            if credits is None and isinstance(rec, dict):
                credits = checked_credits(rec.get('artist-credit'))
            if credits is None or artist_id not in credits:
                continue
            return release_result, item(
                'PASS', release_mbid=release_id, group_mbid=group_id,
                track_mbid=track['id'].lower(), recording_mbid=recording_id.lower(),
                credited_artist_mbids=credits, verified_track_recording_edge=True)
    return release_result, item(
        'INCONCLUSIVE', reason='no_complete_matching_track_artist_credit',
        release_mbid=release_id, group_mbid=group_id)


def audit(artists, get):
    if validate_artists({'schema_version': 1, 'artists': artists}) is None:
        raise ValueError('Expected exactly 20 distinct official MusicBrainz artist IDs')
    artist_checks, group_checks, relations = [], [], []
    first_groups = []
    for seed in artists:
        mid = seed['mbid']
        artist, err = attempt(get, 'artist', mid)
        if err:
            verdict = item('ERROR', artist=seed['name'], mbid=mid, reason=err)
        elif not isinstance(artist, dict):
            verdict = item('INCONCLUSIVE', artist=seed['name'], mbid=mid,
                           reason='artist_source_unavailable')
        elif (artist.get('id') != mid or artist.get('name', '').casefold() !=
              seed['name'].casefold() or artist.get('type') != seed['type']):
            verdict = item('FAIL', artist=seed['name'], mbid=mid,
                           observed_id=artist.get('id'),
                           observed_name=artist.get('name'),
                           observed_type=artist.get('type'))
        else:
            verdict = item('PASS', artist=seed['name'], mbid=mid,
                           type=seed['type'], source=seed['source'])
        artist_checks.append(verdict)
        choices, browse_status, reason = browse_three(get, mid)
        selected = []
        for position in range(3):
            if position >= len(choices):
                selected.append((item(
                    'INCONCLUSIVE' if browse_status != 'ERROR' else 'ERROR',
                    artist_mbid=mid, slot=position + 1,
                    reason=reason or 'fewer_than_three_typed_groups_in_first_browse'), []))
                continue
            group_id, kind = choices[position]
            result, editions = group_check(get, mid, group_id, kind)
            result['artist_mbid'] = mid
            result['slot'] = position + 1
            selected.append((result, editions))
        group_checks.extend(x[0] for x in selected)
        # Exactly one relationship cohort per one of the first 10 artists.
        if len(first_groups) < 10:
            first_groups.append((mid, selected[0]))
    # 10 Artist->Group, 10 Release->Group, 10 Track->Recording/credit.
    for artist_id, (group_verdict, editions) in first_groups:
        status = group_verdict['status']
        group_id = group_verdict.get('group_mbid')
        relations.append(item(status, kind='artist_to_group',
                              artist_mbid=artist_id, group_mbid=group_id,
                              reason=group_verdict.get('reason')))
    for artist_id, (group_verdict, editions) in first_groups:
        group_id = group_verdict.get('group_mbid')
        if group_verdict['status'] != 'PASS' or not editions:
            status = ('FAIL' if group_verdict['status'] == 'FAIL'
                      else 'ERROR' if group_verdict['status'] == 'ERROR'
                      else 'INCONCLUSIVE')
            rel = item(status, artist_mbid=artist_id, group_mbid=group_id,
                       reason='group_unverified_or_no_official_release')
            relations.append(dict(rel, kind='release_to_group'))
            continue
        rel, track = release_and_track_checks(get, artist_id, group_id, editions[0])
        rel['artist_mbid'] = artist_id
        track['artist_mbid'] = artist_id
        relations.append(dict(rel, kind='release_to_group'))
        # Defer the track row so the final 10 are exactly that relation type.
        group_verdict['_track_relation'] = track
    for artist_id, (group_verdict, editions) in first_groups:
        track = group_verdict.get('_track_relation')
        if track is None:
            track = item('INCONCLUSIVE', kind='track_to_recording',
                         artist_mbid=artist_id,
                         reason='not_enough_verified_release_proof')
        relations.append(dict(track, kind='track_to_recording'))
        group_verdict.pop('_track_relation', None)
    # Keep both inputs and output independently bounded (20 + 60 + 30).
    rows = artist_checks + group_checks + relations
    return {
        'schema_version': 1, 'scope': 'PUBLIC_API_NOT_ANDROID_UI',
        'observed_utc': datetime.now(timezone.utc).isoformat(),
        'head_sha': os.environ.get('GITHUB_SHA'),
        'target': {'artists': 20, 'releases': 60, 'relationships': 30},
        'artist_checks': artist_checks,
        'release_group_checks': group_checks,
        'relationship_checks': relations,
        'pass_count': sum(x['status'] == 'PASS' for x in rows),
        'fail_count': sum(x['status'] == 'FAIL' for x in rows),
        'inconclusive_count': sum(x['status'] == 'INCONCLUSIVE' for x in rows),
        'error_count': sum(x['status'] == 'ERROR' for x in rows),
        'android_ui_verified': False, 'physical_smoke_verified': False,
        'changes_canonical_identities': False,
        'selection_rule': 'first_three_typed_exact_groups_from_bounded_artist_browse',
        'limitations': [
            'Live public API validates source identities only, not Kotlin/Room/Android UI',
            'A single browse page can be partial; missing groups never prove absence',
            'No source response can authorize a playable MediaAsset',
            'Source-side changes or malformed credits do not by themselves prove an app bug',
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=MANIFEST)
    parser.add_argument('--output', type=Path, default=Path('catalog-209-identity-exit-audit.json'))
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
        artists = validate_artists(manifest)
        if artists is None:
            raise ValueError('invalid fixed artist manifest')
    except (OSError, ValueError) as exc:
        parser.error(f'Cannot load fixed manifest: {type(exc).__name__}')
    # 20 artist lookups + 20 browses + 60 exact groups + <=10 releases,
    # with a small retry budget. 1.1 s spacing is enforced by shared transport.
    client = PublicMusicBrainz(interval=1.1, timeout=12.0, max_http_requests=132)
    report = audit(artists, client.get)
    report['http_request_count'] = len(client.requests)
    report['max_http_requests'] = client.max_http_requests
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n',
                           encoding='utf-8')
    summary = {key: report[key] for key in
               ('target', 'pass_count', 'fail_count', 'inconclusive_count',
                'error_count', 'http_request_count', 'head_sha')}
    print(json.dumps(summary, ensure_ascii=False))
    # Print exact public IDs selected so the next/final pass can freeze them
    # without performing fuzzy title searches or accessing private libraries.
    print('SAMPLED_GROUP_MBIDS=' + json.dumps([
        row.get('group_mbid') for row in report['release_group_checks']
    ]))
    return 2 if report['fail_count'] or report['error_count'] else 0


if __name__ == '__main__':
    raise SystemExit(main())

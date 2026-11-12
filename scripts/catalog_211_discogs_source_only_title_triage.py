#!/usr/bin/env python3
"""C-cohort: bounded MusicBrainz title-index checks for unresolved Discogs leads.

This searches MusicBrainz's release title index, scoped to an exact artist MBID.
Even a hit is just a release-group lead, not proof of the same pressing.
Zero hits are never proof that a release is absent from MusicBrainz.
"""
from __future__ import annotations
import datetime as dt
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from catalog_211_discogs_candidate_details import candidate_queue, valid_id, MAX_CANDIDATES
from catalog_211_discogs_corpus_candidates import MBID, normalized
from catalog_211_discogs_public_link_probe import read_json

INDEX = Path('discogs-211-corpus-candidates.json')
EDITIONS = Path('discogs-211-corpus-edition-observations.json')
BARCODE = Path('discogs-211-source-only-barcode-reconciliation.json')
OUTPUT = Path('discogs-211-source-only-title-triage.json')
MAX_REQUESTS = MAX_CANDIDATES * 3  # RG exact, bounded RG prefix, MB exact Release
SEARCH_LIMIT = 10


def title_query(title: object, artist_mbid: object) -> str | None:
    if (not isinstance(title,str) or not 2 <= len(title.strip()) <= 180 or
        not isinstance(artist_mbid,str) or not MBID.fullmatch(artist_mbid) or
        any(ord(ch) < 32 or ord(ch) == 127 for ch in title)):
        return None
    # Escape inside a Lucene quoted phrase, never admit source query syntax.
    escaped = title.strip().replace('\\','\\\\').replace('"','\\"')
    phrase = f'release:"{escaped}" AND arid:{artist_mbid}'
    return 'https://musicbrainz.org/ws/2/release-group/?' + urlencode({
        'query': phrase, 'fmt':'json','limit':SEARCH_LIMIT,
    })



def release_title_query(title: object, artist_mbid: object) -> str | None:
    """Artist-scoped literal MB *Release* search, not a ReleaseGroup title."""
    if title_query(title, artist_mbid) is None:
        return None
    escaped = title.strip().replace('\\', '\\\\').replace('"', '\\"')
    return 'https://musicbrainz.org/ws/2/release/?' + urlencode({
        'query': f'release:"{escaped}" AND arid:{artist_mbid}',
        'fmt': 'json', 'limit': SEARCH_LIMIT,
    })


def title_prefix_query(title: object, artist_mbid: object) -> str | None:
    """A bounded literal prefix lead for slash-separated provider titles.

    Example: Discogs "Social Path / Superbowl (Japanese Ver.)" can be
    searched against the *group* phrase "Social Path" without guessing
    that "Superbowl" translates to "Super Bowl" or creating an MBID.
    It runs only after an exact title miss and never proves same edition.
    """
    if title_query(title, artist_mbid) is None:
        return None
    # Only one slash and at least two distinctive literal words before it.
    if title.count("/") != 1:
        return None
    prefix = title.split("/", 1)[0].strip()
    words = re.findall(r"[^\W_]+", prefix, re.UNICODE)
    if (not 2 <= len(words) <= 4 or
        not all(3 <= len(word) <= 24 for word in words) or
        " ".join(words) != prefix or len(prefix) > 100):
        return None
    phrase = prefix.replace("\\", "\\\\").replace('"', '\\"')
    return "https://musicbrainz.org/ws/2/release-group/?" + urlencode({
        "query": f'releasegroup:"{phrase}" AND arid:{artist_mbid}',
        "fmt": "json", "limit": SEARCH_LIMIT,
    })

def parse_mb_group_search(payload: object, artist_mbid: str) -> list[str] | None:
    if not isinstance(payload,dict) or not MBID.fullmatch(artist_mbid):
        return None
    total,groups = payload.get('count'),payload.get('release-groups')
    # The search API's count is the total matching groups, not the page
    # length. Validate this bounded first page and keep its identity leads;
    # later pages remain unobserved and never justify absence claims.
    if (type(total) is not int or total < 0 or
        not isinstance(groups,list) or len(groups) != min(total, SEARCH_LIMIT)):
        return None
    seen = set()
    for group in groups:
        if not isinstance(group,dict):return None
        gid, title, credits = group.get('id'),group.get('title'),group.get('artist-credit')
        if (not isinstance(gid,str) or not MBID.fullmatch(gid) or
            gid.lower() in seen or not isinstance(title,str) or not title.strip() or
            not isinstance(credits,list) or not 1 <= len(credits) <= 40):
            return None
        seen.add(gid.lower())
        artists=[]
        for credit in credits:
            if isinstance(credit,str):continue
            if not isinstance(credit,dict) or not isinstance(credit.get('artist'),dict):
                return None
            aid=credit['artist'].get('id')
            if not isinstance(aid,str) or not MBID.fullmatch(aid):return None
            artists.append(aid.lower())
        if artists != [artist_mbid.lower()]:return None
    return sorted(seen)


def parse_mb_release_search(payload: object, artist_mbid: str,
                            literal_title: str) -> tuple[list[str], list[str]] | None:
    """Positive exact Release-title hits only; does not prove a Discogs pressing."""
    if (not isinstance(payload, dict) or not MBID.fullmatch(artist_mbid) or
        not isinstance(literal_title, str)):
        return None
    total, rows = payload.get('count'), payload.get('releases')
    if (type(total) is not int or total < 0 or
        not isinstance(rows, list) or len(rows) != min(total, SEARCH_LIMIT)):
        return None
    seen, releases, groups = set(), set(), set()
    expected_title = normalized(literal_title)
    for entry in rows:
        if not isinstance(entry, dict):
            return None
        rid, name, parent, credits = (
            entry.get('id'), entry.get('title'),
            entry.get('release-group'), entry.get('artist-credit')
        )
        if (not isinstance(rid, str) or not MBID.fullmatch(rid) or
            rid.lower() in seen or not isinstance(name, str) or
            normalized(name) != expected_title or
            not isinstance(parent, dict) or
            not isinstance(parent.get('id'), str) or
            not MBID.fullmatch(parent['id']) or
            not isinstance(credits, list) or not 1 <= len(credits) <= 40):
            return None
        seen.add(rid.lower())
        artist_ids = []
        for credit in credits:
            if isinstance(credit, str):
                continue
            if not isinstance(credit, dict) or not isinstance(credit.get('artist'), dict):
                return None
            mbid = credit['artist'].get('id')
            if not isinstance(mbid, str) or not MBID.fullmatch(mbid):
                return None
            artist_ids.append(mbid.lower())
        if artist_ids != [artist_mbid.lower()]:
            return None
        releases.add(rid.lower())
        groups.add(parent['id'].lower())
    return sorted(releases), sorted(groups)


def triage(index: dict, editions: dict, barcode_report: dict, get) -> dict:
    result={'schema_version':1,'observed_utc':dt.datetime.now(dt.timezone.utc).isoformat(),
            'provider_requests':0, 'reviewed_corpus_cases':0,
            'confirmed_source_only_cases':0,
            'warning':'Title index leads are not same-pressing matches; zero hits never prove absence',
            'observations':[]}
    if (not all(isinstance(x,dict) and x.get('schema_version') == 1
                for x in (index,editions,barcode_report)) or
        not isinstance(editions.get('observations'),list) or
        not isinstance(barcode_report.get('observations'),list) or
        len(editions['observations']) > MAX_CANDIDATES or
        len(editions['observations']) != len(barcode_report['observations'])):
        return result
    allowed={(x['source_kind'],x['source_id']):x for x in candidate_queue(index)}
    seen=set()
    for source,prev in zip(editions['observations'],barcode_report['observations']):
        if not isinstance(source,dict) or not isinstance(prev,dict):continue
        key=(source.get('source_kind'),source.get('source_id'))
        row={'source_kind':key[0], 'source_id':key[1],
             'status':'ineligible_or_identity_mismatch','mb_group_candidate_mbids':[],
             'mb_release_candidate_mbids':[], 'reviewed':False, 'confirmed_missing_from_musicbrainz':False}
        result['observations'].append(row)
        expected=allowed.get(key)
        if (key in seen or expected is None or
            any(source.get(x)!=expected[x] for x in
                ('artist','musicbrainz_artist_mbid','discogs_artist_id')) or
            not valid_id(source.get('discogs_release_id')) or
            prev.get('source_kind') != key[0] or prev.get('source_id') != key[1] or
            prev.get('discogs_release_id') != source.get('discogs_release_id') or
            prev.get('reviewed') is not False or
            prev.get('confirmed_missing_from_musicbrainz') is not False):
            continue
        seen.add(key)
        if (source.get('status') != 'edition_metadata_observed_not_adjudicated' or
            source.get('artist_id_matches_relation') is not True or
            prev.get('status') not in (
                'source_barcode_unavailable', 'mb_provider_unavailable',
                'no_exact_barcode_result_not_absence') or
            prev.get('matched_mb_release_mbids') != []):
            continue
        url=title_query(source.get('title'), expected['musicbrainz_artist_mbid'])
        if url is None:
            row['status']='invalid_source_title'
            continue
        if result['provider_requests'] >= MAX_REQUESTS:
            row['status']='request_budget_exhausted'
            continue
        result['provider_requests'] += 1
        try:
            data=get(url)
        except (HTTPError,URLError,OSError,TimeoutError,ValueError):
            row['status']='mb_title_provider_unavailable'
            continue
        matches=parse_mb_group_search(data,expected['musicbrainz_artist_mbid'])
        if matches is None:
            row['status']='invalid_mb_title_search'
        elif matches:
            row['status']='mb_title_index_lead_not_edition'
            row['mb_group_candidate_mbids']=matches
        else:
            # A canonical RG title may differ from one of its Release titles
            # (e.g. ROMANCE : UNTOLD vs ROMANCE : UNTOLD -daydream-).
            # First try the existing safe slash-prefix RG lead, when eligible;
            # otherwise also check the exact Release title for the same artist.
            prefix_url = title_prefix_query(
                source.get('title'), expected['musicbrainz_artist_mbid'])
            if prefix_url is not None:
                if result['provider_requests'] >= MAX_REQUESTS:
                    row['status']='title_prefix_not_checked_budget'
                    continue
                result['provider_requests'] += 1
                try:
                    fallback=get(prefix_url)
                except (HTTPError,URLError,OSError,TimeoutError,ValueError):
                    row['status']='mb_title_provider_unavailable'
                    continue
                candidates=parse_mb_group_search(
                    fallback,expected['musicbrainz_artist_mbid'])
                if candidates is None:
                    row['status']='invalid_mb_title_search'
                    continue
                if candidates:
                    row['status']='mb_title_prefix_lead_not_edition'
                    row['mb_group_candidate_mbids']=candidates
                    continue
            release_url = release_title_query(
                source.get('title'), expected['musicbrainz_artist_mbid'])
            if release_url is None:
                row['status']='invalid_source_title'
                continue
            if result['provider_requests'] >= MAX_REQUESTS:
                row['status']='release_title_not_checked_budget'
                continue
            result['provider_requests'] += 1
            try:
                release_payload = get(release_url)
            except (HTTPError,URLError,OSError,TimeoutError,ValueError):
                row['status']='mb_release_title_provider_unavailable'
                continue
            matched = parse_mb_release_search(
                release_payload, expected['musicbrainz_artist_mbid'],
                source['title'])
            if matched is None:
                row['status']='invalid_mb_release_title_search'
            elif matched[0]:
                row['status']='mb_release_title_lead_not_edition'
                row['mb_release_candidate_mbids']=matched[0]
                row['mb_group_candidate_mbids']=matched[1]
            else:
                # Even a complete first-page miss is NOT absence evidence.
                row['status']='no_title_index_hit_not_absence'
    return result


def main() -> None:
    index=json.loads(INDEX.read_text(encoding='utf-8'))
    editions=json.loads(EDITIONS.read_text(encoding='utf-8'))
    barcodes=json.loads(BARCODE.read_text(encoding='utf-8'))
    def public_get(url):
        time.sleep(1.1)
        return read_json(url)[1]
    report=triage(index,editions,barcodes,public_get)
    OUTPUT.write_text(json.dumps(report,ensure_ascii=False,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':main()

#!/usr/bin/env python3
"""C-cohort: bounded MusicBrainz title-index check when Discogs has no barcode.

This searches MusicBrainz's release title index, scoped to an exact artist MBID.
Even a hit is just a release-group lead, not proof of the same pressing.
Zero hits are never proof that a release is absent from MusicBrainz.
"""
from __future__ import annotations
import datetime as dt
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from catalog_211_discogs_candidate_details import candidate_queue, valid_id, MAX_CANDIDATES
from catalog_211_discogs_corpus_candidates import MBID
from catalog_211_discogs_public_link_probe import read_json

INDEX = Path('discogs-211-corpus-candidates.json')
EDITIONS = Path('discogs-211-corpus-edition-observations.json')
BARCODE = Path('discogs-211-source-only-barcode-reconciliation.json')
OUTPUT = Path('discogs-211-source-only-title-triage.json')
MAX_REQUESTS = MAX_CANDIDATES
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


def parse_mb_group_search(payload: object, artist_mbid: str) -> list[str] | None:
    if not isinstance(payload,dict) or not MBID.fullmatch(artist_mbid):
        return None
    total,groups = payload.get('release-group-count'),payload.get('release-groups')
    if (type(total) is not int or not 0 <= total <= SEARCH_LIMIT or
        not isinstance(groups,list) or len(groups) != total):
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
             'reviewed':False, 'confirmed_missing_from_musicbrainz':False}
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
            prev.get('status') != 'source_barcode_unavailable'):
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

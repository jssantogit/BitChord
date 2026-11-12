#!/usr/bin/env python3
"""Bounded public inspection of seven independent C audio release candidates.

All seven source IDs must be on a page-2 Discogs Artist index already queried
under the exact MusicBrainz Artist→Discogs Artist relation. Hydrate only the
direct Discogs Release IDs: seven HTTP GETs maximum, no tokens or guessing.
No title hit, partial browse or Discogs row proves absence from MusicBrainz.
"""
from __future__ import annotations
import datetime as dt
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError

from catalog_211_discogs_corpus_candidates import ARTISTS
from catalog_211_discogs_candidate_details import hydrate, valid_id
from catalog_211_discogs_public_link_probe import read_json

PAGE2 = Path("discogs-211-c-second-page.json")
FIRST = Path("discogs-211-corpus-candidates.json")
MANIFEST = Path("docs/plans/catalog-211-discogs-corpus-manifest.json")
OUTPUT = Path("discogs-211-c-audio-batch.json")

AUDIO_LEADS = (
    ("aespa", 31961039), # SYNK : PARALLEL LINE
    ("NMIXX", 31781147), # Funky Glitter Christmas
    ("IVE", 31422908), # CRUSH
    ("IVE", 27107343), # Kitsch
    ("NMIXX", 34417612), # Roller Coaster
    ("LE SSERAFIM", 29892898), # EASY (English ver.)
    ("IVE", 27690417), # I WANT
)
MAX_REQUESTS = len(AUDIO_LEADS)


def inspect_audio_batch(page2: dict, first: dict,
                        reviewed_source_ids: set[int], get,
                        pause=lambda: None) -> dict:
    """Source facts only, with exact-index provenance and zero auto-promotions."""
    for index in (page2, first):
        rows=index.get("artists") if isinstance(index,dict) and index.get("schema_version")==1 else None
        if (not isinstance(rows,list) or len(rows)!=len(ARTISTS) or
            any(not isinstance(row,dict) or row.get("artist")!=name or
                row.get("musicbrainz_artist_mbid")!=mbid
                for row,(name,mbid) in zip(rows,ARTISTS))):
            raise ValueError("invalid_earlier_scoped_artist_index")
    if not isinstance(reviewed_source_ids,set) or any(not valid_id(x) for x in reviewed_source_ids):
        raise ValueError("invalid_existing_reviewed_releases")
    first_by={x["artist"]:x for x in first["artists"]}
    page2_by={x["artist"]:x for x in page2["artists"]}
    result={
        "schema_version":1,
        "observed_utc":dt.datetime.now(dt.timezone.utc).isoformat(),
        "provider_requests":0,
        "reviewed_corpus_cases":0,
        "verified_edition_count":0,
        "confirmed_source_only_cases":0,
        "warning":"Discogs Artist-page two is a research index only. Hydration does not prove MusicBrainz absence, exclusive Artist identity at work level, matching pressing or UI changes.",
        "observations":[],
    }
    for artist,source_id in AUDIO_LEADS:
        prior=first_by[artist]; second=page2_by[artist]
        entry={"artist":artist,"source_kind":"release","source_id":source_id,
               "discogs_release_id":source_id,
               "status":"unconfirmed_artist_index",
               "reviewed":False,"confirmed_missing_from_musicbrainz":False,
               "verified":False}
        result["observations"].append(entry)
        if source_id in reviewed_source_ids:
            entry["status"]="already_reviewed_pressing"
            continue
        if (prior.get("status")!="index_compared_not_adjudicated" or
            second.get("status")!="page2_observed_not_adjudicated" or
            not valid_id(prior.get("discogs_artist_id"))):
            continue
        hints=second.get("hints")
        if not isinstance(hints,list) or len(hints)>12:
            continue
        matches=[x for x in hints if isinstance(x,dict) and
                 x.get("source_kind")=="release" and
                 x.get("source_id")==source_id and
                 x.get("url")==f"https://www.discogs.com/release/{source_id}" and
                 isinstance(x.get("title"),str) and x.get("title").strip()]
        if len(matches)!=1:
            continue
        if result["provider_requests"]>=MAX_REQUESTS:
            entry["status"]="request_budget_exhausted"
            continue
        def checked(url):
            if url!=f"https://api.discogs.com/releases/{source_id}":
                raise ValueError("unexpected_discogs_source")
            result["provider_requests"]+=1
            pause()
            return get(url)
        observed=hydrate({
            "artist":artist,
            "musicbrainz_artist_mbid":prior["musicbrainz_artist_mbid"],
            "discogs_artist_id":prior["discogs_artist_id"],
            "source_kind":"release","source_id":source_id,
            "index_title":matches[0]["title"],
        },checked)
        entry.update(observed)
        entry["reviewed"]=False
        entry["confirmed_missing_from_musicbrainz"]=False
        entry["verified"]=False
    return result


def main() -> None:
    first=json.loads(FIRST.read_text(encoding="utf-8"))
    page2=json.loads(PAGE2.read_text(encoding="utf-8"))
    manifest=json.loads(MANIFEST.read_text(encoding="utf-8"))
    blocked={item["discogs_release_id"] for item in manifest["cases"] if
             item.get("cohort") in ("unlinked","source_only") and
             valid_id(item.get("discogs_release_id"))}
    result=inspect_audio_batch(page2,first,blocked,
                               lambda url:read_json(url)[1],
                               pause=lambda:time.sleep(1.1))
    OUTPUT.write_text(json.dumps(result,ensure_ascii=False,indent=2,sort_keys=True)+"\n",
                      encoding="utf-8")
    print(json.dumps({
        "requests":result["provider_requests"],
        "rows":result["observations"],
        "reviewed":0,"verified":0,"source_only_recoveries":0,
    },ensure_ascii=False))


if __name__=="__main__":
    main()

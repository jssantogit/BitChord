#!/usr/bin/env python3
"""Research-only Discogs Artist page-2 batch: typed IDs, zero absence claims.

Use only the same artist IDs observed by the independently validated page-1
scout earlier in this CI run. No token, no unbounded pagination, no matching
or corrections. This investigates *new* eligible C evidence, not Android.
"""
from __future__ import annotations
import datetime as dt
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError

from catalog_211_discogs_corpus_candidates import ARTISTS, public_index_hints
from catalog_211_discogs_candidate_details import valid_id
from catalog_211_discogs_public_link_probe import read_json

INPUT=Path("discogs-211-corpus-candidates.json")
OUTPUT=Path("discogs-211-c-second-page.json")
MAX_REQUESTS=10


def scout_second_pages(index: dict, get, pause=lambda: None) -> dict:
    rows=index.get("artists") if isinstance(index,dict) and index.get("schema_version")==1 else None
    if (not isinstance(rows,list) or len(rows)!=len(ARTISTS) or
        any(not isinstance(row,dict) or row.get("artist")!=name or
            row.get("musicbrainz_artist_mbid")!=mbid
            for row,(name,mbid) in zip(rows,ARTISTS))):
        raise ValueError("invalid_first_page_identity_index")
    result={"schema_version":1,
            "observed_utc":dt.datetime.now(dt.timezone.utc).isoformat(),
            "http_requests":0,
            "reviewed_corpus_cases":0,
            "confirmed_source_only_cases":0,
            "verified_edition_count":0,
            "warning":"Public Artist page two is a bounded candidate index; none of its rows proves artist-credit exclusivity, missing MusicBrainz releases, pressing identity or catalog recovery.",
            "artists":[]}
    for row,(name,mbid) in zip(rows,ARTISTS):
        finding={"artist":name,"musicbrainz_artist_mbid":mbid,
                 "status":"invalid_source_identity","hints":[]}
        result["artists"].append(finding)
        if (row.get("status")!="index_compared_not_adjudicated" or
            not valid_id(row.get("discogs_artist_id"))):
            continue
        aid=row["discogs_artist_id"]
        url=(f"https://api.discogs.com/artists/{aid}/releases?"
             "page=2&per_page=12&sort=year&sort_order=desc")
        if result["http_requests"]>=MAX_REQUESTS:
            finding["status"]="request_budget_exhausted"
            continue
        try:
            result["http_requests"]+=1
            pause()
            payload=get(url)
            hints=public_index_hints(payload,2)
            if hints is None:
                finding["status"]="invalid_discogs_page2"
                continue
            finding["status"]="page2_observed_not_adjudicated"
            finding["hints"]=hints
        except (OSError,HTTPError,URLError,TimeoutError,ValueError,TypeError,KeyError):
            finding["status"]="provider_unavailable"
    return result


def main() -> None:
    source=json.loads(INPUT.read_text(encoding="utf-8"))
    output=scout_second_pages(source,lambda url:read_json(url)[1],
                              pause=lambda:time.sleep(1.1))
    OUTPUT.write_text(json.dumps(output,indent=2,ensure_ascii=False,sort_keys=True)+"\n",
                      encoding="utf-8")
    print(json.dumps({"requests":output["http_requests"],
                      "artists":output["artists"],
                      "confirmed_source_only_cases":0,
                      "reviewed_corpus_cases":0},ensure_ascii=False))


if __name__=="__main__":
    main()

#!/usr/bin/env python3
"""Bounded public Master-version evidence for already-reviewed B controls.

Observations only. Never verifies edition identity, edits MusicBrainz, or
changes the 30-case corpus. A typed RG -> Master link is mandatory.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit

MANIFEST = Path("docs/plans/catalog-211-discogs-corpus-manifest.json")
OUTPUT = Path("discogs-211-master-version-observations.json")
MAX_CASES, MAX_VERSIONS, MAX_REQUESTS = 5, 8, 55
MBID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z", re.I)
MASTER_PATH = re.compile(r"/master/([1-9][0-9]{0,11})(?:-[A-Za-z0-9_-]+)?/?\Z")

def positive_id(value: object) -> bool:
    return type(value) is int and 1 <= value <= 999_999_999_999

def selected(manifest: dict) -> list[dict]:
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        raise ValueError("invalid_manifest")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or len(cases) != 30:
        raise ValueError("incomplete_corpus")
    output = []
    for idx in range(1, MAX_CASES + 1):
        matches = [x for x in cases if isinstance(x, dict) and x.get("id") == f"B-{idx:02}"]
        if len(matches) != 1:
            raise ValueError("missing_b_control")
        row = matches[0]
        if (row.get("cohort") != "unlinked" or row.get("reviewed") is not True or
            not all(isinstance(row.get(key), str) and MBID.fullmatch(row[key])
                    for key in ("musicbrainz_release_mbid", "musicbrainz_group_mbid")) or
            not positive_id(row.get("discogs_release_id"))):
            raise ValueError("invalid_b_control")
        output.append({key: row[key] for key in ("id", "musicbrainz_release_mbid",
                       "musicbrainz_group_mbid", "discogs_release_id")})
    return output

def typed_master(payload: dict, group_id: str) -> tuple[str, int | None]:
    if not isinstance(payload, dict) or payload.get("id") != group_id:
        return "invalid_musicbrainz_group", None
    relations = payload.get("relations")
    if not isinstance(relations, list) or len(relations) > 250:
        return "invalid_musicbrainz_relations", None
    ids: set[int] = set()
    for item in relations:
        if not isinstance(item, dict):
            return "invalid_musicbrainz_relations", None
        if item.get("type") != "discogs" or item.get("ended") is True:
            continue
        obj = item.get("url")
        value = obj.get("resource") if isinstance(obj, dict) else None
        if not isinstance(value, str) or len(value) > 512:
            return "invalid_musicbrainz_relations", None
        try:
            url = urlsplit(value)
            if (url.scheme not in ("https", "http") or
                url.hostname not in ("discogs.com", "www.discogs.com") or
                url.username or url.password or url.port is not None or
                url.query or url.fragment):
                return "invalid_musicbrainz_relations", None
        except ValueError:
            return "invalid_musicbrainz_relations", None
        found = MASTER_PATH.fullmatch(url.path)
        if found is not None:
            ids.add(int(found.group(1)))
        elif not re.fullmatch(r"/release/[1-9][0-9]{0,11}(?:-[A-Za-z0-9_-]+)?/?", url.path):
            return "invalid_musicbrainz_relations", None
    if len(ids) > 1:
        return "ambiguous_master_relations", None
    if not ids:
        return "no_typed_master_relation", None
    return "typed_master_relation", next(iter(ids))

def versions(payload: dict) -> tuple[str, list[int]]:
    if not isinstance(payload, dict):
        return "invalid_versions", []
    pagination = payload.get("pagination")
    if not isinstance(pagination, dict):
        return "invalid_versions", []
    page = pagination.get("page")
    pages = pagination.get("pages")
    per_page = pagination.get("per_page")
    items = pagination.get("items")
    if (type(page) is not int or page != 1 or type(per_page) is not int or
        per_page != MAX_VERSIONS or type(pages) is not int or
        type(items) is not int or not 1 <= pages <= 100_000 or
        not 1 <= items <= 100_000):
        return "invalid_versions", []
    if pages != 1 or items > MAX_VERSIONS:
        return "master_too_large_not_exhaustive", []
    rows = payload.get("versions")
    if not isinstance(rows, list) or len(rows) != items:
        return "invalid_versions", []
    ids = [row.get("id") if isinstance(row, dict) else None for row in rows]
    if any(not positive_id(x) for x in ids) or len(set(ids)) != len(ids):
        return "invalid_versions", []
    return "complete_bounded_versions", ids

def release_summary(payload: dict, release_id: int, master_id: int) -> dict | None:
    if (not isinstance(payload, dict) or payload.get("id") != release_id or
        payload.get("master_id") != master_id):
        return None
    formats = payload.get("formats")
    tracks = payload.get("tracklist")
    identifiers = payload.get("identifiers")
    if not isinstance(formats, list) or not isinstance(tracks, list) or not isinstance(identifiers, list):
        return None
    if len(formats) > 24 or len(tracks) > 200 or len(identifiers) > 128:
        return None
    media = [x.get("name") for x in formats if isinstance(x, dict) and
             isinstance(x.get("name"), str) and len(x["name"]) <= 80][:12]
    barcodes = [x.get("value") for x in identifiers if isinstance(x, dict) and
                x.get("type") == "Barcode" and isinstance(x.get("value"), str)
                and len(x["value"]) <= 80][:10]
    track_titles = [x.get("title") for x in tracks if isinstance(x, dict) and
                    x.get("type_") == "track" and isinstance(x.get("title"), str)]
    return {"discogs_release_id": release_id, "format_names": media,
            "barcode_values_unverified": barcodes,
            "ordinary_track_count_observed": len(track_titles),
            "country": payload.get("country") if isinstance(payload.get("country"), str)
                       and len(payload["country"]) <= 80 else None,
            "year": payload.get("year") if type(payload.get("year")) is int else None}

def investigate(case: dict, get) -> dict:
    group = case["musicbrainz_group_mbid"]
    outcome = {**case, "reviewed_by_this_probe": False,
               "verified_edition_count": 0, "status": "source_unavailable",
               "master_id": None, "editions": []}
    try:
        mb = get(f"https://musicbrainz.org/ws/2/release-group/{group}?fmt=json&inc=url-rels")
        state, master_id = typed_master(mb, group)
        outcome["status"] = state
        if master_id is None:
            return outcome
        outcome["master_id"] = master_id
        master = get(f"https://api.discogs.com/masters/{master_id}")
        if (not isinstance(master, dict) or master.get("id") != master_id or
            not positive_id(master.get("main_release"))):
            outcome["status"] = "invalid_master"
            return outcome
        main_release = master["main_release"]
        result, ids = versions(get(
            f"https://api.discogs.com/masters/{master_id}/versions?page=1&per_page=8"))
        outcome["status"] = result
        if result != "complete_bounded_versions":
            return outcome
        if main_release not in ids:
            outcome["status"] = "main_release_missing_from_complete_list"
            return outcome
        for release_id in ids:
            record = release_summary(get(f"https://api.discogs.com/releases/{release_id}"),
                                     release_id, master_id)
            if record is None:
                outcome["editions"] = []
                outcome["status"] = "incomplete_release_hydration"
                return outcome
            outcome["editions"].append(record)
        outcome["status"] = "all_versions_observed_not_adjudicated"
        outcome["selected_discogs_release_in_versions"] = case["discogs_release_id"] in ids
    except (HTTPError, URLError, OSError, TimeoutError):
        outcome["status"] = "source_unavailable"
        outcome["editions"] = []
    except (ValueError, TypeError, KeyError, AttributeError):
        outcome["status"] = "invalid_provider_response"
        outcome["editions"] = []
    return outcome

def observe(manifest: dict, get, pause=lambda: None) -> dict:
    count = 0
    def bounded(url):
        nonlocal count
        if count >= MAX_REQUESTS:
            raise ValueError("request_budget_exceeded")
        count += 1
        pause()
        payload = get(url)
        if not isinstance(payload, dict):
            raise ValueError("invalid_provider_json")
        return payload
    findings = [investigate(case, bounded) for case in selected(manifest)]
    return {"schema_version": 1,
            "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "source_http_requests": count,
            "reviewed_corpus_cases": 0,
            "verified_edition_count": 0,
            "warning": "B-01..B-05 Master versions are observations, not Verified pairs or new reviews",
            "findings": findings}

def main():
    from catalog_211_discogs_public_link_probe import read_json
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    result = observe(manifest, lambda url: read_json(url)[1], pause=lambda: time.sleep(1.1))
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                      encoding="utf-8")
    print(json.dumps({"source_http_requests": result["source_http_requests"],
                      "statuses": [x["status"] for x in result["findings"]],
                      "reviewed_corpus_cases": 0, "verified_edition_count": 0}))

if __name__ == "__main__":
    main()

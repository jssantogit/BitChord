#!/usr/bin/env python3
"""Discogs #211: anonymous, exact-ID edition-pair comparison controls.

The output is a fact packet, NEVER a Verified link, a missing MB group,
or a reviewed corpus case. Input is a small checked-in list of public IDs.
"""
from __future__ import annotations
import datetime as dt
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit

from catalog_211_discogs_corpus_candidates import MBID, discogs_artist_relation
from catalog_211_discogs_public_link_probe import compare_edition, read_json

PAIRS = Path("docs/plans/catalog-211-discogs-edition-pairs.json")
OUTPUT = Path("discogs-211-edition-pair-evidence.json")
MAX_PAIRS, MAX_REQUESTS = 6, 18
MAX_ID = 999_999_999_999
PATH = re.compile(r"/release/([1-9][0-9]{0,11})(?:-[A-Za-z0-9_-]+)?/?")

def valid_id(value: object) -> bool:
    return type(value) is int and 1 <= value <= MAX_ID

def case_list(doc: dict) -> list[dict]:
    if not isinstance(doc, dict) or doc.get("schema_version") != 1:
        raise ValueError("invalid_schema")
    cases = doc.get("pairs")
    if not isinstance(cases, list) or not 1 <= len(cases) <= MAX_PAIRS:
        raise ValueError("invalid_pair_count")
    seen_names, seen_releases = set(), set()
    for row in cases:
        if not isinstance(row, dict) or set(row) != {
            "id", "artist", "musicbrainz_artist_mbid",
            "discogs_artist_id", "musicbrainz_release_mbid",
            "discogs_release_id", "reviewed"
        }:
            raise ValueError("invalid_pair_fields")
        if row["reviewed"] is not False:
            raise ValueError("review_claim_not_permitted")
        if (not isinstance(row["id"], str) or
            not re.fullmatch(r"CTRL-[A-Z0-9-]{3,72}", row["id"]) or
            not isinstance(row["artist"], str) or
            not 1 <= len(row["artist"].strip()) <= 100 or
            not isinstance(row["musicbrainz_artist_mbid"], str) or
            not MBID.fullmatch(row["musicbrainz_artist_mbid"]) or
            not isinstance(row["musicbrainz_release_mbid"], str) or
            not MBID.fullmatch(row["musicbrainz_release_mbid"]) or
            not valid_id(row["discogs_artist_id"]) or
            not valid_id(row["discogs_release_id"])):
            raise ValueError("invalid_pair_identity")
        if row["id"] in seen_names or (
            row["musicbrainz_release_mbid"].lower(), row["discogs_release_id"]
        ) in seen_releases:
            raise ValueError("duplicate_pair")
        seen_names.add(row["id"])
        seen_releases.add((row["musicbrainz_release_mbid"].lower(),
                           row["discogs_release_id"]))
    return cases

def release_relations(mb: dict) -> set[int] | None:
    """An absent relation is NoLink; malformed asserted links are unknown."""
    relationships = mb.get("relations")
    if not isinstance(relationships, list) or len(relationships) > 250:
        return None
    ids = set()
    for rel in relationships:
        if not isinstance(rel, dict):
            return None
        if rel.get("type") != "discogs" or rel.get("ended") is True:
            continue
        url = rel.get("url")
        raw = url.get("resource") if isinstance(url, dict) else None
        if not isinstance(raw, str) or len(raw) > 512:
            return None
        parsed = urlsplit(raw)
        try:
            if parsed.port is not None:
                return None
        except ValueError:
            return None
        if (parsed.scheme not in ("http", "https") or
            parsed.hostname not in ("discogs.com", "www.discogs.com") or
            parsed.username or parsed.password or parsed.query or parsed.fragment):
            return None
        matched = PATH.fullmatch(parsed.path)
        if not matched:
            return None
        ids.add(int(matched.group(1)))
    return ids

def inspect(row: dict, get, pause=lambda: None) -> dict:
    result = {
        **row, "status": "source_unavailable",
        "musicbrainz_group_mbid": None, "direct_release_relation": "unknown",
        "comparison": None,
    }
    try:
        artist_id, release_id = row["musicbrainz_artist_mbid"], row["musicbrainz_release_mbid"]
        discogs_id = row["discogs_release_id"]
        pause()
        artist = get(f"https://musicbrainz.org/ws/2/artist/{artist_id}?fmt=json&inc=url-rels")
        if discogs_artist_relation(artist, artist_id) != row["discogs_artist_id"]:
            result["status"] = "artist_relation_unconfirmed"
            return result
        pause()
        mb = get(
            f"https://musicbrainz.org/ws/2/release/{release_id}"
            "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels"
        )
        if not isinstance(mb, dict) or mb.get("id") != release_id:
            result["status"] = "musicbrainz_release_identity_conflict"
            return result
        credits = mb.get("artist-credit")
        mb_ids = [x.get("artist", {}).get("id") for x in credits if isinstance(x, dict)] \
            if isinstance(credits, list) else []
        if mb_ids != [artist_id]:
            result["status"] = "musicbrainz_release_artist_unconfirmed"
            return result
        group = mb.get("release-group")
        if not isinstance(group, dict) or not isinstance(group.get("id"), str) \
                or not MBID.fullmatch(group["id"]):
            result["status"] = "missing_release_group_id"
            return result
        result["musicbrainz_group_mbid"] = group["id"]
        result["musicbrainz_group_title"] = group.get("title")
        result["musicbrainz_group_primary_type"] = group.get("primary-type")
        result["musicbrainz_release_title"] = mb.get("title")
        result["musicbrainz_release_status"] = mb.get("status")
        relation_ids = release_relations(mb)
        if relation_ids is not None:
            result["direct_release_relation"] = (
                "points_to_this_release" if discogs_id in relation_ids
                else "points_to_other_release" if relation_ids else "no_direct_release_link"
            )
        pause()
        discogs = get(f"https://api.discogs.com/releases/{discogs_id}")
        if not isinstance(discogs, dict) or type(discogs.get("id")) is not int \
                or discogs["id"] != discogs_id:
            result["status"] = "discogs_release_identity_conflict"
            return result
        credits = discogs.get("artists")
        discogs_ids = [a.get("id") for a in credits if isinstance(a, dict)] \
            if isinstance(credits, list) else []
        if discogs_ids != [row["discogs_artist_id"]]:
            result["status"] = "discogs_release_artist_unconfirmed"
            return result
        if not isinstance(discogs.get("tracklist"), list) or \
                not isinstance(discogs.get("identifiers", []), list) or \
                not isinstance(discogs.get("formats", []), list):
            result["status"] = "incomplete_discogs_release"
            return result
        result["discogs_title"] = discogs.get("title")
        result["discogs_master_id"] = discogs.get("master_id")
        result["discogs_format_names"] = [
            f.get("name") for f in discogs.get("formats", [])[:20]
            if isinstance(f, dict) and isinstance(f.get("name"), str)
        ]
        result["comparison"] = compare_edition(mb, discogs)
        # This is separate from artist_matches (display names). The
        # exact public Artist link and two typed credit IDs were checked above.
        result["artist_identity_confirmed_by_source_ids"] = True
        result["status"] = "edition_facts_compared_not_reviewed"
    except (HTTPError, URLError, OSError, TimeoutError):
        result["status"] = "source_unavailable"
    except (ValueError, TypeError, KeyError, AttributeError):
        result["status"] = "invalid_provider_response"
    return result

def observe(document: dict, get, pause=lambda: None) -> dict:
    cases = case_list(document)
    calls = 0
    def limited(url: str) -> dict:
        nonlocal calls
        if calls >= MAX_REQUESTS:
            raise ValueError("provider_request_budget_exceeded")
        calls += 1
        data = get(url)
        if not isinstance(data, dict):
            raise ValueError("invalid_response_object")
        return data
    output = {
        "schema_version": 1,
        "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "reviewed_corpus_cases": 0,
        "warning": "Edition comparison controls, not Verified or missing MB releases",
        "cases": [inspect(case, limited, pause) for case in cases],
    }
    output["provider_requests"] = calls
    return output

def main() -> None:
    document = json.loads(PAIRS.read_text(encoding="utf-8"))
    result = observe(document, lambda url: read_json(url)[1],
                     pause=lambda: time.sleep(1.1))
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2)
                      + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()

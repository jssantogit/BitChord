#!/usr/bin/env python3
"""B cohort: discover real RG->Discogs Master leads with no personal token.

Only the first 25 official MusicBrainz ReleaseGroups per chosen artist are
examined. The output is an explicitly INCOMPLETE lead list, never an absence
claim, matching Release, or new reviewed corpus row. Fail closed on invalid
pages, mismatched artists, duplicate RG IDs, Master conflicts and oversized
version lists.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit

from catalog_211_discogs_corpus_candidates import ARTISTS, MBID
from catalog_211_discogs_master_versions_probe import (
    positive_id, typed_master, versions,
)

MANIFEST = Path("docs/plans/catalog-211-discogs-corpus-manifest.json")
OUTPUT = Path("discogs-211-master-lead-scout.json")
ARTIST_LIMIT, MASTER_LIMIT, REQUEST_LIMIT = 10, 5, 25
GROUP_PAGE_LIMIT = 25

def artist_index_url(mbid: str) -> str:
    if not isinstance(mbid, str) or not MBID.fullmatch(mbid):
        raise ValueError("invalid_artist_id")
    return ("https://musicbrainz.org/ws/2/release-group/?artist=" + mbid +
            "&fmt=json&limit=25&offset=0&inc=artist-credits%2Burl-rels")

def browse_page(payload: dict, artist_mbid: str) -> tuple[list[dict], dict]:
    """Only typed, exact-artist relations can become lead *hints*.

    We deliberately DO NOT claim completeness even if the first page happens
    to contain as many records as the reported count: order/index drift and
    unobserved editions remain possible.
    """
    bad = ([], {"status": "invalid_browse", "group_rows_seen": 0,
                "typed_master_rows": 0, "exhaustive_artist_catalog": False})
    if not isinstance(payload, dict) or not MBID.fullmatch(artist_mbid):
        return bad
    offset, count, rows = (payload.get("release-group-offset"),
                            payload.get("release-group-count"),
                            payload.get("release-groups"))
    if (type(offset) is not int or offset != 0 or
        type(count) is not int or not 0 <= count <= 300 or
        not isinstance(rows, list) or len(rows) > GROUP_PAGE_LIMIT or
        len(rows) > count or (count > 0 and not rows) or
        (count == 0 and rows)):
        return bad
    seen, leads = set(), []
    for row in rows:
        if not isinstance(row, dict):
            return bad
        gid, title = row.get("id"), row.get("title")
        if (not isinstance(gid, str) or not MBID.fullmatch(gid) or gid in seen or
            not isinstance(title, str) or not 1 <= len(title.strip()) <= 240):
            return bad
        seen.add(gid)
        # A browse is a navigation index, not proof of a solo artist
        # credit. Reject rows for collaborations and secondary appearances.
        credits = row.get("artist-credit")
        if not isinstance(credits, list) or len(credits) > 16:
            continue
        ids = [item.get("artist", {}).get("id") for item in credits
               if isinstance(item, dict) and isinstance(item.get("artist"), dict)]
        if ids != [artist_mbid]:
            continue
        if row.get("primary-type") not in ("Album", "EP", "Single"):
            continue
        relations = row.get("relations", [])
        if relations is None:
            return bad
        state, master = typed_master(
            {"id": gid, "relations": relations}, gid)
        if state.startswith("invalid"):
            return bad
        if state == "typed_master_relation" and positive_id(master):
            leads.append({"group_mbid": gid, "group_title": title.strip(),
                          "primary_type_hint": row["primary-type"],
                          "artist_mbid": artist_mbid, "master_id": master,
                          "status": "typed_master_lead_not_verified",
                          "version_ids": []})
    return leads, {"status": "partial_index_observed",
                   "group_rows_seen": len(rows),
                   "typed_master_rows": len(leads),
                   "exhaustive_artist_catalog": False,
                   "provider_reported_group_count": count}

def release_index_url(group: str) -> str:
    if not isinstance(group, str) or not MBID.fullmatch(group):
        raise ValueError("invalid_release_group_id")
    return ("https://musicbrainz.org/ws/2/release/?release-group=" + group +
            "&fmt=json&limit=25&offset=0&inc=artist-credits%2Bmedia%2Brelease-groups")

def release_browse(payload: dict, group_mbid: str,
                   artist_mbid: str) -> tuple[list[dict], str]:
    """Exact Official Release IDs are *leads*, never corresponding pressings.

    Incomplete browses cannot prove the only possible MusicBrainz edition.
    Artist collaboration and non-Official releases are excluded from this
    simple solo-artist B review queue; they remain available at the source.
    """
    if (not isinstance(payload, dict) or not MBID.fullmatch(group_mbid) or
        not MBID.fullmatch(artist_mbid)):
        return [], "invalid_canonical_release_index"
    count = payload.get("release-count")
    offset = payload.get("release-offset")
    rows = payload.get("releases")
    if (type(count) is not int or count < 0 or count > 2_000 or
        type(offset) is not int or offset != 0 or
        not isinstance(rows, list) or len(rows) > 25 or len(rows) > count):
        return [], "invalid_canonical_release_index"
    if count > 25:
        return [], "incomplete_canonical_release_index"
    if len(rows) != count:
        return [], "invalid_canonical_release_index"
    observations, seen = [], set()
    for row in rows:
        if not isinstance(row, dict):
            return [], "invalid_canonical_release_index"
        mbid, title = row.get("id"), row.get("title")
        parent = row.get("release-group")
        if (not isinstance(mbid, str) or not MBID.fullmatch(mbid) or
            mbid in seen or not isinstance(title, str) or
            not 1 <= len(title.strip()) <= 240 or
            not isinstance(parent, dict) or parent.get("id") != group_mbid):
            return [], "invalid_canonical_release_index"
        seen.add(mbid)
        credits = row.get("artist-credit")
        if not isinstance(credits, list) or len(credits) > 16:
            return [], "invalid_canonical_release_index"
        ids = [part.get("artist", {}).get("id") for part in credits
               if isinstance(part, dict) and isinstance(part.get("artist"), dict)]
        if ids != [artist_mbid] or row.get("status") != "Official":
            continue
        media = row.get("media", [])
        if not isinstance(media, list) or len(media) > 32:
            return [], "invalid_canonical_release_index"
        formats = [x.get("format") for x in media if isinstance(x, dict)
                   and isinstance(x.get("format"), str) and
                   len(x["format"]) <= 80][:16]
        barcode = row.get("barcode")
        date = row.get("date")
        country = row.get("country")
        observations.append({"release_mbid": mbid, "title": title.strip(),
            "barcode_unverified": barcode if isinstance(barcode, str) and
                len(barcode) <= 80 else None,
            "release_date": date if isinstance(date, str) and
                len(date) <= 32 else None,
            "country": country if isinstance(country, str) and
                len(country) <= 80 else None,
            "media_formats": formats,
            "verified_discogs_identity": False})
    if not observations:
        return [], "no_eligible_official_solo_releases"
    return observations, "complete_official_release_index"

def _allowed(url: str) -> bool:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.fragment:
        return False
    if parsed.port is not None:
        return False
    if parsed.hostname == "musicbrainz.org":
        return ((parsed.path == "/ws/2/release-group/" and
                re.fullmatch(r"artist=[0-9a-f-]{36}&fmt=json&limit=25&offset=0&inc=artist-credits%2Burl-rels",
                             parsed.query, re.I) is not None) or
                (parsed.path == "/ws/2/release/" and
                re.fullmatch(r"release-group=[0-9a-f-]{36}&fmt=json&limit=25&offset=0&inc=artist-credits%2Bmedia%2Brelease-groups",
                             parsed.query, re.I) is not None))
    if parsed.hostname == "api.discogs.com":
        return (re.fullmatch(r"/masters/[1-9][0-9]{0,11}", parsed.path) is not None and
                not parsed.query) or (re.fullmatch(
                    r"/masters/[1-9][0-9]{0,11}/versions", parsed.path) is not None and
                    parsed.query == "page=1&per_page=8")
    return False

def scout(artists: list[tuple[str, str]], known_b_groups: set[str],
          get, pause=lambda: None) -> dict:
    if (not isinstance(artists, (list, tuple)) or
        len(artists) > ARTIST_LIMIT or
        any(not isinstance(name, str) or not isinstance(mbid, str) or
            not MBID.fullmatch(mbid) for name, mbid in artists) or
        any(not isinstance(mbid, str) or not MBID.fullmatch(mbid)
            for mbid in known_b_groups)):
        raise ValueError("invalid_scoping")
    request_count = 0
    def fetch(url):
        nonlocal request_count
        if not _allowed(url) or request_count >= REQUEST_LIMIT:
            raise ValueError("invalid_or_over_budget_source_request")
        request_count += 1
        pause()
        result = get(url)
        if not isinstance(result, dict):
            raise ValueError("invalid_source_payload")
        return result

    artist_findings, candidates = [], []
    # Source errors affect only their own page. The report never claims an
    # exhaustive catalog, so an unavailable artist must not starve other
    # independent read-only lead observations.
    for name, mbid in artists:
        try:
            rows, meta = browse_page(fetch(artist_index_url(mbid)), mbid)
        except (HTTPError, URLError, OSError, TimeoutError):
            artist_findings.append({"artist": name, "artist_mbid": mbid,
                "status": "source_unavailable", "exhaustive_artist_catalog": False})
            continue
        except (ValueError, KeyError, TypeError):
            artist_findings.append({"artist": name, "artist_mbid": mbid,
                "status": "invalid_source_payload", "exhaustive_artist_catalog": False})
            source_failure = True
            break
        artist_findings.append({"artist": name, "artist_mbid": mbid, **meta})
        if meta["status"] != "invalid_browse":
            candidates.extend({**row, "artist": name} for row in rows
                              if row["group_mbid"].lower() not in known_b_groups)
    # Duplicate RG IDs returned for two artists or Masters attached to
    # unrelated groups cannot supply a unique edition claim.
    # A maximum of ten artist pages × 25 rows is already bounded at 250.
    # Truncating source rows here can conceal an explicit competing Master.
    ranked = sorted(candidates, key=lambda item:
                    (item["artist"], item["group_title"].casefold(),
                     item["group_mbid"], item["master_id"]))
    leads = [dict(row) for row in ranked[:MASTER_LIMIT]]
    master_groups: dict[int, set[str]] = {}
    group_masters: dict[str, set[int]] = {}
    for row in candidates:
        master_groups.setdefault(row["master_id"], set()).add(row["group_mbid"])
        group_masters.setdefault(row["group_mbid"], set()).add(row["master_id"])
    for lead in leads:
        mid = lead["master_id"]
        if (len(master_groups[mid]) > 1 or
            len(group_masters[lead["group_mbid"]]) > 1):
            lead["status"] = "master_shared_across_groups"
            continue
        try:
            master = fetch(f"https://api.discogs.com/masters/{mid}")
            if (master.get("id") != mid or
                not positive_id(master.get("main_release"))):
                lead["status"] = "invalid_master"
                continue
            version_state, ids = versions(fetch(
                f"https://api.discogs.com/masters/{mid}/versions?page=1&per_page=8"))
            if version_state != "complete_bounded_versions":
                lead["status"] = version_state
                continue
            if master["main_release"] not in ids:
                lead["status"] = "main_release_not_in_versions"
                continue
            lead["status"] = "complete_small_master_candidate"
            lead["version_ids"] = ids
            # A complete Discogs Master is NOT the same thing as identifying
            # one corresponding MB Release. List exact candidate MB editions
            # separately, with no cross-provider identity promotion.
            try:
                editions, state = release_browse(
                    fetch(release_index_url(lead["group_mbid"])),
                    lead["group_mbid"], lead["artist_mbid"])
                lead["mb_release_browse_status"] = state
                lead["mb_official_release_candidates"] = editions
            except (HTTPError, URLError, OSError, TimeoutError):
                lead["mb_release_browse_status"] = "mb_release_source_unavailable"
                lead["mb_official_release_candidates"] = []
            except (ValueError, KeyError, TypeError):
                lead["mb_release_browse_status"] = "invalid_mb_release_response"
                lead["mb_official_release_candidates"] = []
        except (HTTPError, URLError, OSError, TimeoutError):
            lead["status"] = "source_unavailable"
        except (ValueError, KeyError, TypeError):
            lead["status"] = "invalid_provider_response"
    return {"schema_version": 1, "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "source_http_requests": request_count, "artists": artist_findings,
            "leads": leads,
            "unavailable_artist_pages": sum(
                x["status"] != "partial_index_observed" for x in artist_findings),
            "exhaustive_artist_catalog": False,
            "reviewed_corpus_cases": 0, "verified_edition_count": 0,
            "warning": ("First-page only, anonymous candidate scouting. Neither a "
                        "complete artist catalog nor matching MB Releases nor corpus review.")}

def known_b_groups(manifest: dict) -> set[str]:
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        raise ValueError("invalid_manifest")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or len(cases) != 30:
        raise ValueError("incomplete_manifest")
    existing = [x for x in cases if isinstance(x, dict) and
                x.get("cohort") == "unlinked" and x.get("reviewed") is True]
    if not 5 <= len(existing) <= 10 or len({x.get("id") for x in existing}) != len(existing):
        raise ValueError("unexpected_reviewed_b")
    values = [x.get("musicbrainz_group_mbid") for x in existing]
    if not all(isinstance(x, str) and MBID.fullmatch(x) for x in values):
        raise ValueError("invalid_existing_b_id")
    return {x.lower() for x in values}

def main():
    from catalog_211_discogs_public_link_probe import read_json
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    result = scout(list(ARTISTS), known_b_groups(manifest),
                   lambda url: read_json(url)[1],
                   pause=lambda: time.sleep(1.1))
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True,
                                 indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"requests": result["source_http_requests"],
        "artist_statuses": [x["status"] for x in result["artists"]],
        "master_leads": [{"artist": x["artist"], "title": x["group_title"],
                          "group": x["group_mbid"], "master": x["master_id"],
                          "status": x["status"], "versions": x["version_ids"],
                          "mb_status": x.get("mb_release_browse_status"),
                          "mb_release_ids": [r["release_mbid"] for r in
                              x.get("mb_official_release_candidates", [])]}
                          for x in result["leads"]],
        "reviewed": 0, "verified": 0}, ensure_ascii=False))

if __name__ == "__main__":
    main()

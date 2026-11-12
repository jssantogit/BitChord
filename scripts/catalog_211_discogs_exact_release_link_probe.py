#!/usr/bin/env python3
"""Bounded A-cohort: exact MB Release→Discogs Release links in large Masters.

This is a sampling diagnostic, NEVER exhaustive Master comparison, Verified
edition matching, a type correction, or a new reviewed corpus entry. A typed
MB ReleaseGroup→Discogs Master link does not identify any pressing by itself.
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
from catalog_211_discogs_edition_pair_review import release_relations
from catalog_211_discogs_master_lead_scout import release_browse, release_index_url
from catalog_211_discogs_master_versions_probe import typed_master, positive_id
from catalog_211_discogs_public_link_probe import compare_edition, read_json
from catalog_211_discogs_unlinked_group_probe import label_catalog_evidence

OUTPUT = Path("discogs-211-exact-release-link-probe.json")
MAX_GROUPS, MAX_PER_GROUP, MAX_REQUESTS = 4, 3, 44

# Real RG→Master & Artist→Discogs ID leads observed in CI #43128078271.
# Each relationship is rechecked live; a seed is never accepted as proof.
SEEDS = [
    {"artist": "IVE", "artist_mbid": "b2f2216a-d7a9-4ce0-8b8f-f494d9a8c196",
     "group_mbid": "1c8c9fa5-8b86-4dd1-81cb-b22f8dcd487c",
     "master_id": 2803793, "discogs_artist_id": 7122521},
    {"artist": "LE SSERAFIM", "artist_mbid": "1ee37742-1e3d-4e61-84d2-bc85f4c1459a",
     "group_mbid": "c54392ad-7d95-447b-8b1a-bc5668d2f00d",
     "master_id": 2986856, "discogs_artist_id": 11171795},
    {"artist": "NewJeans", "artist_mbid": "49204a7a-ed85-407a-828f-6fd46f1d8126",
     "group_mbid": "9750354e-78df-4058-8758-b5a7a2eccf66",
     "master_id": 3499101, "discogs_artist_id": 11594273},
    {"artist": "Stray Kids", "artist_mbid": "142b343d-bf5a-428c-a64f-6d1a7566bbe9",
     "group_mbid": "350ea536-dbf2-437d-913f-40040a3932ab",
     "master_id": 3554257, "discogs_artist_id": 6838809},
]


def validate_seeds(seeds: list[dict]) -> None:
    if not isinstance(seeds, (list, tuple)) or not 1 <= len(seeds) <= MAX_GROUPS:
        raise ValueError("invalid_seed_count")
    groups = set()
    for seed in seeds:
        if not isinstance(seed, dict) or set(seed) != {
            "artist", "artist_mbid", "group_mbid", "master_id", "discogs_artist_id"
        } or not isinstance(seed["artist"], str) or not 1 <= len(seed["artist"]) <= 100:
            raise ValueError("invalid_seed")
        if not all(isinstance(seed[key], str) and MBID.fullmatch(seed[key])
                   for key in ("artist_mbid", "group_mbid")):
            raise ValueError("invalid_seed_mbid")
        if not all(positive_id(seed[key])
                   for key in ("master_id", "discogs_artist_id")):
            raise ValueError("invalid_seed_source_id")
        if seed["group_mbid"].lower() in groups:
            raise ValueError("duplicate_group")
        groups.add(seed["group_mbid"].lower())


def select_editions(payload: dict, group_mbid: str,
                    artist_mbid: str) -> tuple[list[dict], str]:
    candidates, state = release_browse(payload, group_mbid, artist_mbid)
    if state != "complete_official_release_index":
        return [], state
    # Schedule physically identifiable media first. Sorting is a REQUEST
    # priority, not an edition match, preferred discography type or claim
    # of complete Discogs coverage.
    physical = {"cd", "vinyl", "cassette", "7\" vinyl", "12\" vinyl"}
    candidates.sort(key=lambda entry: (
        not any(fmt.casefold() in physical for fmt in entry["media_formats"]),
        not bool(entry["barcode_unverified"]),
        entry["release_mbid"],
    ))
    return candidates[:MAX_PER_GROUP], state


def _allowed(url: str) -> bool:
    try:
        p = urlsplit(url)
        if p.scheme != "https" or p.port is not None or p.username or p.password or p.fragment:
            return False
    except ValueError:
        return False
    if p.hostname == "musicbrainz.org":
        if re.fullmatch(r"/ws/2/artist/[0-9a-f-]{36}", p.path, re.I):
            return p.query == "fmt=json&inc=url-rels"
        if re.fullmatch(r"/ws/2/release-group/[0-9a-f-]{36}", p.path, re.I):
            return p.query == "fmt=json&inc=url-rels"
        if p.path == "/ws/2/release/":
            return re.fullmatch(
                r"release-group=[0-9a-f-]{36}&fmt=json&limit=25&offset=0&inc=artist-credits%2Bmedia%2Brelease-groups",
                p.query, re.I) is not None
        if re.fullmatch(r"/ws/2/release/[0-9a-f-]{36}", p.path, re.I):
            return p.query == "fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels"
        return False
    if p.hostname == "api.discogs.com":
        return re.fullmatch(r"/releases/[1-9][0-9]{0,11}", p.path) is not None and not p.query
    return False


def inspect(seed: dict, get) -> dict:
    group_id, artist_id = seed["group_mbid"], seed["artist_mbid"]
    result = {**seed, "status": "source_unavailable", "editions": [],
              "total_mb_official_editions": None,
              "sampled_mb_editions": 0, "verified": False}
    try:
        artist = get(f"https://musicbrainz.org/ws/2/artist/{artist_id}?fmt=json&inc=url-rels")
        if discogs_artist_relation(artist, artist_id) != seed["discogs_artist_id"]:
            result["status"] = "artist_relation_unconfirmed"
            return result
        group = get(f"https://musicbrainz.org/ws/2/release-group/{group_id}?fmt=json&inc=url-rels")
        link_state, master_id = typed_master(group, group_id)
        if link_state != "typed_master_relation" or master_id != seed["master_id"]:
            result["status"] = "typed_master_relation_unconfirmed"
            return result
        result["musicbrainz_group_primary_type"] = (
            group.get("primary-type") if group.get("primary-type") in
            ("Album", "EP", "Single") else None)
        result["musicbrainz_group_title"] = group.get("title")
        # For >25 MB releases we do not treat the first page as complete.
        payload = get(release_index_url(group_id))
        candidates, browse_status = select_editions(payload, group_id, artist_id)
        result["mb_release_browse_status"] = browse_status
        if browse_status != "complete_official_release_index":
            result["status"] = browse_status
            return result
        result["total_mb_official_editions"] = payload.get("release-count")
        result["sampled_mb_editions"] = len(candidates)
        for candidate in candidates:
            mbid = candidate["release_mbid"]
            row = {"musicbrainz_release_mbid": mbid, "status": "source_unavailable",
                   "discogs_release_id": None, "verified": False,
                   "type_correction_applied": False, "reviewed": False}
            result["editions"].append(row)
            try:
                mb = get(f"https://musicbrainz.org/ws/2/release/{mbid}"
                         "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
                if (not isinstance(mb, dict) or mb.get("id") != mbid or
                    mb.get("status") != "Official" or
                    not isinstance(mb.get("release-group"), dict) or
                    mb["release-group"].get("id") != group_id):
                    row["status"] = "musicbrainz_release_identity_conflict"
                    continue
                credit = mb.get("artist-credit")
                if not isinstance(credit, list) or [
                    c.get("artist", {}).get("id") for c in credit
                    if isinstance(c, dict) and isinstance(c.get("artist"), dict)
                ] != [artist_id]:
                    row["status"] = "musicbrainz_release_artist_conflict"
                    continue
                direct = release_relations(mb)
                if direct is None:
                    row["status"] = "invalid_direct_release_relations"
                    continue
                if not direct:
                    row["status"] = "no_direct_release_link"
                    continue
                if len(direct) != 1:
                    row["status"] = "ambiguous_direct_release_links"
                    continue
                did = next(iter(direct))
                row["discogs_release_id"] = did
                discogs = get(f"https://api.discogs.com/releases/{did}")
                if not isinstance(discogs, dict) or discogs.get("id") != did:
                    row["status"] = "discogs_release_identity_conflict"
                    continue
                if discogs.get("master_id") != master_id:
                    row["status"] = "discogs_release_other_master"
                    continue
                discogs_credits = discogs.get("artists")
                if not isinstance(discogs_credits, list) or [
                    c.get("id") for c in discogs_credits if isinstance(c, dict)
                ] != [seed["discogs_artist_id"]]:
                    row["status"] = "discogs_release_artist_conflict"
                    continue
                if (not isinstance(discogs.get("tracklist"), list) or
                    not isinstance(discogs.get("identifiers", []), list) or
                    not isinstance(discogs.get("formats", []), list) or
                    not isinstance(discogs.get("labels", []), list)):
                    row["status"] = "incomplete_discogs_release"
                    continue
                comparison = compare_edition(mb, discogs)
                catalogs = label_catalog_evidence(mb, discogs)
                row["comparison"] = comparison
                row["label_catalog"] = catalogs
                row["status"] = "direct_release_in_typed_master_not_adjudicated"
                row["a_type_reviewer_lead_not_adjudicated"] = (
                    result["musicbrainz_group_primary_type"] in ("Album", "EP", "Single") and
                    comparison["discogs_explicit_type"] in ("Album", "EP", "Single") and
                    result["musicbrainz_group_primary_type"] !=
                    comparison["discogs_explicit_type"] and
                    all(comparison.get(field) is True for field in (
                        "artist_matches", "title_matches", "tracklist_complete",
                        "tracklist_matches", "year_matches",
                        "country_compatible", "barcode_compatible"
                    )) and not catalogs["conflicting_same_label_catalog_numbers"]
                )
            except (HTTPError, URLError, OSError, TimeoutError, ValueError,
                    KeyError, TypeError, AttributeError):
                row["status"] = "source_unavailable"
        result["status"] = "exact_releases_examined_not_adjudicated"
    except (HTTPError, URLError, OSError, TimeoutError):
        result["status"] = "source_unavailable"
    except (ValueError, KeyError, TypeError, AttributeError):
        result["status"] = "invalid_provider_response"
    return result


def probe(seeds: list[dict], get, pause=lambda: None) -> dict:
    validate_seeds(seeds)
    count = 0
    def bounded(url: str) -> dict:
        nonlocal count
        if not _allowed(url) or count >= MAX_REQUESTS:
            raise ValueError("unapproved_or_over_budget_request")
        count += 1
        pause()
        result = get(url)
        if not isinstance(result, dict):
            raise ValueError("invalid_source_payload")
        return result
    groups = [inspect(seed, bounded) for seed in seeds]
    return {
        "schema_version": 1,
        "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source_http_requests": count,
        "reviewed_corpus_cases": 0,
        "verified_edition_count": 0,
        "type_corrections_applied": 0,
        "warning": "Bounded exact Release URL leads only, NOT verified editions, exhaustive Discogs Masters, or reviewed A corrections",
        "groups": groups,
    }


def main() -> None:
    outcome = probe(SEEDS, lambda url: read_json(url)[1],
                    pause=lambda: time.sleep(1.1))
    OUTPUT.write_text(json.dumps(outcome, ensure_ascii=False, sort_keys=True, indent=2)
                      + "\n", encoding="utf-8")
    print(json.dumps({
        "requests": outcome["source_http_requests"],
        "groups": [{"artist": x["artist"], "group": x["group_mbid"],
                    "status": x["status"], "mb_group_type": x.get("musicbrainz_group_primary_type"),
                    "mb_browse": x.get("mb_release_browse_status"),
                    "sampled": x["sampled_mb_editions"],
                    "edition_statuses": [
                        {"mb_release": ed["musicbrainz_release_mbid"],
                         "discogs_release": ed["discogs_release_id"],
                         "state": ed["status"],
                         "a_type_lead": ed.get("a_type_reviewer_lead_not_adjudicated", False)}
                        for ed in x["editions"]]}
                   for x in outcome["groups"]],
        "verified": 0, "reviewed": 0,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()

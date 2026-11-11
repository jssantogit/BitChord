#!/usr/bin/env python3
"""Read-only exact-ID matrix for bounded public typed Master leads.

The source lists are checked *in full* before comparing any pairs. Matched
tracks/barcodes are observations, not a unique pressing, a Discogs Verified
claim, or a new B-06/B-07 corpus review. No tokens or local catalog writes.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit

from catalog_211_discogs_master_lead_scout import release_browse, release_index_url
from catalog_211_discogs_master_versions_probe import typed_master, versions, positive_id
from catalog_211_discogs_public_link_probe import compare_edition, read_json
from catalog_211_discogs_edition_pair_review import release_relations
from catalog_211_discogs_unlinked_group_probe import label_catalog_evidence

OUTPUT = Path("discogs-211-typed-master-pair-matrix.json")
MAX_SEEDS, MAX_MB_RELEASES, MAX_REQUESTS = 4, 12, 60
MBID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z", re.I)

# Provenance: #43107919302 (ENHYPEN/LE SSERAFIM) and #43117680744
# (IVE): real MusicBrainz RG -> Discogs Master links and bounded <=8
# version lists. Recheck on EVERY run; never treat IDs as permanent mappings.
SEEDS = [
    {"artist": "ENHYPEN",
     "artist_mbid": "95663592-1447-4579-90f7-c94733476667",
     "group_mbid": "849d8582-3422-4d61-bfbe-9b65f4bfdc06",
     "master_id": 1887999, "discogs_artist_id": 8303009},
    {"artist": "LE SSERAFIM",
     "artist_mbid": "1ee37742-1e3d-4e61-84d2-bc85f4c1459a",
     "group_mbid": "fb426d76-525d-4b25-90d1-fa4d1b3140c9",
     "master_id": 2653076, "discogs_artist_id": 11171795},
    {"artist": "IVE",
     "artist_mbid": "b2f2216a-d7a9-4ce0-8b8f-f494d9a8c196",
     "group_mbid": "18271616-7122-4bf6-8fc8-11467cc923c1",
     "master_id": 2531575, "discogs_artist_id": 7122521},
    {"artist": "IVE",
     "artist_mbid": "b2f2216a-d7a9-4ce0-8b8f-f494d9a8c196",
     "group_mbid": "07fb58fa-2007-4e40-aa57-88e69a9e19be",
     "master_id": 2911144, "discogs_artist_id": 7122521},
]

def validate_seed(seed: dict) -> None:
    if (not isinstance(seed, dict) or
        not all(isinstance(seed.get(k), str) and MBID.fullmatch(seed[k])
                for k in ("artist_mbid", "group_mbid")) or
        not positive_id(seed.get("master_id")) or
        not positive_id(seed.get("discogs_artist_id")) or
        not isinstance(seed.get("artist"), str) or
        not 1 <= len(seed["artist"]) <= 100):
        raise ValueError("invalid_pair_matrix_seed")

def _allowed(url: str) -> bool:
    p = urlsplit(url)
    if p.scheme != "https" or p.port is not None or p.username or p.password or p.fragment:
        return False
    if p.hostname == "api.discogs.com":
        return (re.fullmatch(r"/(?:masters|releases)/[1-9][0-9]{0,11}", p.path)
                is not None and not p.query) or (
                re.fullmatch(r"/masters/[1-9][0-9]{0,11}/versions", p.path)
                is not None and p.query == "page=1&per_page=8")
    if p.hostname == "musicbrainz.org":
        if re.fullmatch(r"/ws/2/release-group/[0-9a-f-]{36}", p.path, re.I):
            return p.query == "fmt=json&inc=url-rels"
        if p.path == "/ws/2/release/":
            return re.fullmatch(
                r"release-group=[0-9a-f-]{36}&fmt=json&limit=25&offset=0&inc=artist-credits%2Bmedia%2Brelease-groups",
                p.query, re.I) is not None
        if re.fullmatch(r"/ws/2/release/[0-9a-f-]{36}", p.path, re.I):
            return (p.query ==
                    "fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
    return False

def _mb_once_retry_503(url: str, get) -> dict:
    # Only a transient 503 on this exact MusicBrainz Release lookup can
    # receive one extra attempt. The shared request limiter counts and
    # spaces both calls; 404, 429 and invalid JSON never get masked.
    try:
        return get(url)
    except HTTPError as error:
        if error.code != 503:
            raise
        return get(url)

def inspect(seed: dict, get) -> dict:
    validate_seed(seed)
    rgid, mid, aid = seed["group_mbid"], seed["master_id"], seed["artist_mbid"]
    did_artist = seed["discogs_artist_id"]
    result = {**seed, "status": "source_unavailable",
              "reviewed_by_this_probe": False, "verified_edition_count": 0,
              "mb_release_ids": [], "discogs_release_ids": [], "pairs": []}
    try:
        group = _mb_once_retry_503(
            f"https://musicbrainz.org/ws/2/release-group/{rgid}?fmt=json&inc=url-rels", get)
        state, observed_master = typed_master(group, rgid)
        if state != "typed_master_relation" or observed_master != mid:
            result["status"] = "typed_master_relation_not_confirmed"
            return result
        master = get(f"https://api.discogs.com/masters/{mid}")
        if (not isinstance(master, dict) or master.get("id") != mid or
            not positive_id(master.get("main_release"))):
            result["status"] = "source_identity_conflict"
            return result
        state, ids = versions(get(
            f"https://api.discogs.com/masters/{mid}/versions?page=1&per_page=8"))
        if state != "complete_bounded_versions":
            result["status"] = state
            return result
        if master["main_release"] not in ids:
            result["status"] = "source_identity_conflict"
            return result
        result["discogs_release_ids"] = ids
        rows, state = release_browse(
            _mb_once_retry_503(release_index_url(rgid), get), rgid, aid)
        if state != "complete_official_release_index":
            result["status"] = state
            return result
        if len(rows) > MAX_MB_RELEASES:
            result["status"] = "too_many_mb_releases_to_compare_exhaustively"
            return result
        mbids = [row["release_mbid"] for row in rows]
        result["mb_release_ids"] = mbids
        mb_details, discogs_details = [], []
        # Missing one source cannot turn the others into a unique pair.
        for mbid in mbids:
            try:
                url = (f"https://musicbrainz.org/ws/2/release/{mbid}"
                    "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
                record = _mb_once_retry_503(url, get)
            except (HTTPError, URLError, OSError, TimeoutError):
                result["status"] = "incomplete_source_hydration"
                result["unavailable_source_id"] = "musicbrainz_release:" + mbid
                return result
            if (not isinstance(record, dict) or record.get("id") != mbid or
                not isinstance(record.get("release-group"), dict) or
                record["release-group"].get("id") != rgid or
                record.get("status") != "Official"):
                result["status"] = "source_identity_conflict"
                return result
            credits = record.get("artist-credit")
            ids_here = ([x.get("artist", {}).get("id") for x in credits
                if isinstance(x, dict) and isinstance(x.get("artist"), dict)]
                if isinstance(credits, list) else [])
            if ids_here != [aid]:
                result["status"] = "source_identity_conflict"
                return result
            mb_details.append(record)
        for did in ids:
            try:
                record = get(f"https://api.discogs.com/releases/{did}")
            except (HTTPError, URLError, OSError, TimeoutError):
                result["status"] = "incomplete_source_hydration"
                result["unavailable_source_id"] = "discogs_release:" + str(did)
                return result
            if (not isinstance(record, dict) or record.get("id") != did or
                record.get("master_id") != mid or
                not isinstance(record.get("artists"), list) or
                [x.get("id") for x in record["artists"] if isinstance(x, dict)] !=
                    [did_artist] or
                not isinstance(record.get("tracklist"), list) or
                not isinstance(record.get("identifiers", []), list) or
                not isinstance(record.get("formats", []), list)):
                result["status"] = "source_identity_conflict"
                return result
            discogs_details.append(record)
        # Exact MB Release->Discogs Release relations provide *ownership
        # warnings* for other same-RG editions, not a new link. Never
        # overwrite a typed direct relation with a no-link UPC coincidence.
        owners: dict[int, set[str]] = {}
        for mb in mb_details:
            links = release_relations(mb)
            if links is not None:
                for discogs_id in links:
                    if discogs_id in ids:
                        owners.setdefault(discogs_id, set()).add(mb["id"])
        # No deduplication: two versions can have identical UPC+tracklists.
        # Retain the complete cross-product so a caller cannot cherry-pick
        # the sole attractive match out of a competing physical version.
        for mb in mb_details:
            related = release_relations(mb)
            for discogs in discogs_details:
                comparison = compare_edition(mb, discogs)
                result["pairs"].append({
                    "musicbrainz_release_mbid": mb["id"],
                    "discogs_release_id": discogs["id"],
                    "direct_release_relation": (
                        "unknown" if related is None else
                        "this_discogs_release" if discogs["id"] in related else
                        "another_discogs_release" if related else "no_direct_release_link"),
                    "mb_formats": [x.get("format") for x in mb.get("media", [])
                                   if isinstance(x, dict) and isinstance(x.get("format"), str)][:12],
                    "discogs_formats": [x.get("name") for x in discogs["formats"]
                                       if isinstance(x, dict) and isinstance(x.get("name"), str)][:12],
                    "comparison": comparison,
                    "label_catalog": label_catalog_evidence(mb, discogs),
                    "competing_direct_release_owner_mbids": sorted(
                        owners.get(discogs["id"], set()) - {mb["id"]}),
                    "verified_discogs_edition": False,
                })
        result["status"] = "all_pairs_compared_not_adjudicated"
    except (HTTPError, URLError, OSError, TimeoutError):
        result["status"] = "source_unavailable"
        result["pairs"] = []
    except (ValueError, TypeError, KeyError, AttributeError):
        result["status"] = "invalid_provider_response"
        result["pairs"] = []
    return result

def observe(seeds: list[dict], get, pause=lambda: None) -> dict:
    if not isinstance(seeds, list) or not 1 <= len(seeds) <= MAX_SEEDS:
        raise ValueError("invalid_seed_count")
    for item in seeds:
        validate_seed(item)
    if len({x["group_mbid"] for x in seeds}) != len(seeds) or len(
        {x["master_id"] for x in seeds}) != len(seeds):
        raise ValueError("duplicate_master_or_group")
    count = 0
    def limited(url):
        nonlocal count
        if not _allowed(url) or count >= MAX_REQUESTS:
            raise ValueError("unapproved_url_or_request_limit")
        count += 1
        pause()
        value = get(url)
        if not isinstance(value, dict):
            raise ValueError("malformed_provider_payload")
        return value
    findings = [inspect(seed, limited) for seed in seeds]
    return {"schema_version": 1,
            "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "source_http_requests": count, "reviewed_corpus_cases": 0,
            "verified_edition_count": 0,
            "warning": "No corpus B review, no unique pressing, and no Discogs Verified",
            "findings": findings}

def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=("all", "ENHYPEN", "LE SSERAFIM", "IVE"),
                        default="all")
    args = parser.parse_args()
    selected = SEEDS if args.target == "all" else [
        x for x in SEEDS if x["artist"] == args.target]
    data = observe(selected, lambda url: read_json(url)[1],
                   pause=lambda: time.sleep(1.1))
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, sort_keys=True,
                                 indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"requests": data["source_http_requests"],
        "summary": [{"artist": x["artist"], "status": x["status"],
                     "master": x["master_id"], "mb_release_count": len(x["mb_release_ids"]),
                     "discogs_release_count": len(x["discogs_release_ids"]),
                     "raw_pairs": len(x["pairs"]),
                     "unavailable_source_id": x.get("unavailable_source_id"),
                     "label_catalog_conflicting_pairs": [
                         {"mb": p["musicbrainz_release_mbid"],
                          "discogs": p["discogs_release_id"],
                          "conflicts": p["label_catalog"]["conflicting_same_label_catalog_numbers"]}
                         for p in x["pairs"]
                         if p["label_catalog"]["conflicting_same_label_catalog_numbers"]
                     ][:15],
                     "matching_full_tracks_and_barcode": sum(
                         p["comparison"]["tracklist_matches"] and
                         p["comparison"]["barcode_identical_on_both"]
                         for p in x["pairs"]),
                     "matching_pair_source_ids": [
                         {"mb": p["musicbrainz_release_mbid"],
                          "discogs": p["discogs_release_id"],
                          "direct_relation": p["direct_release_relation"]}
                         for p in x["pairs"]
                         if p["comparison"]["tracklist_matches"] and
                         p["comparison"]["barcode_identical_on_both"]][:12]}
                    for x in data["findings"]],
        "verified": 0, "reviewed": 0}, ensure_ascii=False))

if __name__ == "__main__":
    main()

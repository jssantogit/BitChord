#!/usr/bin/env python3
"""C-cohort: bounded MusicBrainz barcode search on public Discogs edition leads.

Positive artist+barcode matches show an existing MusicBrainz release record,
NOT an exact pressing match. Negative search results NEVER prove MB absence.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode

from catalog_211_discogs_candidate_details import candidate_queue, MAX_CANDIDATES, valid_id
from catalog_211_discogs_corpus_candidates import MBID
from catalog_211_discogs_public_link_probe import read_json

INPUT_INDEX = Path("discogs-211-corpus-candidates.json")
INPUT_EDITIONS = Path("discogs-211-corpus-edition-observations.json")
OUTPUT = Path("discogs-211-source-only-barcode-reconciliation.json")
MAX_MB_REQUESTS = MAX_CANDIDATES * 2
MB_SEARCH_LIMIT = 10


def eligible_barcode(value: object) -> str | None:
    """Only literal digits and separators, not OCR corrections or fuzzy matches."""
    if not isinstance(value, str) or len(value) > 40 or not re.fullmatch(r"[0-9 -]+", value):
        return None
    code = value.replace(" ", "").replace("-", "")
    return code if len(code) in (8, 12, 13, 14) and any(c != "0" for c in code) else None


def read_barcode_search(payload: object, barcode: str, artist_mbid: str) -> dict | None:
    """Reject partial/inconsistent search results; identify existing MB record only."""
    if not isinstance(payload, dict) or not MBID.fullmatch(artist_mbid):
        return None
    count, rows = payload.get("release-count"), payload.get("releases")
    if (type(count) is not int or not 0 <= count <= MB_SEARCH_LIMIT or
        not isinstance(rows, list) or len(rows) != count):
        return None
    identities = set()
    matches = set()
    other_artist = False
    for row in rows:
        if not isinstance(row, dict):
            return None
        rid, mb_barcode, credits = row.get("id"), row.get("barcode"), row.get("artist-credit")
        if (not isinstance(rid, str) or not MBID.fullmatch(rid) or
            rid.lower() in identities or eligible_barcode(mb_barcode) != barcode or
            not isinstance(credits, list) or not credits or len(credits) > 40):
            return None
        identities.add(rid.lower())
        artist_ids = []
        for credit in credits:
            # MusicBrainz interleaves string join phrases with structured credits.
            if isinstance(credit, str):
                continue
            if not isinstance(credit, dict) or not isinstance(credit.get("artist"), dict):
                return None
            mid = credit["artist"].get("id")
            if not isinstance(mid, str) or not MBID.fullmatch(mid):
                return None
            artist_ids.append(mid.lower())
        if not artist_ids:
            return None
        if artist_ids == [artist_mbid.lower()]:
            matches.add(rid.lower())
        else:
            other_artist = True
    return {"matching_release_mbids": sorted(matches), "other_artist_hit": other_artist}


def reconcile(index: dict, editions: dict, get) -> dict:
    """Compare at most two public barcodes per strictly joined C candidate."""
    result = {
        "schema_version": 1,
        "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "warning": "Barcode corroboration is not an exact edition match; zero hits are NOT absence",
        "reviewed_corpus_cases": 0, "confirmed_source_only_cases": 0,
        "provider_requests": 0, "observations": [],
    }
    if (not isinstance(index, dict) or not isinstance(editions, dict) or
        index.get("schema_version") != 1 or editions.get("schema_version") != 1 or
        not isinstance(editions.get("observations"), list) or
        len(editions["observations"]) > MAX_CANDIDATES):
        return result
    queue = candidate_queue(index)
    if not queue:
        return result
    allowed = {(row["source_kind"], row["source_id"]): row for row in queue}
    artists = {row["artist"]: row for row in index.get("artists", []) if isinstance(row, dict)}
    seen = set()
    for source in editions["observations"]:
        if not isinstance(source, dict):
            continue
        kind, sid = source.get("source_kind"), source.get("source_id")
        key = (kind, sid)
        row = {"source_kind": kind, "source_id": sid,
               "discogs_release_id": source.get("discogs_release_id"),
               "status": "source_identity_mismatch", "matched_mb_release_mbids": [],
               "reviewed": False, "confirmed_missing_from_musicbrainz": False,
               "barcode_searches": []}
        result["observations"].append(row)
        expected = allowed.get(key)
        if (key in seen or expected is None or
            any(source.get(x) != expected[x] for x in (
                "artist", "musicbrainz_artist_mbid", "discogs_artist_id")) or
            not valid_id(source.get("discogs_release_id")) or
            not isinstance(artists.get(expected["artist"]), dict) or
            artists[expected["artist"]].get("status") != "index_compared_not_adjudicated" or
            type(artists[expected["artist"]].get("distinct_mb_titles_count")) is not int):
            continue
        seen.add(key)
        if (source.get("status") != "edition_metadata_observed_not_adjudicated" or
            source.get("artist_id_matches_relation") is not True or
            type(source.get("track_rows")) is not int or source["track_rows"] <= 0 or
            not isinstance(source.get("labels"), list) or not source["labels"]):
            row["status"] = "excluded_source_metadata"
            continue
        codes = source.get("barcodes")
        if not isinstance(codes, list) or len(codes) > 12:
            row["status"] = "source_barcode_unavailable"
            continue
        barcodes = sorted({b for v in codes if (b := eligible_barcode(v)) is not None})
        if not barcodes:
            row["status"] = "source_barcode_unavailable"
            continue
        if len(barcodes) > 2:
            row["status"] = "multiple_source_barcodes_unresolved"
            continue
        found, collision, failed, zero_hit = set(), False, False, True
        for barcode in barcodes:
            if result["provider_requests"] >= MAX_MB_REQUESTS:
                failed = True
                break
            url = "https://musicbrainz.org/ws/2/release/?" + urlencode({
                "query": "barcode:" + barcode, "fmt": "json", "limit": MB_SEARCH_LIMIT,
            })
            result["provider_requests"] += 1
            try:
                payload = get(url)
            except (HTTPError, URLError, OSError, TimeoutError, ValueError):
                row["barcode_searches"].append({"barcode": barcode, "status": "unavailable"})
                failed = True
                continue
            parsed = read_barcode_search(payload, barcode, expected["musicbrainz_artist_mbid"])
            if parsed is None:
                row["barcode_searches"].append({"barcode": barcode, "status": "invalid_response"})
                failed = True
                continue
            matches = parsed["matching_release_mbids"]
            collision |= parsed["other_artist_hit"]
            zero_hit &= not matches and not parsed["other_artist_hit"]
            found.update(matches)
            row["barcode_searches"].append({
                "barcode": barcode, "status": "observed", "mb_artist_release_matches": matches,
                "other_artist_hit": parsed["other_artist_hit"],
            })
        row["matched_mb_release_mbids"] = sorted(found)
        if found:
            row["status"] = "existing_mb_artist_barcode"
        elif failed:
            row["status"] = ("mb_provider_unavailable" if any(x["status"] == "unavailable"
                               for x in row["barcode_searches"]) else "invalid_mb_barcode_search")
        elif collision:
            row["status"] = "barcode_hit_other_artist_only"
        elif zero_hit:
            row["status"] = "no_exact_barcode_result_not_absence"
        else:
            row["status"] = "barcode_review_inconclusive"
    return result


def main() -> None:
    index = json.loads(INPUT_INDEX.read_text(encoding="utf-8"))
    editions = json.loads(INPUT_EDITIONS.read_text(encoding="utf-8"))
    def public_get(url):
        time.sleep(1.1)
        return read_json(url)[1]
    result = reconcile(index, editions, public_get)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                      encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

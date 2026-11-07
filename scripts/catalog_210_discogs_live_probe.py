#!/usr/bin/env python3
"""Bounded, anonymous, read-only MusicBrainz -> Discogs edition contract probe.

The two IDs below are *real*, explicitly linked editions, not fabricated test
fixture IDs. This is a network probe from the GitHub runner, not Android smoke.
Never record source payloads, audio, artwork, tokens or user-specific details.
"""
from __future__ import annotations

import datetime as dt
import json
import pathlib
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

MBID = "dc919562-19c7-42c8-961c-7ed5520f00bb"
DISCOGS_ID = 249504
RELATION_TYPE = "4a78823c-1c53-4176-a5f3-58026c76f2bc"
UA = "BitChordPersonal/1.0 (+https://github.com/jssantogit/BitChord)"
MAX_BODY_BYTES = 131_072
DISCOGS_URL = f"https://api.discogs.com/releases/{DISCOGS_ID}"
MB_URL = "https://musicbrainz.org/ws/2/release/" + MBID + "?" + urllib.parse.urlencode({
    "fmt": "json", "inc": "recordings+artist-credits+release-groups+url-rels"
})

def fetch(url: str, accept: str) -> tuple[int, dict]:
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
    with urllib.request.urlopen(request, timeout=15) as response:
        status = response.status
        raw = response.read(MAX_BODY_BYTES + 1)
    if len(raw) > MAX_BODY_BYTES:
        raise ValueError("oversized")
    payload = json.loads(raw.decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("non_object")
    return status, payload

def normalized(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())

def mb_track_titles(payload: dict) -> tuple[list[str], bool]:
    media = payload.get("media")
    if not isinstance(media, list) or not media:
        return [], False
    all_titles = []
    top_level_media_count = payload.get("media-count")
    # Real MusicBrainz lookups omit this optional summary; the per-medium
    # track-count is authoritative for a complete release tracklist.
    complete = top_level_media_count is None or top_level_media_count == len(media)
    for medium in media:
        if not isinstance(medium, dict):
            return [], False
        tracks = medium.get("tracks")
        if not isinstance(tracks, list):
            return [], False
        complete = complete and medium.get("track-count") == len(tracks)
        for track in tracks:
            if not isinstance(track, dict) or not isinstance(track.get("id"), str):
                complete = False
                continue
            title = track.get("title") or (track.get("recording") or {}).get("title")
            if not isinstance(title, str) or not title:
                complete = False
                continue
            all_titles.append(title)
    return all_titles, bool(complete and all_titles)

def relation_id(payload: dict) -> bool:
    links = payload.get("relations")
    if not isinstance(links, list):
        return False
    for link in links:
        if not isinstance(link, dict):
            continue
        if link.get("type") != "discogs" or link.get("type-id") != RELATION_TYPE:
            continue
        resource = (link.get("url") or {}).get("resource")
        if not isinstance(resource, str):
            continue
        uri = urllib.parse.urlsplit(resource)
        if uri.scheme not in ("http", "https") or uri.hostname not in ("www.discogs.com", "discogs.com"):
            continue
        if uri.username or uri.password or uri.port or uri.query or uri.fragment:
            continue
        match = re.match(r"^/release/([1-9][0-9]{0,11})(?:-[A-Za-z0-9_-]+)?/?$", uri.path)
        if match and int(match.group(1)) == DISCOGS_ID:
            return True
    return False

def main(argv: list[str]) -> int:
    started = time.monotonic()
    result = {
        "observed_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "target": "linked_public_musicbrainz_discogs_release",
        "discogs_status": None,
        "musicbrainz_status": None,
        "source_ids_correct": False,
        "link_confirmed": False,
        "musicbrainz_tracklist_complete": False,
        "matching_artist": False,
        "matching_title": False,
        "matching_tracks": False,
        "matching_country": False,
        "matching_year": False,
        "barcode_compatible": False,
        "result": "unavailable",
    }
    try:
        status, discogs = fetch(DISCOGS_URL, "application/vnd.discogs.v2.discogs+json")
        result["discogs_status"] = status
        mb_status, mb = fetch(MB_URL, "application/json")
        result["musicbrainz_status"] = mb_status
        result["source_ids_correct"] = (discogs.get("id") == DISCOGS_ID and mb.get("id") == MBID)
        result["link_confirmed"] = relation_id(mb)
        titles, complete = mb_track_titles(mb)
        media = mb.get("media")
        result["mb_media_count_field"] = mb.get("media-count")
        result["mb_media_items"] = len(media) if isinstance(media, list) else None
        result["mb_medium_track_count_fields"] = [
            m.get("track-count") for m in media if isinstance(m, dict)
        ] if isinstance(media, list) else []
        result["mb_medium_track_items"] = [
            len(m["tracks"]) if isinstance(m.get("tracks"), list) else None
            for m in media if isinstance(m, dict)
        ] if isinstance(media, list) else []
        result["mb_parsed_track_count"] = len(titles)
        result["musicbrainz_tracklist_complete"] = complete
        result["matching_title"] = (isinstance(mb.get("title"), str) and
            isinstance(discogs.get("title"), str) and
            normalized(mb["title"]) == normalized(discogs["title"]))
        mb_artists = [c.get("name") or (c.get("artist") or {}).get("name")
            for c in mb.get("artist-credit", []) if isinstance(c, dict)]
        discogs_artists = [a.get("name") for a in discogs.get("artists", [])
            if isinstance(a, dict)]
        result["matching_artist"] = bool(set(map(normalized, filter(None, mb_artists))) &
            set(map(normalized, filter(None, discogs_artists))))
        discogs_titles = [t.get("title") for t in discogs.get("tracklist", [])
            if isinstance(t, dict) and t.get("type_", "track") == "track"]
        result["matching_tracks"] = bool(titles and titles == discogs_titles or
            titles and list(map(normalized, titles)) == list(map(normalized, discogs_titles)))
        mb_country = (mb.get("country") or "").upper()
        discogs_country = (discogs.get("country") or "").upper()
        result["matching_country"] = (mb_country == discogs_country or
            (mb_country, discogs_country) in {("GB", "UK"), ("KR", "SOUTH KOREA")})
        result["matching_year"] = bool(mb.get("date") and discogs.get("year") and
            mb["date"][:4] == str(discogs["year"]))
        mb_barcode = re.sub(r"[ -]", "", mb.get("barcode") or "")
        discogs_barcodes = {
            re.sub(r"[ -]", "", item.get("value", ""))
            for item in discogs.get("identifiers", [])
            if isinstance(item, dict) and item.get("type") == "Barcode"
                and isinstance(item.get("value"), str)
        }
        # The app treats absent barcode evidence as unknown, but rejects a
        # contradiction when *both* databases contain validated barcodes.
        result["barcode_compatible"] = (
            not mb_barcode or not discogs_barcodes or mb_barcode in discogs_barcodes
        )
        mandatory = [
            "source_ids_correct", "link_confirmed", "musicbrainz_tracklist_complete",
            "matching_artist", "matching_title", "matching_tracks",
            "matching_country", "matching_year", "barcode_compatible",
        ]
        result["result"] = "verified" if all(result[key] for key in mandatory) else "mismatch"
    except urllib.error.HTTPError as exc:
        result["result"] = "http_error"
        # HTTP status identifies which API failed without exposing the URL/body.
        if result["discogs_status"] is None:
            result["discogs_status"] = exc.code
        else:
            result["musicbrainz_status"] = exc.code
    except (urllib.error.URLError, TimeoutError, OSError):
        result["result"] = "network_unavailable"
    except (ValueError, TypeError, UnicodeError, AttributeError):
        result["result"] = "invalid_payload"
    result["duration_ms"] = round((time.monotonic() - started) * 1000)
    output = pathlib.Path(argv[1] if len(argv) > 1 else "discogs-api-evidence.json")
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0 if result["result"] == "verified" else 1

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

#!/usr/bin/env python3
"""Bounded, anonymous observation of a real public MB -> Discogs Release link.

This is NOT APK, Android smoke, a credentials check or a classification claim.
An unavailable/mismatched response is recorded, never promoted to VERIFIED.
"""
from __future__ import annotations
import datetime as dt
import json
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request

MBID = "5f600fc6-5c84-4c84-838e-eb87bd7363c7"
DISCOGS_RELEASE_ID = 36297418
USER_AGENT = "BitChordPersonalEditorialProbe/1.0 (+https://github.com/jssantogit/BitChord)"
MB_URL = "https://musicbrainz.org/ws/2/release/" + MBID + "?" + urllib.parse.urlencode({
    "fmt": "json", "inc": "recordings+artist-credits+release-groups+url-rels",
})
DISCOGS_URL = f"https://api.discogs.com/releases/{DISCOGS_RELEASE_ID}"
MAX_BYTES = 131_072

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def read_json(url: str) -> tuple[int, dict]:
    request = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT, "Accept": "application/json",
    })
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(request, timeout=12) as response:
        raw = response.read(MAX_BYTES + 1)
        status = response.status
    if len(raw) > MAX_BYTES:
        raise ValueError("body_too_large")
    obj = json.loads(raw.decode("utf-8"))
    if not isinstance(obj, dict):
        raise ValueError("not_object")
    return status, obj

def exact_discogs_relationship(mb: dict) -> bool:
    for relation in mb.get("relations", []):
        if not isinstance(relation, dict) or relation.get("type") != "discogs":
            continue
        address = (relation.get("url") or {}).get("resource", "")
        if not isinstance(address, str):
            continue
        parsed = urllib.parse.urlsplit(address)
        if (parsed.scheme not in ("http", "https") or
            parsed.hostname not in ("discogs.com", "www.discogs.com") or
            parsed.username or parsed.password or parsed.port or
            parsed.query or parsed.fragment):
            continue
        found = re.fullmatch(r"/release/([1-9][0-9]{0,11})(?:-[A-Za-z0-9_-]+)?/?", parsed.path)
        if found and int(found.group(1)) == DISCOGS_RELEASE_ID:
            return True
    return False

def normalized(value: str) -> str:
    import unicodedata
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())

def explicit_discogs_type(formats: list) -> str:
    mapping = {
        "album": "Album", "mini-album": "EP", "ep": "EP",
        "single": "Single", "maxi-single": "Single",
    }
    kinds = {
        mapping[normalized(desc)]
        for entry in formats if isinstance(entry, dict)
        for desc in (entry.get("descriptions") or [])
        if isinstance(desc, str) and normalized(desc) in mapping
    }
    return "ambiguous" if len(kinds) > 1 else next(iter(kinds), "unknown")

def country_code(value: str | None) -> str | None:
    lookup = {
        "kr": "kr", "south korea": "kr",
        "us": "us", "united states": "us",
        "gb": "gb", "uk": "gb", "united kingdom": "gb",
        "jp": "jp", "japan": "jp",
    }
    return lookup.get(normalized(value or ""))

def musicbrainz_tracks(mb: dict) -> tuple[list[str], bool]:
    media = mb.get("media")
    if not isinstance(media, list) or not media:
        return [], False
    group_count = mb.get("media-count")
    complete = group_count is None or group_count == len(media)
    titles = []
    for medium in media:
        if not isinstance(medium, dict):
            return [], False
        tracks = medium.get("tracks")
        if not isinstance(tracks, list) or not tracks:
            return [], False
        if medium.get("track-count") != len(tracks):
            complete = False
        for item in tracks:
            if not isinstance(item, dict) or not isinstance(item.get("id"), str):
                complete = False
                continue
            title = item.get("title") or (item.get("recording") or {}).get("title")
            if not isinstance(title, str) or not title.strip():
                complete = False
                continue
            titles.append(normalized(title))
    return titles, bool(complete and titles)

def compare_edition(mb: dict, release: dict) -> dict:
    mb_names = [
        (credit.get("name") or (credit.get("artist") or {}).get("name"))
        for credit in mb.get("artist-credit", [])
        if isinstance(credit, dict)
    ]
    external = [
        artist.get("name")
        for artist in release.get("artists", [])
        if isinstance(artist, dict)
    ]
    expected = sorted(normalized(name) for name in mb_names if isinstance(name, str))
    actual = sorted(normalized(name) for name in external if isinstance(name, str))
    title = normalized(str(release.get("title") or ""))
    expected_title = normalized(str(mb.get("title") or ""))
    artist_prefixes = [name + " - " for name in expected]
    without_prefix = next((title[len(p):] for p in artist_prefixes
                           if title.startswith(p)), title)
    titles, complete = musicbrainz_tracks(mb)
    raw_tracks = release.get("tracklist")
    discogs_titles = [
        normalized(entry["title"])
        for entry in raw_tracks
        if isinstance(entry, dict) and
        entry.get("type_", "track") == "track" and
        isinstance(entry.get("title"), str)
    ] if isinstance(raw_tracks, list) else []
    mb_barcode = "".join(ch for ch in str(mb.get("barcode") or "") if ch.isdigit())
    other_barcodes = {
        "".join(ch for ch in str(row.get("value") or "") if ch.isdigit())
        for row in release.get("identifiers", [])
        if isinstance(row, dict) and row.get("type") == "Barcode"
    }
    other_barcodes.discard("")
    mb_country = country_code(mb.get("country"))
    external_country = country_code(release.get("country"))
    formats = release.get("formats")
    return {
        "artist_matches": bool(expected and expected == actual),
        "title_matches": bool(expected_title and expected_title == without_prefix),
        "tracklist_complete": complete,
        "tracklist_matches": bool(complete and titles == discogs_titles),
        "track_count": len(titles),
        "discogs_track_count": len(discogs_titles),
        "track_positions_equal": len(titles) == len(discogs_titles),
        "exact_matching_track_positions": sum(
            expected == observed
            for expected, observed in zip(titles, discogs_titles)
        ),
        "first_title_mismatch_position": next(
            (index for index, (expected, observed) in enumerate(
                zip(titles, discogs_titles), start=1
            ) if expected != observed), None,
        ),
        "cross_script_mismatch_positions": sum(
            expected != observed and (
                (any("\uac00" <= ch <= "\ud7af" for ch in expected) and
                 any("a" <= ch <= "z" for ch in observed)) or
                (any("\uac00" <= ch <= "\ud7af" for ch in observed) and
                 any("a" <= ch <= "z" for ch in expected))
            )
            for expected, observed in zip(titles, discogs_titles)
        ),
        "year_matches": bool(str(mb.get("date") or "")[:4].isdigit() and
                             str(mb.get("date") or "")[:4] ==
                             str(release.get("year") or "")),
        "country_compatible": (mb_country is None or external_country is None or
                               mb_country == external_country),
        "barcode_compatible": (not mb_barcode or not other_barcodes or
                               mb_barcode in other_barcodes),
        "discogs_explicit_type": explicit_discogs_type(
            formats if isinstance(formats, list) else [],
        ),
    }

def main() -> int:
    began = time.monotonic()
    observation = {
        "observed_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "musicbrainz_release_mbid": MBID,
        "discogs_release_id": DISCOGS_RELEASE_ID,
        "musicbrainz_status": None,
        "discogs_status": None,
        "source_ids_match": False,
        "direct_release_relationship": False,
        "musicbrainz_release_group_mbid_present": False,
        "musicbrainz_official": False,
        "discogs_format_descriptions_present": False,
        "result": "not_checked",
    }
    try:
        status, mb = read_json(MB_URL)
        observation["musicbrainz_status"] = status
        observation["musicbrainz_release_group_mbid_present"] = bool(
            re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}",
                str((mb.get("release-group") or {}).get("id", "")))
        )
        observation["musicbrainz_official"] = (
            str(mb.get("status", "")).lower() == "official"
        )
        observation["direct_release_relationship"] = exact_discogs_relationship(mb)
        status, release = read_json(DISCOGS_URL)
        observation["discogs_status"] = status
        observation["source_ids_match"] = (
            mb.get("id") == MBID and release.get("id") == DISCOGS_RELEASE_ID
        )
        formats = release.get("formats")
        observation["discogs_format_descriptions_present"] = (
            isinstance(formats, list) and any(
                isinstance(fmt, dict) and
                isinstance(fmt.get("descriptions"), list) and
                bool(fmt["descriptions"])
                for fmt in formats
            )
        )
        observation.update(compare_edition(mb, release))
        # The observation labels only field compatibility. The app's VERIFIED
        # verdict requires its own complete matcher, source state and TTL.
        observation["public_edition_facts_compatible"] = all(
            observation[field] for field in (
                "artist_matches", "title_matches", "tracklist_complete",
                "tracklist_matches", "year_matches",
                "country_compatible", "barcode_compatible",
            )
        )
        observation["result"] = (
            "public_link_observed"
            if observation["source_ids_match"] and
                observation["direct_release_relationship"] and
                observation["musicbrainz_release_group_mbid_present"] and
                observation["musicbrainz_official"]
            else "public_sources_disagree"
        )
    except urllib.error.HTTPError as exc:
        observation["result"] = "source_http_unavailable"
        if observation["musicbrainz_status"] is None:
            observation["musicbrainz_status"] = exc.code
        else:
            observation["discogs_status"] = exc.code
    except (urllib.error.URLError, TimeoutError, OSError):
        observation["result"] = "network_unavailable"
    except (ValueError, TypeError, AttributeError, json.JSONDecodeError):
        observation["result"] = "invalid_or_oversized_response"
    observation["elapsed_ms"] = round((time.monotonic() - began) * 1000)
    pathlib.Path("discogs-211-public-link-observation.json").write_text(
        json.dumps(observation, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(observation, ensure_ascii=False, sort_keys=True))
    # A single public API observation is deliberately not a release match gate.
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

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
    "fmt": "json", "inc": "artist-credits+release-groups+url-rels",
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
        observation["discogs_format_descriptions_present"] = any(
            isinstance(fmt, dict) and
            isinstance(fmt.get("descriptions"), list) and
            bool(fmt.get("descriptions"))
            for fmt in release.get("formats", [])
            if isinstance(release.get("formats"), list)
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

#!/usr/bin/env python3
"""Two-source public MusicBrainz check for a documented corrected tracklist.

Lemon Demon - Damn Skippy release 05a49eba... had its CD2 order/recordings
corrected in applied MusicBrainz edit 112201206 (May 2024).
This inspects the CURRENT JSON and XML release snapshots only; it never
claims to reconstruct the previous snapshot, run Kotlin, Room, or the UI.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

from catalog_178_musicbrainz_live_probe import APP_AGENT, PublicMusicBrainz

RELEASE_MBID = "05a49eba-0311-422b-bc12-0bec04a1cdda"
HISTORICAL_EDIT_URL = "https://musicbrainz.org/edit/112201206"
NAMESPACE = "http://musicbrainz.org/ns/mmd-2.0#"
UUID = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$", re.I)
XML_URL = f"https://musicbrainz.org/ws/2/release/{RELEASE_MBID}?inc=recordings&fmt=xml"


def valid_id(value: object) -> bool:
    return isinstance(value, str) and UUID.fullmatch(value) is not None


def nonnegative_int(value: object) -> int:
    if type(value) is not int or value < 0:
        raise ValueError("missing or invalid integer")
    return value


def normalized(rows: list[tuple[str, str, int, int]], media_count: int) -> tuple:
    if not rows or len(rows) > 2000 or not 1 <= media_count <= 100:
        raise ValueError("empty/unbounded release")
    if len({x[0] for x in rows}) != len(rows):
        raise ValueError("Track MBID appears more than once")
    if len({(x[2], x[3]) for x in rows}) != len(rows):
        raise ValueError("duplicate track positions")
    return tuple(sorted(rows))


def json_rows(doc: object) -> tuple:
    if not isinstance(doc, dict) or doc.get("id") != RELEASE_MBID:
        raise ValueError("not the expected release")
    media = doc.get("media")
    if not isinstance(media, list) or not 1 <= len(media) <= 100:
        raise ValueError("missing/unbounded media")
    result = []
    mediums = []
    for medium in media:
        if not isinstance(medium, dict):
            raise ValueError("invalid medium")
        position = nonnegative_int(medium.get("position"))
        mediums.append(position)
        if nonnegative_int(medium.get("track-offset", 0)) != 0:
            raise ValueError("partial medium")
        tracks = medium.get("tracks")
        if not isinstance(tracks, list) or len(tracks) != nonnegative_int(medium.get("track-count")):
            raise ValueError("missing/partial track list")
        for row in tracks:
            if not isinstance(row, dict):
                raise ValueError("invalid track row")
            recording = row.get("recording")
            tid = row.get("id")
            rid = recording.get("id") if isinstance(recording, dict) else None
            if not valid_id(tid) or not valid_id(rid):
                raise ValueError("invalid Track/Recording identity")
            result.append((tid.lower(), rid.lower(), position, nonnegative_int(row.get("position"))))
    if len(set(mediums)) != len(mediums):
        raise ValueError("duplicate medium position")
    return normalized(result, len(media))


def xml_rows(xml: str) -> tuple:
    if not 1 <= len(xml) <= 2_000_000 or "<!DOCTYPE" in xml.upper() or "<!ENTITY" in xml.upper():
        raise ValueError("unbounded/unsafe XML")
    n = lambda name: f"{{{NAMESPACE}}}{name}"
    root = ET.fromstring(xml)
    if root.tag != n("metadata"):
        raise ValueError("non-MusicBrainz XML namespace")
    releases = root.findall(n("release"))
    if len(releases) != 1 or releases[0].attrib.get("id") != RELEASE_MBID:
        raise ValueError("wrong release")
    medium_lists = releases[0].findall(n("medium-list"))
    if len(medium_lists) != 1:
        raise ValueError("missing medium list")
    medium_list = medium_lists[0]
    if medium_list.attrib.get("offset", "0") != "0":
        raise ValueError("partial medium list")
    media = medium_list.findall(n("medium"))
    if len(media) != nonnegative_int(int(medium_list.attrib.get("count", "-1"))):
        raise ValueError("incomplete medium list")
    rows = []
    mediums = []
    for medium in media:
        medium_position = nonnegative_int(int(medium.findtext(n("position"), "-1")))
        mediums.append(medium_position)
        lists = medium.findall(n("track-list"))
        if len(lists) != 1 or lists[0].attrib.get("offset", "0") != "0":
            raise ValueError("incomplete track list")
        track_list = lists[0]
        tracks = track_list.findall(n("track"))
        if len(tracks) != nonnegative_int(int(track_list.attrib.get("count", "-1"))):
            raise ValueError("track-count mismatch")
        for track in tracks:
            recordings = track.findall(n("recording"))
            if len(recordings) != 1 or not valid_id(track.attrib.get("id")) or not valid_id(recordings[0].attrib.get("id")):
                raise ValueError("missing Track/Recording")
            p = nonnegative_int(int(track.findtext(n("position"), "-1")))
            rows.append((track.attrib["id"].lower(), recordings[0].attrib["id"].lower(), medium_position, p))
    if len(set(mediums)) != len(mediums):
        raise ValueError("duplicate medium position")
    return normalized(rows, len(media))


def compare(document: object, xml: str) -> dict:
    output = {
        "scope": "PUBLIC_API_ONLY",
        "release_mbid": RELEASE_MBID,
        "historical_edit_url": HISTORICAL_EDIT_URL,
        "historical_pre_edit_state_reconstructed": False,
        "android_ui_verified": False,
        "kotlin_room_verified": False,
        "xml_json_track_identity_and_order_equal": False,
        "status": "FAIL",
    }
    try:
        source = json_rows(document)
        independent = xml_rows(xml)
        output["track_count"] = len(source)
        output["medium_count"] = len({r[2] for r in source})
        output["xml_json_track_identity_and_order_equal"] = source == independent
        output["status"] = "PASS" if source == independent else "FAIL"
        output["sample_track_mbids"] = [row[0] for row in source[:3]]
    except (ValueError, TypeError, ET.ParseError, OverflowError) as err:
        output["error_kind"] = type(err).__name__
    return output


def run() -> dict:
    client = PublicMusicBrainz(interval=1.1, timeout=22.0, max_http_requests=3)
    report = {
        "scope": "PUBLIC_API_ONLY",
        "release_mbid": RELEASE_MBID,
        "historical_edit_url": HISTORICAL_EDIT_URL,
        "status": "INCONCLUSIVE",
        "http_attempt_limit": 4,
        "observed_utc": datetime.now(timezone.utc).isoformat(),
        "android_ui_verified": False,
        "historical_pre_edit_state_reconstructed": False,
    }
    extra_requests = 0
    try:
        document = client.get("release", RELEASE_MBID, inc="recordings")
        # The JSON client uses mandatory 1.1 s spacing; the second XML GET
        # must also wait because it uses a separate urllib transport.
        time.sleep(1.1)
        extra_requests = 1
        request = Request(XML_URL, headers={"User-Agent": APP_AGENT, "Accept": "application/xml"})
        with urlopen(request, timeout=22.0) as response:
            xml = response.read(2_000_001).decode("utf-8")
        report.update(compare(document, xml))
    except (HTTPError, URLError, TimeoutError, OSError, ValueError, UnicodeError) as err:
        report["status"] = "INCONCLUSIVE"
        report["error_kind"] = type(err).__name__
    report["http_attempts"] = len(client.requests) + extra_requests
    report["http_status_counts"] = {
        str(status): sum(1 for row in client.requests if row.get("http_status") == status)
        for status in sorted({row.get("http_status") for row in client.requests if row.get("http_status") is not None})
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("catalog-209-edited-release-proof.json"))
    args = parser.parse_args()
    outcome = run()
    args.output.write_text(json.dumps(outcome, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(outcome, sort_keys=True))
    return 0 if outcome["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())

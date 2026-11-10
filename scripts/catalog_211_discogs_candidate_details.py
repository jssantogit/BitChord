#!/usr/bin/env python3
"""Bounded public Discogs Release details for #211; never proof of MB absence.

Input: candidate-index artifact. No token, untrusted URLs, redirects, artwork or
playback. A candidate is not a Verified match nor a reviewed corpus entry.
"""
from __future__ import annotations
import datetime as dt
import json
from pathlib import Path
import time

from catalog_211_discogs_corpus_candidates import ARTISTS, normalized
from catalog_211_discogs_public_link_probe import read_json

INPUT = Path("discogs-211-corpus-candidates.json")
OUTPUT = Path("discogs-211-corpus-edition-observations.json")
MAX_CANDIDATES, MAX_PER_ARTIST, MAX_REQUESTS = 8, 2, 16
ID_MAX = 999_999_999_999

def valid_id(v: object) -> bool:
    return type(v) is int and 1 <= v <= ID_MAX

def candidate_queue(report: dict) -> list[dict]:
    if not isinstance(report, dict) or report.get("schema_version") != 1:
        return []
    rows = report.get("artists")
    if not isinstance(rows, list) or len(rows) != len(ARTISTS):
        return []
    allowed = dict(ARTISTS)
    per_artist = {}
    for row in rows:
        if not isinstance(row, dict):
            return []
        artist = row.get("artist")
        if artist not in allowed or row.get("musicbrainz_artist_mbid") != allowed[artist]:
            return []
        if artist in per_artist:
            return []
        hints = row.get("candidate_index_hints")
        if not isinstance(hints, list) or len(hints) > 12:
            return []
        accepted = []
        if row.get("status") == "index_compared_not_adjudicated" and valid_id(row.get("discogs_artist_id")):
            for h in hints:
                if not isinstance(h, dict):
                    continue
                kind, rid, title = h.get("source_kind"), h.get("source_id"), h.get("title")
                if (kind not in ("master", "release") or not valid_id(rid) or
                    not isinstance(title, str) or not 1 <= len(title.strip()) <= 240 or
                    h.get("url") != f"https://www.discogs.com/{kind}/{rid}" or
                    h.get("assessment") != "index_title_not_exact_mb_group_title"):
                    continue
                accepted.append({
                    "artist": artist, "musicbrainz_artist_mbid": allowed[artist],
                    "discogs_artist_id": row["discogs_artist_id"],
                    "source_kind": kind, "source_id": rid, "index_title": title.strip(),
                })
        per_artist[artist] = accepted
    selected, seen = [], set()
    for n in range(MAX_PER_ARTIST):
        for artist, _ in ARTISTS:
            if len(selected) == MAX_CANDIDATES:
                return selected
            values = per_artist.get(artist, [])
            if n < len(values):
                item = values[n]
                key = (item["source_kind"], item["source_id"])
                if key not in seen:
                    seen.add(key)
                    selected.append(item)
    return selected

def hydrate(entry: dict, get) -> dict:
    output = {**entry, "status": "provider_unavailable", "discogs_release_id": None}
    try:
        source_id = entry["source_id"]
        if not valid_id(source_id) or entry["source_kind"] not in ("master", "release"):
            return {**output, "status": "invalid_source_identity"}
        release_id = source_id
        if entry["source_kind"] == "master":
            master = get(f"https://api.discogs.com/masters/{source_id}")
            if not isinstance(master, dict) or type(master.get("id")) is not int or (
                master["id"] != source_id or not valid_id(master.get("main_release"))
            ):
                return {**output, "status": "invalid_master"}
            release_id = master["main_release"]
        release = get(f"https://api.discogs.com/releases/{release_id}")
        if (not isinstance(release, dict) or type(release.get("id")) is not int or
            release["id"] != release_id):
            return {**output, "status": "invalid_release"}
        if entry["source_kind"] == "master" and (
            type(release.get("master_id")) is not int or release["master_id"] != source_id
        ):
            return {**output, "status": "master_release_disagrees"}
        output["discogs_release_id"] = release_id
        title, artists = release.get("title"), release.get("artists")
        formats, labels = release.get("formats"), release.get("labels")
        tracks = release.get("tracklist")
        if (not isinstance(title, str) or not 1 <= len(title.strip()) <= 240 or
            not isinstance(artists, list) or not 1 <= len(artists) <= 20 or
            not isinstance(formats, list) or len(formats) > 20 or
            not isinstance(labels, list) or len(labels) > 40 or
            not isinstance(tracks, list) or len(tracks) > 250):
            return {**output, "status": "incomplete_release_payload"}
        names = [a.get("name") for a in artists if isinstance(a, dict)]
        if len(names) != len(artists) or not all(isinstance(n, str) and len(n) <= 160 for n in names):
            return {**output, "status": "invalid_artist_credits"}
        raw_ids = [a.get("id") for a in artists]
        artist_ids = raw_ids if all(valid_id(i) for i in raw_ids) else []
        output["artist_ids"] = artist_ids
        output["artist_id_matches_relation"] = (
            len(artist_ids) == 1 and artist_ids[0] == entry["discogs_artist_id"]
        )
        fmt_names, desc = set(), set()
        for fmt in formats:
            if not isinstance(fmt, dict):
                continue
            name = fmt.get("name")
            if isinstance(name, str) and len(name) <= 80:
                fmt_names.add(name.strip())
            values = fmt.get("descriptions")
            if isinstance(values, list):
                desc.update(v.strip() for v in values[:20]
                            if isinstance(v, str) and len(v) <= 80)
        label_names = [label["name"][:120] for label in labels[:5]
                       if isinstance(label, dict) and isinstance(label.get("name"), str)]
        year = release.get("year")
        if type(year) is not int or not 1800 <= year <= 2200:
            year = None
        country = release.get("country")
        if not isinstance(country, str) or len(country) > 120:
            country = None
        output.update({
            "title": title.strip(), "artist_credits": names, "country": country,
            "year": year, "format_names": sorted(fmt_names),
            "format_descriptions": sorted(desc),
            "track_rows": sum(isinstance(t, dict) and t.get("type_", "track") == "track"
                              for t in tracks),
            "labels": label_names,
            "master_id": release.get("master_id") if valid_id(release.get("master_id")) else None,
        })
        video = {"dvd", "blu-ray", "vhs", "laserdisc", "video", "vcd", "video cd"}
        if fmt_names and all(normalized(name) in video for name in fmt_names):
            output["status"] = "video_only_format"
        elif len(artist_ids) != 1:
            output["status"] = "artist_id_unavailable_or_multiple_credits"
        elif artist_ids[0] != entry["discogs_artist_id"]:
            output["status"] = "artist_id_disagrees_with_relation"
        else:
            output["status"] = "edition_metadata_observed_not_adjudicated"
    except (ValueError, TypeError, KeyError, OSError, TimeoutError):
        output["status"] = "provider_unavailable"
    return output

def observe(report: dict, get) -> dict:
    request_count = 0
    def bounded_get(url):
        nonlocal request_count
        if request_count >= MAX_REQUESTS:
            raise ValueError("request_budget_exceeded")
        request_count += 1
        return get(url)
    output = {
        "schema_version": 1,
        "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "reviewed_corpus_cases": 0,
        "warning": "Public provider facts only: no MusicBrainz absence or editorial match verified",
        "max_candidates": MAX_CANDIDATES,
        "observations": [hydrate(entry, bounded_get) for entry in candidate_queue(report)],
    }
    output["provider_requests"] = request_count
    return output

def main() -> None:
    doc = json.loads(INPUT.read_text(encoding="utf-8"))
    def public_get(url):
        time.sleep(1.1)
        return read_json(url)[1]
    result = observe(doc, public_get)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                      encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()

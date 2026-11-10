#!/usr/bin/env python3
"""Bounded, token-free C-cohort candidate scouting for Discogs #211.

A candidate is ONLY a public index title not equal to an entry in a complete
MusicBrainz ReleaseGroup browse. It is NOT evidence of a missing MB release,
verified edition, correct artist credit, or a corpus-reviewed case.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import re
import time
import unicodedata
from urllib.parse import urlencode, urlsplit
from urllib.error import HTTPError, URLError

from catalog_211_discogs_public_link_probe import read_json, parse_public_artist_page

ARTISTS = (
    ("ENHYPEN", "95663592-1447-4579-90f7-c94733476667"),
    ("ILLIT", "6b128dd7-e998-4edb-a4f5-400fef190445"),
    ("NewJeans", "49204a7a-ed85-407a-828f-6fd46f1d8126"),
    ("LE SSERAFIM", "1ee37742-1e3d-4e61-84d2-bc85f4c1459a"),
    ("IVE", "b2f2216a-d7a9-4ce0-8b8f-f494d9a8c196"),
    ("aespa", "b51c672b-85e0-48fe-8648-470a2422229f"),
    ("NMIXX", "2d623e82-73e2-4179-9c44-e963980f2d58"),
    ("TOMORROW X TOGETHER", "9d027d72-790e-40ec-bbb7-61aa613457de"),
    ("BABYMONSTER", "5829cd4f-e693-4b03-abe5-1226243c23d6"),
    ("Stray Kids", "142b343d-bf5a-428c-a64f-6d1a7566bbe9"),
)
MBID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", re.I)
DISCOGS_PATH = re.compile(r"/artist/([1-9][0-9]{0,11})(?:-[A-Za-z0-9_-]+)?/?")
PER_PAGE = 75
MAX_GROUPS = 300
OUTPUT = Path("discogs-211-corpus-candidates.json")


def normalized(value: str) -> str:
    text = " ".join(unicodedata.normalize("NFKC", value).casefold().split())
    return re.sub(r"\s*:\s*", ": ", text.replace("’", "'").replace("‘", "'"))


def comparable_title(value: str, artist: str) -> str:
    title = normalized(value)
    prefix = normalized(artist) + " - "
    return title[len(prefix):] if title.startswith(prefix) else title


def lexical_signature(value: str) -> tuple[str, ...]:
    # Whole Unicode letter/digit tokens; no fuzzy words or translations.
    return tuple(re.findall(r"[^\W_]+", value, re.UNICODE))


def bilingual_aliases(value: str) -> set[str]:
    # Only MB's explicit "合 (HOP)" / "ジュエリー (Jewelry)" form,
    # never inferred cross-script synonyms, remixes or generic qualifiers.
    match = re.fullmatch(r"(.+?)\s+\(([a-z][a-z0-9 &'’!.\-]{2,65})\)", value)
    if not match:
        return set()
    native, alias = match.group(1), match.group(2).strip()
    if not any(
        "\uac00" <= ch <= "\ud7a3" or
        "\u3040" <= ch <= "\u30ff" or
        "\u4e00" <= ch <= "\u9fff"
        for ch in native
    ):
        return set()
    if sum("a" <= ch <= "z" for ch in alias) < 3 or alias in {
        "live", "remix", "demo", "acoustic", "instrumental",
        "version", "english", "japanese", "korean", "explicit", "clean",
    }:
        return set()
    return {alias}


def known_group_title(title: str, artist: str, known: set[str]) -> bool:
    value = comparable_title(title, artist)
    if value in known or any(value in bilingual_aliases(group) for group in known):
        return True
    signature = lexical_signature(value)
    if len(signature) >= 3 and any(lexical_signature(item) == signature for item in known):
        return True
    ending = re.search(r"\s*[\(\[]([^\(\)\[\]]{1,72})[\)\]]$", value)
    if not ending:
        return False
    suffix = ending.group(1)
    if (not re.search(r"\b(?:version|edition|ver\.?|explicit|clean)\b", suffix) or
        re.search(r"\b(?:remix|live|acoustic|instrumental|sped\s+up|slowed)\b", suffix)):
        return False
    return value[:ending.start()].strip() in known


def discogs_artist_relation(payload: dict, expected_mbid: str) -> int | None:
    if not MBID.fullmatch(expected_mbid) or not isinstance(payload, dict) or (
        str(payload.get("id", "")).lower() != expected_mbid.lower()
    ):
        return None
    links = payload.get("relations")
    if not isinstance(links, list) or len(links) > 250:
        return None
    ids: set[int] = set()
    for relation in links:
        if not isinstance(relation, dict):
            return None
        if relation.get("type") != "discogs" or relation.get("ended") is True:
            continue
        url = relation.get("url")
        address = url.get("resource") if isinstance(url, dict) else None
        if not isinstance(address, str) or len(address) > 512:
            return None
        parsed = urlsplit(address)
        if (parsed.scheme not in ("http", "https") or
            parsed.hostname not in ("discogs.com", "www.discogs.com") or
            parsed.username or parsed.password or parsed.query or parsed.fragment):
            return None
        try:
            if parsed.port is not None:
                return None
        except ValueError:
            return None
        found = DISCOGS_PATH.fullmatch(parsed.path)
        if not found:
            return None
        ids.add(int(found.group(1)))
    return next(iter(ids)) if len(ids) == 1 else None


def complete_mb_titles(artist_mbid: str, get, pause=lambda: None) -> set[str] | None:
    """Only 1..4 complete, consistent pages can justify the title comparison."""
    if not MBID.fullmatch(artist_mbid):
        return None
    total = None
    offset = 0
    identities = set()
    titles = set()
    for _ in range(4):
        url = "https://musicbrainz.org/ws/2/release-group/?" + urlencode({
            "artist": artist_mbid, "fmt": "json",
            "limit": PER_PAGE, "offset": offset,
        })
        pause()
        raw = get(url)
        if not isinstance(raw, dict):
            return None
        groups = raw.get("release-groups")
        server_total = raw.get("release-group-count")
        server_offset = raw.get("release-group-offset")
        if (not isinstance(groups, list) or type(server_total) is not int or
            type(server_offset) is not int or server_offset != offset or
            server_total < 0 or server_total > MAX_GROUPS or
            (total is not None and server_total != total) or
            len(groups) > PER_PAGE or offset + len(groups) > server_total):
            return None
        total = server_total
        if total == 0:
            return set() if offset == 0 and not groups else None
        if not groups:
            return None
        for row in groups:
            if not isinstance(row, dict):
                return None
            rgid, title = row.get("id"), row.get("title")
            if (not isinstance(rgid, str) or not MBID.fullmatch(rgid) or
                rgid.lower() in identities or not isinstance(title, str) or
                not title.strip()):
                return None
            identities.add(rgid.lower())
            titles.add(normalized(title))
        offset += len(groups)
        if offset == total and len(identities) == total:
            return titles
    return None


def public_index_hints(payload: dict, page: int = 1) -> list[dict] | None:
    if parse_public_artist_page(payload, page) is None:
        return None
    hints: dict[tuple[str, int], dict] = {}
    for row in payload["releases"]:
        if not isinstance(row, dict) or row.get("role") != "Main":
            continue
        kind, rid, title = row.get("type"), row.get("id"), row.get("title")
        if (kind not in ("release", "master") or
            type(rid) is not int or not 1 <= rid <= 999_999_999_999 or
            not isinstance(title, str) or not 1 <= len(title) <= 240):
            continue
        key = (kind, rid)
        if key not in hints or (not hints[key]["title"].strip() and title.strip()):
            hints[key] = {
                "source_kind": kind, "source_id": rid, "title": title.strip(),
                "url": f"https://www.discogs.com/{kind}/{rid}",
            }
    return list(hints.values())[:12]


def scout_artist(name: str, mbid: str, get, pause=lambda: None) -> dict:
    finding = {"artist": name, "musicbrainz_artist_mbid": mbid,
               "status": "unavailable", "discogs_artist_id": None,
               "distinct_mb_titles_count": None, "indexed_rows": 0,
               "exact_title_matches": 0, "candidate_index_hints": [],
               "pages_checked": []}
    try:
        pause()
        artist = get("https://musicbrainz.org/ws/2/artist/" + mbid +
                     "?fmt=json&inc=url-rels")
        artist_id = discogs_artist_relation(artist, mbid)
        if artist_id is None:
            finding["status"] = "no_unique_artist_relation"
            return finding
        finding["discogs_artist_id"] = artist_id
        known = complete_mb_titles(mbid, get, pause)
        if known is None:
            finding["status"] = "incomplete_mb_browse"
            return finding
        finding["distinct_mb_titles_count"] = len(known)
        pause()
        url = f"https://api.discogs.com/artists/{artist_id}/releases?" + urlencode({
            "page": 1, "per_page": 12, "sort": "year", "sort_order": "desc",
        })
        page = get(url)
        hints = public_index_hints(page)
        if hints is None:
            finding["status"] = "invalid_discogs_index"
            return finding
        finding["pages_checked"].append(1)
        def record_hints(rows):
            finding["indexed_rows"] += len(rows)
            finding["exact_title_matches"] += sum(
                known_group_title(hint["title"], name, known) for hint in rows)
            finding["candidate_index_hints"] += [
                {**hint, "assessment": "index_title_not_exact_mb_group_title"}
                for hint in rows if not known_group_title(hint["title"], name, known)
            ]
        record_hints(hints)
        # Mirror the native two-page policy, never exhaustively crawl Discogs.
        # Page 2 is inspected only when page 1 supplies no new title hint.
        if not finding["candidate_index_hints"] and page["pagination"]["pages"] >= 2:
            pause()
            second_url = f"https://api.discogs.com/artists/{artist_id}/releases?" + urlencode({
                "page": 2, "per_page": 12, "sort": "year", "sort_order": "desc",
            })
            second = get(second_url)
            second_hints = public_index_hints(second, 2)
            if second_hints is None:
                finding["status"] = "invalid_discogs_second_page"
                return finding
            finding["pages_checked"].append(2)
            record_hints(second_hints)
        finding["status"] = "index_compared_not_adjudicated"
    except (HTTPError, URLError, TimeoutError, OSError):
        finding["status"] = "network_unavailable"
    except (ValueError, TypeError, KeyError, UnicodeError):
        finding["status"] = "invalid_or_unavailable_response"
    return finding


def main() -> None:
    observed = {
        "schema_version": 1,
        "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "disclaimer": "Index-only title leads, NOT reviewed corpus cases or proven MB absences",
        "artists": [],
    }
    def get(url: str) -> dict:
        return read_json(url)[1]
    for name, mbid in ARTISTS:
        finding = scout_artist(name, mbid, get, pause=lambda: time.sleep(1.1))
        observed["artists"].append(finding)
    OUTPUT.write_text(json.dumps(observed, indent=2, ensure_ascii=False) + "\n",
                      encoding="utf-8")
    print(json.dumps(observed, ensure_ascii=False))


if __name__ == "__main__":
    main()

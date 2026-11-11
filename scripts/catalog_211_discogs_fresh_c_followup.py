#!/usr/bin/env python3
"""Bounded C-only follow-up of public Discogs leads not already sampled or seeded.

This reuses the public index, hydration, barcode, and artist-scoped MB title
probes. It never asserts a missing MusicBrainz release or exact pressing.
The first scan's eight source identities and seeded B/C release IDs are
excluded, preventing previously known editions from becoming fake C gains.
"""
from __future__ import annotations

from copy import deepcopy
import datetime as dt
import json
from pathlib import Path
import time

from catalog_211_discogs_candidate_details import (
    candidate_queue, observe, valid_id, MAX_CANDIDATES,
)
from catalog_211_discogs_corpus_candidates import ARTISTS
from catalog_211_discogs_corpus_report import report, selected_identity
from catalog_211_discogs_source_only_reconciliation import reconcile
from catalog_211_discogs_source_only_title_triage import triage
from catalog_211_discogs_public_link_probe import read_json

INDEX = Path("discogs-211-corpus-candidates.json")
FIRST = Path("discogs-211-corpus-edition-observations.json")
MANIFEST = Path("docs/plans/catalog-211-discogs-corpus-manifest.json")
OUTPUT = Path("discogs-211-fresh-c-followup.json")


def _source_key(row: dict) -> tuple | None:
    kind, sid = row.get("source_kind"), row.get("source_id")
    if kind not in ("master", "release") or not valid_id(sid):
        return None
    return kind, sid


def _known_releases(manifest: dict) -> set[int] | None:
    """Read existing preselected cases, never invent known release identities."""
    if not isinstance(manifest, dict):
        return None
    try:
        evaluation = report(manifest)
    except (ValueError, KeyError, TypeError):
        return None
    if evaluation["errors"]:
        return None
    identities = set()
    for case in manifest["cases"]:
        if "selection_state" not in case:
            continue
        if case["cohort"] not in ("unlinked", "source_only"):
            return None
        if selected_identity(case) is None or case.get("reviewed") is not False:
            return None
        identities.add(case["discogs_release_id"])
    return identities


def build_followup_index(index: dict, first: dict, manifest: dict) -> dict | None:
    """Keep only not-yet-sampled source IDs, without mutating original evidence."""
    known = _known_releases(manifest)
    if known is None or not isinstance(index, dict) or not isinstance(first, dict):
        return None
    if index.get("schema_version") != 1 or first.get("schema_version") != 1:
        return None
    artists, observations = index.get("artists"), first.get("observations")
    if not isinstance(artists, list) or len(artists) != len(ARTISTS) or (
        not isinstance(observations, list) or len(observations) > MAX_CANDIDATES
    ):
        return None
    expected_queue = candidate_queue(index)
    # Reject missing or re-ordered first-scan evidence rather than skipping
    # an unknown number of already-inspected sources.
    if len(observations) != len(expected_queue):
        return None
    seen = set()
    for actual, expected in zip(observations, expected_queue):
        if not isinstance(actual, dict) or (
            _source_key(actual) != _source_key(expected)
        ) or any(actual.get(name) != expected[name] for name in (
            "artist", "musicbrainz_artist_mbid", "discogs_artist_id",
        )):
            return None
        key = _source_key(actual)
        if key in seen:
            return None
        seen.add(key)
    filtered = deepcopy(index)
    for row, (artist, mbid) in zip(filtered["artists"], ARTISTS):
        if not isinstance(row, dict) or row.get("artist") != artist or (
            row.get("musicbrainz_artist_mbid") != mbid
        ):
            return None
        hints = row.get("candidate_index_hints")
        if not isinstance(hints, list) or len(hints) > 12:
            return None
        kept = []
        for hint in hints:
            if not isinstance(hint, dict):
                return None
            key = _source_key(hint)
            if key in seen:
                continue
            # Release IDs may be filtered before HTTP; Master IDs are
            # resolved to their exact Release ID during hydrate().
            if key is not None and key[0] == "release" and key[1] in known:
                continue
            kept.append(hint)
        row["candidate_index_hints"] = kept
    return filtered


def probe_followup(index: dict, first: dict, manifest: dict, get) -> dict:
    result = {
        "schema_version": 1,
        "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "reviewed_corpus_cases": 0, "confirmed_source_only_cases": 0,
        "independent_edition_leads_not_adjudicated": 0,
        "discogs_requests": 0, "musicbrainz_requests": 0,
        "warning": "Independent C leads are NOT proven MusicBrainz absences or Verified editions",
        "observations": [],
    }
    fresh_index = build_followup_index(index, first, manifest)
    if fresh_index is None or not candidate_queue(fresh_index):
        return result
    already_known = _known_releases(manifest)
    already_known.update(
        entry["discogs_release_id"] for entry in first["observations"]
        if valid_id(entry.get("discogs_release_id"))
    )
    editions = observe(fresh_index, get)
    result["discogs_requests"] = editions["provider_requests"]
    checked_releases: set[int] = set()
    for row in editions["observations"]:
        release_id = row.get("discogs_release_id")
        if valid_id(release_id):
            if release_id in already_known or release_id in checked_releases:
                row["status"] = "already_seeded_b_or_c_release"
            checked_releases.add(release_id)
    barcodes = reconcile(fresh_index, editions, get)
    titles = triage(fresh_index, editions, barcodes, get)
    result["musicbrainz_requests"] = (
        barcodes["provider_requests"] + titles["provider_requests"]
    )
    for source, barcode, title in zip(
        editions["observations"], barcodes["observations"], titles["observations"],
    ):
        status = source.get("status", "unavailable")
        if status == "edition_metadata_observed_not_adjudicated":
            result["independent_edition_leads_not_adjudicated"] += 1
        result["observations"].append({
            "artist": source.get("artist"),
            "musicbrainz_artist_mbid": source.get("musicbrainz_artist_mbid"),
            "source_kind": source.get("source_kind"),
            "source_id": source.get("source_id"),
            "discogs_release_id": source.get("discogs_release_id"),
            "title": source.get("title"),
            "status": status,
            "barcode_status": barcode.get("status", "not_checked"),
            "matched_mb_release_mbids": barcode.get("matched_mb_release_mbids", []),
            "title_status": (title.get("status") if status ==
                             "edition_metadata_observed_not_adjudicated" else
                             "not_checked"),
            "mb_group_candidate_mbids": title.get("mb_group_candidate_mbids", []),
            "reviewed": False,
            "confirmed_missing_from_musicbrainz": False,
        })
    return result


def main() -> None:
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    first = json.loads(FIRST.read_text(encoding="utf-8"))
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    def get(url):
        time.sleep(1.1)
        return read_json(url)[1]
    output = probe_followup(index, first, manifest, get)
    OUTPUT.write_text(
        json.dumps(output, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

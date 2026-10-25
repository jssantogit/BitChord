#!/usr/bin/env python3
"""Public MusicBrainz HTTP evidence for Android catalog issue #178.

A small live subset of the 55-case Android acceptance matrix. It does NOT
exercise Room, Navidrome, Media3, private libraries, Play Now, or a device.
The report clearly separates API_LIVE observations from those pending gates.

Usage: python3 scripts/catalog_178_musicbrainz_live_probe.py --output catalog-178-live.json
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import json
import os
from pathlib import Path
import statistics
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

MB_BASE = "https://musicbrainz.org/ws/2"
APP_AGENT = "BitChordFork-issue178-live-probe/1.0 (https://github.com/jssantogit/BitChord)"
ILLIT = "6b128dd7-e998-4edb-a4f5-400fef190445"
AESPA = "b51c672b-85e0-48fe-8648-470a2422229f"
GROUP = "529f87a1-1081-48a4-8ee1-3c08de229858"
WORLD = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
JAPAN = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
ORIGINAL = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
SPED = "3e619ba2-34f0-4649-83d8-05483e2fabe5"


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return round(ordered[0], 2)
    rank = (len(ordered) - 1) * percentile_value / 100.0
    floor = int(rank)
    fractional = rank - floor
    ceil = min(floor + 1, len(ordered) - 1)
    return round(ordered[floor] * (1 - fractional) + ordered[ceil] * fractional, 2)


class PublicMusicBrainz:
    def __init__(self, interval: float = 1.1, timeout: float = 22.0):
        if interval < 1.0:
            raise ValueError("A minimum 1 second request spacing is mandatory")
        self.interval = interval
        self.timeout = timeout
        self.last_started: float | None = None
        self.latencies_ms: list[float] = []
        self.requests: list[dict] = []

    def get(self, kind: str, ident: str, **params: str) -> dict:
        params = {"fmt": "json", **params}
        url = f"{MB_BASE}/{kind}/{ident}?{urlencode(params)}" if kind in ("artist", "release", "recording", "release-group") else f"{MB_BASE}/{kind}?{urlencode(params)}"
        for attempt in range(3):
            now = time.monotonic()
            if self.last_started is not None:
                time.sleep(max(0.0, self.interval - (now - self.last_started)))
            self.last_started = time.monotonic()
            t0 = self.last_started
            request = Request(url, headers={"User-Agent": APP_AGENT, "Accept": "application/json"})
            try:
                with urlopen(request, timeout=self.timeout) as response:
                    body = response.read(5_000_000)
                    data = json.loads(body)
                    ms = round((time.monotonic() - t0) * 1000, 2)
                    self.latencies_ms.append(ms)
                    self.requests.append({"endpoint": kind, "http_status": response.status, "latency_ms": ms})
                    return data
            except HTTPError as exc:
                ms = round((time.monotonic() - t0) * 1000, 2)
                self.latencies_ms.append(ms)
                self.requests.append({"endpoint": kind, "http_status": exc.code, "latency_ms": ms})
                if exc.code not in (429, 503) or attempt == 2:
                    raise
                wait_seconds = 1.5 * (attempt + 1)
                retry_after = exc.headers.get("Retry-After")
                if retry_after:
                    if retry_after.isdigit():
                        wait_seconds = max(wait_seconds, min(float(retry_after), 30.0))
                    else:
                        try:
                            dt = parsedate_to_datetime(retry_after)
                            wait_seconds = max(wait_seconds, min(
                                max(0.0, (dt - datetime.now(timezone.utc)).total_seconds()), 30.0,
                            ))
                        except (TypeError, ValueError, OverflowError):
                            pass
                time.sleep(wait_seconds)
        raise RuntimeError("Retry loop exhausted")


def collect_checks(client: PublicMusicBrainz) -> list[dict]:
    checks: list[dict] = []

    def check(case_id: str, label: str, operation):
        try:
            value = operation()
            passed, evidence = value
            checks.append({"case": case_id, "label": label, "evidence_type": "API_LIVE",
                           "status": "PASS" if passed else "FAIL", "evidence": evidence})
        except (HTTPError, URLError, ValueError, KeyError, RuntimeError, TimeoutError) as exc:
            checks.append({"case": case_id, "label": label, "evidence_type": "API_LIVE",
                           "status": "ERROR", "error": f"{type(exc).__name__}: {str(exc)[:180]}"})

    def artist_case(case_id, name, mbid):
        data = client.get("artist", mbid)
        return data.get("id") == mbid and name.lower() in data.get("name", "").lower(), {
            "mbid": data.get("id"), "name": data.get("name"), "disambiguation": data.get("disambiguation"),
        }

    check("02", "aespa artist MBID, without name-based identity promotion",
          lambda: artist_case("02", "aespa", AESPA))
    check("01", "ILLIT artist MBID", lambda: artist_case("01", "ILLIT", ILLIT))

    def recording_case(mbid):
        data = client.get("recording", mbid, inc="artist-credits")
        return data.get("id") == mbid and "magnetic" in data.get("title", "").lower(), {
            "mbid": data.get("id"), "title": data.get("title"), "length_ms": data.get("length"),
        }

    check("11", "Magnetic original recording MBID", lambda: recording_case(ORIGINAL))
    check("12", "Magnetic sped-up recording is distinct", lambda: recording_case(SPED))

    def group_case():
        data = client.get("release-group", GROUP)
        return data.get("id") == GROUP, {
            "mbid": data.get("id"), "title": data.get("title"),
            "primary_type": data.get("primary-type"),
        }

    check("21", "ReleaseGroup immutable identity", group_case)

    def release_case(mbid, label):
        data = client.get("release", mbid, inc="recordings+artist-credits+release-groups")
        release_group = data.get("release-group") or {}
        tracks = [track for medium in data.get("media", []) for track in medium.get("tracks", [])]
        typed = all(t.get("id") and (t.get("recording") or {}).get("id") for t in tracks)
        return data.get("id") == mbid and release_group.get("id") == GROUP and typed and len(tracks) > 0, {
            "mbid": data.get("id"), "title": data.get("title"), "country": data.get("country"),
            "release_group_mbid": release_group.get("id"), "track_count": len(tracks),
            "sample_track_mbid": tracks[0].get("id") if tracks else None,
            "sample_recording_mbid": (tracks[0].get("recording") or {}).get("id") if tracks else None,
        }

    check("26", "Worldwide edition Track versus Recording identity",
          lambda: release_case(WORLD, "worldwide"))
    check("27", "Japanese edition independent release identity",
          lambda: release_case(JAPAN, "japan"))

    def artist_search():
        data = client.get("artist", "", query="ILLIT", limit="25", dismax="true")
        rows = data.get("artists", [])
        ids = [row.get("id") for row in rows]
        return ILLIT in ids, {"returned": len(rows), "found_illit_mbid": ILLIT in ids}

    # Search endpoints have no entity ID path: call /artist?query=...
    check("03", "Public artist search resolves ILLIT by MBID", artist_search)

    def aespa_search():
        data = client.get("artist", "", query="aespa", limit="25", dismax="true")
        ids = {row.get("id") for row in data.get("artists", [])}
        return AESPA in ids, {"returned": len(ids), "found_aespa_mbid": AESPA in ids}

    check("04", "Public artist search resolves aespa by MBID", aespa_search)

    def no_match_search():
        # A known-absent exact token should be an empty result, not HTTP failure
        # or an invented catalog hit.
        data = client.get("artist", "", query="zzzbitchordissue1782026unlikelymatchzzzz",
                          limit="25", dismax="true")
        rows = data.get("artists")
        return isinstance(rows, list) and len(rows) == 0, {
            "count": data.get("count"), "returned": len(rows) if isinstance(rows, list) else None,
        }

    check("05", "Public artist search with no matches", no_match_search)

    def artist_group_browse():
        data = client.get("release-group", "", artist=ILLIT, limit="100")
        groups = data.get("release-groups", [])
        found = GROUP in {group.get("id") for group in groups}
        return found, {"returned": len(groups), "found_expected_release_group": found}

    check("22", "Artist-to-ReleaseGroup browse uses stable identity", artist_group_browse)

    def group_browse():
        data = client.get("release", "", **{"release-group": GROUP, "limit": "100"})
        rows = data.get("releases", [])
        mbids = {row.get("id") for row in rows}
        return WORLD in mbids and JAPAN in mbids, {
            "returned": len(rows), "worldwide_found": WORLD in mbids,
            "japan_found": JAPAN in mbids,
        }

    check("26-27-browse", "ReleaseGroup lists worldwide and Japan editions", group_browse)
    return checks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("catalog-178-live.json"))
    parser.add_argument("--interval", type=float, default=1.1)
    args = parser.parse_args()
    client = PublicMusicBrainz(interval=args.interval)
    started_at = datetime.now(timezone.utc).isoformat()
    checks = collect_checks(client)
    stats = {
        "started_at_utc": started_at,
        "completed_at_utc": datetime.now(timezone.utc).isoformat(),
        "head_sha": os.environ.get("CATALOG_HEAD_SHA") or None,
        "github_run_id": os.environ.get("GITHUB_RUN_ID") or None,
        "scope": "PUBLIC_MUSICBRAINZ_API_ONLY",
        "not_executed": "Room/Media3/Android UI/Navidrome/physical device and remaining issue #178 matrix cases",
        "checks": checks,
        # This is subset *public API evidence*, not an Android acceptance PASS.
        # Auxiliary checks retain descriptive IDs and are not counted as
        # standalone matrix cases.
        "matrix_case_ids_with_api_evidence": sorted({
            check["case"] for check in checks
            if len(check["case"]) == 2 and check["case"].isdigit()
            and check["status"] == "PASS"
        }),
        "http_requests": client.requests,
        "http_request_count": len(client.requests),
        "latency_p50_ms": percentile(client.latencies_ms, 50),
        "latency_p95_ms": percentile(client.latencies_ms, 95),
        "pass_count": sum(x["status"] == "PASS" for x in checks),
        "fail_count": sum(x["status"] == "FAIL" for x in checks),
        "error_count": sum(x["status"] == "ERROR" for x in checks),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: stats[k] for k in (
        "scope", "pass_count", "fail_count", "error_count", "http_request_count",
        "latency_p50_ms", "latency_p95_ms",
    )}, indent=2))
    return 0 if stats["fail_count"] == 0 and stats["error_count"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

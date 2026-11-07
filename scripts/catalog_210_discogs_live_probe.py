#!/usr/bin/env python3
"""One anonymous, read-only Discogs API probe. No credentials or user data.

This measures API reachability *from GitHub Actions*, not from an Android phone.
It must not be treated as a physical device smoke or evidence of commercial
distribution permissions. Only status and booleans are reported, never a
response body, tokens, user identifiers or marketplace information.
"""
from __future__ import annotations
import datetime as dt
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

RELEASE_ID = 249504  # documented Discogs public API example
ENDPOINT = f"https://api.discogs.com/releases/{RELEASE_ID}"
UA = "BitChordPersonal/1.0 (+https://github.com/jssantogit/BitChord)"
MAX_BYTES = 131_072

def probe() -> dict:
    started = time.monotonic()
    result = {
        "observed_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "target": "public_discogs_release",
        "http_status": None,
        "result": "unavailable",
        "valid_id": False,
        "title_present": False,
        "tracklist_present": False,
    }
    request = urllib.request.Request(ENDPOINT, headers={
        "User-Agent": UA,
        "Accept": "application/vnd.discogs.v2.discogs+json",
    })
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            result["http_status"] = response.status
            body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            result["result"] = "oversized"
        else:
            data = json.loads(body.decode("utf-8"))
            if isinstance(data, dict):
                result["valid_id"] = data.get("id") == RELEASE_ID
                result["title_present"] = isinstance(data.get("title"), str) and bool(data["title"])
                result["tracklist_present"] = isinstance(data.get("tracklist"), list)
            result["result"] = (
                "ok" if result["valid_id"] and result["title_present"] and result["tracklist_present"]
                else "invalid_payload"
            )
    except urllib.error.HTTPError as exc:
        result["http_status"] = exc.code
        result["result"] = {
            401: "unauthorized", 403: "forbidden",
            404: "not_found", 429: "throttled",
        }.get(exc.code, "http_error")
    except (urllib.error.URLError, TimeoutError, OSError):
        result["result"] = "network_unavailable"
    except (ValueError, UnicodeError):
        result["result"] = "invalid_payload"
    result["duration_ms"] = round((time.monotonic() - started) * 1000)
    return result

def main(argv: list[str]) -> int:
    output = pathlib.Path(argv[1] if len(argv) > 1 else "discogs-api-evidence.json")
    result = probe()
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0 if result["result"] == "ok" else 1

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

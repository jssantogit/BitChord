#!/usr/bin/env python3
"""Offline Discogs coverage report. Never invent measurements from missing cases."""
from __future__ import annotations
import argparse
import json
import re
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit

COHORTS = ("type", "unlinked", "source_only")
KINDS = {"Album", "EP", "Single", "Unknown"}
MATCH = {"verified", "ambiguous", "candidate", "no_link", "unavailable", "invalid"}
SOURCES = {"artist", "label", "release_notes", "discogs", "musicbrainz"}
MBID = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
WORKFLOW_URL = re.compile(r"https://github\.com/jssantogit/BitChord/actions/runs/[1-9][0-9]*")


def _public_https(url: object) -> str | None:
    if not isinstance(url, str) or len(url) not in range(9, 2049):
        return None
    try:
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or not parsed.hostname or
            parsed.username or parsed.password or parsed.port is not None or
            parsed.query or parsed.fragment or not parsed.path.startswith("/")):
            return None
        return parsed.hostname.lower()
    except ValueError:
        return None


def reviewed_evidence(case: dict) -> bool:
    """Evidence locator validity, not automatic confirmation of its factual claim.

    Reviewers must check source contents and the native app themselves. A
    human-readable source category or UUID alone never certifies a case.
    """
    cohort = case["cohort"]
    release_id = case.get("discogs_release_id")
    if type(release_id) is not int or not 1 <= release_id <= 999_999_999_999:
        return False
    links = case.get("evidence_links")
    if not isinstance(links, dict):
        return False
    if links.get("discogs_release") != f"https://www.discogs.com/release/{release_id}":
        return False
    editorial_host = _public_https(links.get("independent_editorial"))
    if (editorial_host is None or editorial_host in {
        "discogs.com", "www.discogs.com", "api.discogs.com",
        "musicbrainz.org", "www.musicbrainz.org", "github.com",
        "api.github.com",
    } or editorial_host.endswith(".discogs.com") or
        editorial_host.endswith(".musicbrainz.org")):
        return False
    if cohort == "type":
        gid = case.get("musicbrainz_group_mbid")
        expected = f"https://musicbrainz.org/release-group/{gid}"
        if not isinstance(gid, str) or not MBID.fullmatch(gid) or (
            links.get("musicbrainz_release_group") != expected
        ):
            return False
    elif cohort == "unlinked":
        edition = case.get("musicbrainz_release_mbid")
        if (not isinstance(edition, str) or not MBID.fullmatch(edition) or
            links.get("musicbrainz_release") !=
                f"https://musicbrainz.org/release/{edition}"):
            return False
    elif cohort == "source_only":
        artist = case.get("musicbrainz_artist_mbid")
        if (not isinstance(artist, str) or not MBID.fullmatch(artist) or
            case.get("artist_browse_complete") is not True or
            links.get("musicbrainz_artist") !=
                f"https://musicbrainz.org/artist/{artist}" or
            not WORKFLOW_URL.fullmatch(str(links.get("observed_workflow", "")))):
            return False
    else:
        return False
    notes, reviewed_at = case.get("review_notes"), case.get("reviewed_at_utc")
    if not isinstance(notes, str) or not 40 <= len(notes.strip()) <= 2000:
        return False
    if not isinstance(reviewed_at, str) or (
        re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", reviewed_at) is None
    ):
        return False
    try:
        from datetime import datetime
        if datetime.strptime(reviewed_at, "%Y-%m-%dT%H:%M:%SZ").strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ) != reviewed_at:
            return False
    except ValueError:
        return False
    return True

def selected_identity(case: dict) -> str | None:
    """Validate a seeded, non-adjudicated case and return a duplicate key.

    No candidate becomes an editorial judgment just by having public IDs.
    The URL strings must anchor exactly the typed IDs supplied in the row.
    """
    stage = case.get("selection_state")
    cohort = case["cohort"]
    if (cohort == "unlinked" and stage != "identity_observed" or
        cohort == "source_only" and stage != "excluded_by_provider_metadata"):
        return None
    release_id = case.get("discogs_release_id")
    if type(release_id) is not int or not 1 <= release_id <= 999_999_999_999:
        return None
    label, note = case.get("label"), case.get("selection_note")
    if not isinstance(label, str) or not 1 <= len(label.strip()) <= 160:
        return None
    if not isinstance(note, str) or not 1 <= len(note.strip()) <= 240:
        return None
    links = case.get("evidence_links")
    if not isinstance(links, dict) or not isinstance(links.get("observed_workflow"), str):
        return None
    run_url = links["observed_workflow"]
    if not WORKFLOW_URL.fullmatch(run_url):
        return None
    if links.get("discogs_release") != f"https://www.discogs.com/release/{release_id}":
        return None
    if cohort == "unlinked":
        rg, edition = case.get("musicbrainz_group_mbid"), case.get("musicbrainz_release_mbid")
        if (not isinstance(rg, str) or not MBID.fullmatch(rg) or
            not isinstance(edition, str) or not MBID.fullmatch(edition)):
            return None
        if (links.get("musicbrainz_release") !=
            f"https://musicbrainz.org/release/{edition}" or
            case.get("direct_release_relation") != "no_direct_release_link"):
            return None
    return str(release_id)


def report(document: dict) -> dict:
    if document.get("schema_version") != 1 or not isinstance(document.get("cases"), list):
        raise ValueError("Expected schema_version=1 and cases array")
    cases = document["cases"]
    seen = set()
    source_identities = set()
    counts = Counter()
    reviewed = Counter()
    selected = Counter()
    excluded_triage = Counter()
    selected_identities = set()
    errors = []
    for i, case in enumerate(cases):
        if not isinstance(case, dict):
            errors.append(f"case {i}: invalid object")
            continue
        cid, cohort = case.get("id"), case.get("cohort")
        if not isinstance(cid, str) or not cid or cid in seen:
            errors.append(f"case {i}: missing or duplicate id")
        else:
            seen.add(cid)
        if cohort not in COHORTS:
            errors.append(f"case {cid}: unknown cohort")
            continue
        counts[cohort] += 1
        if case.get("reviewed") is not True:
            # A selected case is NOT reviewed. Guard it against fabricated
            # identities, wrong-source URLs, and duplicate release entries.
            if "selection_state" in case:
                identity = selected_identity(case)
                if identity is None:
                    errors.append(f"case {cid}: invalid unreviewed selection evidence")
                elif identity in selected_identities:
                    errors.append(f"case {cid}: duplicate unreviewed source edition")
                else:
                    selected_identities.add(identity)
                    selected[cohort] += 1
                    if case["selection_state"] == "excluded_by_provider_metadata":
                        excluded_triage[cohort] += 1
            continue
        if not isinstance(case.get("sources"), list) or not case["sources"] or not all(
            isinstance(src, str) and src in SOURCES for src in case["sources"]
        ) or not any(src in {"artist", "label", "release_notes"} for src in case["sources"]):
            errors.append(f"case {cid}: independent editorial adjudication is required")
            continue
        if not reviewed_evidence(case):
            errors.append(f"case {cid}: missing public, independent, dated review evidence")
            continue
        # A reviewed result must be reproducible from public release identities;
        # a confident boolean without the underlying source is not evidence.
        mbid = case.get("musicbrainz_group_mbid")
        if cohort != "source_only" and (
            not isinstance(mbid, str) or not re.fullmatch(
                r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", mbid
            )
        ):
            errors.append(f"case {cid}: missing exact public MusicBrainz group MBID")
            continue
        release_id = case.get("discogs_release_id")
        if (case.get("match_state") == "verified" or cohort == "source_only") and (
            type(release_id) is not int or not 1 <= release_id <= 999_999_999_999
        ):
            errors.append(f"case {cid}: missing exact Discogs release ID")
            continue
        if any(case.get(k) not in KINDS for k in
               ("reference_type", "musicbrainz_type", "app_type")):
            errors.append(f"case {cid}: release types must be explicit")
            continue
        if case.get("match_state") not in MATCH:
            errors.append(f"case {cid}: unknown match_state")
            continue
        for field in ("match_correct", "visible", "absent_in_musicbrainz"):
            if type(case.get(field)) is not bool:
                errors.append(f"case {cid}: {field} must be an explicit boolean")
                break
        else:
            # A type correction must be independently evaluated per ReleaseGroup,
            # while unlinked matching is edition-scoped. Duplicate cases must
            # never inflate coverage merely by changing the case label.
            identity = (
                (cohort, mbid.lower()) if cohort == "type" else
                (cohort, mbid.lower(), release_id) if cohort == "unlinked" else
                (cohort, release_id)
            )
            if identity in source_identities:
                errors.append(f"case {cid}: duplicate source identity")
            else:
                source_identities.add(identity)
                reviewed[cohort] += 1
    if len(cases) != 30:
        errors.append(f"expected 30 slots, found {len(cases)}")
    for cohort in COHORTS:
        if counts[cohort] != 10:
            errors.append(f"expected 10 cases in {cohort}, found {counts[cohort]}")
    complete = not errors and all(reviewed[x] == 10 for x in COHORTS)
    output = {
        "schema_version": 1, "complete": complete,
        "slots": {x: {"provided": counts[x], "selected_unreviewed": selected[x],
                  "excluded_preliminary": excluded_triage[x],
                  "reviewed": reviewed[x], "required": 10}
                  for x in COHORTS},
        "errors": errors, "metrics": None,
    }
    if not complete:
        return output
    ty = [x for x in cases if x["cohort"] == "type"]
    ul = [x for x in cases if x["cohort"] == "unlinked"]
    so = [x for x in cases if x["cohort"] == "source_only"]
    incorrect = [x for x in ty if x["musicbrainz_type"] in {"Album", "EP", "Single"}
                 and x["musicbrainz_type"] != x["reference_type"]]
    unknown = [x for x in ty if x["musicbrainz_type"] == "Unknown"
               and x["reference_type"] != "Unknown"]
    verified = [x for x in cases if x["match_state"] == "verified"]
    confirmed = [x for x in so if x["absent_in_musicbrainz"]]
    output["metrics"] = {
        "type_correction": {
            "corrected": sum(x["app_type"] == x["reference_type"] for x in incorrect),
            "eligible": len(incorrect),
        },
        "type_fill": {
            "filled": sum(x["app_type"] == x["reference_type"] for x in unknown),
            "eligible": len(unknown),
        },
        "unlinked_matching": {
            "verified": sum(x["match_state"] == "verified" for x in ul),
            "eligible": len(ul),
        },
        "verified_precision": {
            "correct": sum(x["match_correct"] for x in verified),
            "verified": len(verified),
        },
        "source_only_recovery": {
            "visible": sum(x["visible"] for x in confirmed),
            "confirmed_absent": len(confirmed),
        },
        "false_positive_count": sum(not x["match_correct"] for x in verified),
    }
    return output

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = report(json.loads(args.manifest.read_text(encoding="utf-8")))
    encoded = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")
    return 0 if result["complete"] and result["metrics"]["false_positive_count"] == 0 else 2

if __name__ == "__main__":
    raise SystemExit(main())

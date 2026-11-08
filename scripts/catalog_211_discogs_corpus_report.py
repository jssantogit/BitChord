#!/usr/bin/env python3
"""Offline Discogs coverage report. Never invent measurements from missing cases."""
from __future__ import annotations
import argparse
import json
import re
from collections import Counter
from pathlib import Path

COHORTS = ("type", "unlinked", "source_only")
KINDS = {"Album", "EP", "Single", "Unknown"}
MATCH = {"verified", "ambiguous", "candidate", "no_link", "unavailable", "invalid"}
SOURCES = {"artist", "label", "release_notes", "discogs", "musicbrainz"}

def report(document: dict) -> dict:
    if document.get("schema_version") != 1 or not isinstance(document.get("cases"), list):
        raise ValueError("Expected schema_version=1 and cases array")
    cases = document["cases"]
    seen = set()
    source_identities = set()
    counts = Counter()
    reviewed = Counter()
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
            continue
        if not isinstance(case.get("sources"), list) or not case["sources"] or not all(
            isinstance(src, str) and src in SOURCES for src in case["sources"]
        ) or not any(src in {"artist", "label", "release_notes"} for src in case["sources"]):
            errors.append(f"case {cid}: independent editorial adjudication is required")
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
        "slots": {x: {"provided": counts[x], "reviewed": reviewed[x], "required": 10}
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

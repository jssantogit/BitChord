#!/usr/bin/env python3
"""B cohort: bounded real alternatives from the SAME MB ReleaseGroup.

Discovery evidence, not a Verified match or proof of a missing release.
The IDs come from an unreviewed corpus row joined to an existing pair control.
"""
from __future__ import annotations
import datetime as dt
import json
from pathlib import Path
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode

from catalog_211_discogs_corpus_candidates import MBID
from catalog_211_discogs_edition_pair_review import valid_id, release_relations
from catalog_211_discogs_public_link_probe import compare_edition, read_json

MANIFEST = Path("docs/plans/catalog-211-discogs-corpus-manifest.json")
PAIRS = Path("docs/plans/catalog-211-discogs-edition-pairs.json")
OUTPUT = Path("discogs-211-unlinked-alternative-editions.json")
MAX_CASES, MAX_RELEASES, MAX_DETAILS, MAX_REQUESTS = 5, 25, 2, 20

def barcode(value: object) -> str | None:
    """UPC/EAN/GTIN exact check digit; leading zeros are significant."""
    if not isinstance(value, str) or len(value) > 80:
        return None
    digits = value.replace(" ", "").replace("-", "")
    if len(digits) not in (8, 12, 13, 14) or not digits.isascii() or not digits.isdigit():
        return None
    weighted = sum(int(d) * (3 if i % 2 == 0 else 1)
                   for i, d in enumerate(reversed(digits[:-1])))
    return digits if (10 - weighted % 10) % 10 == int(digits[-1]) else None

def discogs_barcodes(record: dict) -> set[str]:
    identifiers = record.get("identifiers")
    if not isinstance(identifiers, list) or len(identifiers) > 128:
        return set()
    return {b for item in identifiers if isinstance(item, dict)
            and item.get("type") == "Barcode"
            for b in [barcode(item.get("value"))] if b is not None}

def _metadata_field(value: object, *, catalog: bool = False) -> str | None:
    """Normalize typography only: never guess label aliases or catalog variants."""
    if not isinstance(value, str) or not 1 <= len(value) <= 160:
        return None
    text = " ".join(unicodedata.normalize("NFKC", value).casefold().split())
    if not text or text in {
        "none", "n/a", "na", "unknown", "?", "-", "not on label",
        "no label", "tba", "no catalog number", "[none]",
    } or text.startswith("not on label ("):
        return None
    if catalog and len(text) > 100:
        return None
    return text


def _label_catalog_pairs(record: dict, *, musicbrainz: bool) -> list[tuple[str, str]]:
    entries = record.get("label-info") if musicbrainz else record.get("labels")
    # Malformed, missing or oversized arrays provide no pair evidence.
    if not isinstance(entries, list) or len(entries) > 64:
        return []
    output: set[tuple[str, str]] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        label = entry.get("label") if musicbrainz else entry
        if not isinstance(label, dict):
            continue
        name = _metadata_field(label.get("name"))
        number = _metadata_field(
            entry.get("catalog-number" if musicbrainz else "catno"),
            catalog=True,
        )
        if name and number:
            output.add((name, number))
    return sorted(output)[:16]


def label_catalog_evidence(mb: dict, discogs: dict) -> dict:
    """Same *pair* is a source observation, never proof of a physical edition."""
    mb_pairs = _label_catalog_pairs(mb, musicbrainz=True)
    discogs_pairs = _label_catalog_pairs(discogs, musicbrainz=False)
    shared = sorted(set(mb_pairs) & set(discogs_pairs))
    common_numbers = sorted(
        {number for _, number in mb_pairs} &
        {number for _, number in discogs_pairs}
    )
    # Two exact same-label assertions with disjoint catalog numbers are
    # variant evidence, even when UPC and track count match. Preserve both
    # sources as a reviewable contradiction, never infer a catalog alias.
    mb_by_label: dict[str, set[str]] = {}
    dc_by_label: dict[str, set[str]] = {}
    for label, number in mb_pairs:
        mb_by_label.setdefault(label, set()).add(number)
    for label, number in discogs_pairs:
        dc_by_label.setdefault(label, set()).add(number)
    conflicting_labels = sorted(
        label for label in mb_by_label.keys() & dc_by_label.keys()
        if not (mb_by_label[label] & dc_by_label[label])
    )
    assessment = (
        "same_label_different_catalog_numbers" if conflicting_labels else
        "exact_label_catalog_pair_observed_not_verified" if shared else
        "catalog_only_unconfirmed" if common_numbers else
        "insufficient_label_catalog_data"
    )
    def details(pairs):
        return [{"label": label, "catalog_number": number} for label, number in pairs]
    return {
        "assessment": assessment,
        "verified": False,
        "musicbrainz_pairs": details(mb_pairs),
        "discogs_pairs": details(discogs_pairs),
        "matching_pairs": details(shared),
        "shared_catalog_numbers": common_numbers,
        "conflicting_same_label_catalog_numbers": [
            {"label": label, "musicbrainz": sorted(mb_by_label[label]),
             "discogs": sorted(dc_by_label[label])}
            for label in conflicting_labels
        ],
    }


def selected(manifest: dict, controls: dict) -> list[dict]:
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1 or (
        not isinstance(controls, dict) or controls.get("schema_version") != 1
    ):
        raise ValueError("invalid_schema")
    if not isinstance(manifest.get("cases"), list) or not isinstance(controls.get("pairs"), list):
        raise ValueError("missing_cases_or_controls")
    pairs = {}
    for row in controls["pairs"]:
        if not isinstance(row, dict) or row.get("reviewed") is not False:
            raise ValueError("invalid_control")
        key = (row.get("musicbrainz_release_mbid"), row.get("discogs_release_id"))
        if key in pairs:
            raise ValueError("duplicate_control")
        pairs[key] = row
    result, seen = [], set()
    for item in manifest["cases"]:
        if not isinstance(item, dict) or item.get("cohort") != "unlinked" or (
            item.get("selection_state") != "identity_observed"
        ):
            continue
        mbid, group, did = (item.get("musicbrainz_release_mbid"),
                            item.get("musicbrainz_group_mbid"),
                            item.get("discogs_release_id"))
        control = pairs.get((mbid, did))
        links = item.get("evidence_links")
        if (item.get("reviewed") is not False or
            item.get("direct_release_relation") != "no_direct_release_link" or
            not isinstance(mbid, str) or not MBID.fullmatch(mbid) or
            not isinstance(group, str) or not MBID.fullmatch(group) or
            not valid_id(did) or not isinstance(links, dict) or
            links.get("musicbrainz_release") != f"https://musicbrainz.org/release/{mbid}" or
            links.get("discogs_release") != f"https://www.discogs.com/release/{did}" or
            control is None or not isinstance(control.get("musicbrainz_artist_mbid"), str) or
            not MBID.fullmatch(control["musicbrainz_artist_mbid"]) or
            not valid_id(control.get("discogs_artist_id"))):
            raise ValueError("invalid_selected_identity")
        key = (group.lower(), did)
        if key in seen:
            raise ValueError("duplicate_selected_identity")
        seen.add(key)
        result.append({
            "case_id": item["id"], "musicbrainz_group_mbid": group,
            "reference_musicbrainz_release_mbid": mbid,
            "musicbrainz_artist_mbid": control["musicbrainz_artist_mbid"],
            "discogs_artist_id": control["discogs_artist_id"],
            "discogs_release_id": did,
        })
    if len(result) > MAX_CASES:
        raise ValueError("exceeded_case_budget")
    return result

def group_page(payload: dict, group: str) -> tuple[list[dict], str]:
    count, offset, rows = (
        payload.get("release-count"), payload.get("release-offset"), payload.get("releases")
    )
    if (type(count) is not int or type(offset) is not int or offset != 0 or count < 1 or
        not isinstance(rows, list) or len(rows) > MAX_RELEASES or
        not rows or len(rows) > count):
        return [], "invalid_group_browse"
    if count > MAX_RELEASES or len(rows) != count:
        return [], "group_browse_incomplete"
    seen = set()
    for row in rows:
        if (not isinstance(row, dict) or not isinstance(row.get("id"), str) or
            not MBID.fullmatch(row["id"]) or row["id"] in seen):
            return [], "invalid_group_browse"
        seen.add(row["id"])
        rg = row.get("release-group")
        if rg is not None and (not isinstance(rg, dict) or rg.get("id") != group):
            return [], "release_group_identity_conflict"
    return rows, "complete_group_browse"

def same_year_alternatives(
    rows: list[dict], year: object, reference_mbid: str,
    discogs: dict | None = None,
) -> list[str]:
    """Prioritize exact label+catalog pairs; same year stays *content only*."""
    if type(year) is not int or not 1800 <= year <= 2200:
        return []
    candidates = [
        row for row in rows
        if row.get("status") == "Official" and
        isinstance(row.get("date"), str) and
        row["date"][:4] == str(year)
    ]
    # No name guessing: the complete provider pair outranks chronological
    # ambiguity; an isolated catalog number never receives this priority.
    def rank(row: dict) -> tuple[bool, bool, str]:
        exact_pair = (
            bool(label_catalog_evidence(row, discogs)["matching_pairs"])
            if isinstance(discogs, dict) else False
        )
        return (not exact_pair, row["id"] == reference_mbid, row["id"])
    return [row["id"] for row in sorted(candidates, key=rank)[:MAX_DETAILS]]


def investigate(case: dict, get) -> dict:
    result = {**case, "status": "source_unavailable", "group_browse_count": None,
              "discogs_has_valid_barcode": False, "matching_barcode_release_ids": [],
              "candidates": [], "reviewed": False}
    try:
        did, group = case["discogs_release_id"], case["musicbrainz_group_mbid"]
        discogs = get(f"https://api.discogs.com/releases/{did}")
        artists = discogs.get("artists")
        if (type(discogs.get("id")) is not int or discogs["id"] != did or
            not isinstance(artists, list) or
            [x.get("id") for x in artists if isinstance(x, dict)] !=
            [case["discogs_artist_id"]]):
            result["status"] = "discogs_artist_or_release_identity_conflict"
            return result
        barcodes = discogs_barcodes(discogs)
        result["discogs_has_valid_barcode"] = bool(barcodes)
        # Missing UPC does not preclude a same-group, same-year content
        # comparison. It can never validate the same edition or pressing.
        url = "https://musicbrainz.org/ws/2/release/?" + urlencode({
            "release-group": group, "fmt": "json", "inc": "labels",
            "limit": MAX_RELEASES, "offset": 0,
        })
        page = get(url)
        rows, status = group_page(page, group)
        result["status"] = status
        if not rows:
            return result
        result["group_browse_count"] = len(rows)
        matches = [r["id"] for r in rows if barcode(r.get("barcode")) in barcodes]
        result["matching_barcode_release_ids"] = matches
        result["group_rows_without_barcode"] = sum(r.get("barcode") is None for r in rows)
        if matches:
            candidates = matches[:MAX_DETAILS]
            result["selection_basis"] = "exact_valid_barcode_in_same_release_group"
        else:
            # Same release year and Official status are only weak evidence.
            # This fallback is NOT eligible for Verified, even if tracks match.
            candidates = same_year_alternatives(
                rows, discogs.get("year"), case["reference_musicbrainz_release_mbid"],
                discogs,
            )
            result["selection_basis"] = "same_year_official_content_only"
        if not candidates:
            result["status"] = "no_same_year_official_candidate_in_complete_group_index"
            return result
        for mbid in candidates:
            # A failed second Release must not erase a successfully observed
            # first alternative; report every candidate's availability.
            try:
                full = get(f"https://musicbrainz.org/ws/2/release/{mbid}"
                           "?fmt=json&inc=recordings+artist-credits+release-groups+url-rels+labels")
            except (HTTPError, URLError, OSError, TimeoutError):
                result["candidates"].append({"mbid": mbid, "status": "source_unavailable"})
                continue
            except (ValueError, TypeError, KeyError, AttributeError):
                result["candidates"].append({"mbid": mbid, "status": "invalid_provider_response"})
                continue
            parent = full.get("release-group")
            if (full.get("id") != mbid or not isinstance(parent, dict) or
                parent.get("id") != group):
                result["candidates"].append({"mbid": mbid, "status": "wrong_release_group"})
                continue
            credits = full.get("artist-credit")
            ids = [a.get("artist", {}).get("id") for a in credits if isinstance(a, dict)] \
                if isinstance(credits, list) else []
            if ids != [case["musicbrainz_artist_mbid"]]:
                result["candidates"].append({"mbid": mbid, "status": "wrong_artist_credit"})
                continue
            relations = release_relations(full)
            result["candidates"].append({
                "mbid": mbid, "status": "edition_facts_compared_not_adjudicated",
                "selection_basis": result["selection_basis"],
                "direct_discogs_relation": (
                    "unknown" if relations is None else
                    "this_release" if did in relations else
                    "another_release" if relations else "none"),
                "comparison": compare_edition(full, discogs),
                "label_catalog": label_catalog_evidence(full, discogs),
            })
        compared = sum(x["status"] == "edition_facts_compared_not_adjudicated"
                       for x in result["candidates"])
        unavailable = sum(x["status"] in ("source_unavailable", "invalid_provider_response")
                          for x in result["candidates"])
        result["status"] = (
            "partial_candidate_details_not_verified" if unavailable and compared else
            "all_candidate_details_unavailable" if unavailable and not compared else
            "barcode_candidates_inspected_not_verified" if matches else
            "year_only_content_candidates_not_verified"
        )
    except (HTTPError, URLError, OSError, TimeoutError):
        result["status"] = "source_unavailable"
    except (ValueError, TypeError, KeyError, AttributeError):
        result["status"] = "invalid_provider_response"
    return result

def observe(manifest: dict, controls: dict, get, pause=lambda: None) -> dict:
    cases, calls = selected(manifest, controls), 0
    def limited(url):
        nonlocal calls
        if calls >= MAX_REQUESTS:
            raise ValueError("request_budget_exceeded")
        calls += 1
        pause()
        data = get(url)
        if not isinstance(data, dict):
            raise ValueError("invalid_provider_data")
        return data
    findings = [investigate(case, limited) for case in cases]
    return {"schema_version": 1, "observed_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "selected_b_cases": len(cases), "reviewed_corpus_cases": 0,
            "provider_requests": calls,
            "warning": "Barcode-narrowed same-ReleaseGroup candidates; no Verified claim or MB absence",
            "findings": findings}

def main():
    doc = json.loads(MANIFEST.read_text(encoding="utf-8"))
    pairs = json.loads(PAIRS.read_text(encoding="utf-8"))
    result = observe(doc, pairs, lambda url: read_json(url)[1],
                     pause=lambda: time.sleep(1.1))
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2)
                      + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()

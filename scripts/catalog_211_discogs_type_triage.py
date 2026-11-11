#!/usr/bin/env python3
"""A-cohort diagnostic on already observed exact-ID MusicBrainz/Discogs pairs.

Offline only. Agreement, missing provider type, or discrepancy are observations,
never an adjudicated corpus case or a correction of canonical MusicBrainz types.
"""
from __future__ import annotations

import json
from pathlib import Path

from catalog_211_discogs_corpus_candidates import MBID
from catalog_211_discogs_edition_pair_review import case_list

PAIRS = Path("docs/plans/catalog-211-discogs-edition-pairs.json")
OBSERVATIONS = Path("discogs-211-edition-pair-evidence.json")
OUTPUT = Path("discogs-211-type-disagreement-triage.json")
KNOWN_TYPES = {"Album", "EP", "Single"}
UNAVAILABLE = {"source_unavailable", "invalid_provider_response"}
# A type disagreement belongs to an exact MB Release/Discogs Release
# comparison, never just two similarly named releases in the same product
# family. This is a REVIEW LEAD gate, not a canonical classification rule.
EDITION_PROOFS = (
    "artist_matches", "title_matches", "tracklist_complete",
    "tracklist_matches", "year_matches", "country_compatible",
    "barcode_compatible",
)


def classify(row: dict) -> dict:
    comparison = row.get("comparison")
    provider_type = comparison.get("discogs_explicit_type") if isinstance(comparison, dict) else None
    mb_type = row.get("musicbrainz_group_primary_type")
    anchor = row.get("direct_release_relation")
    result = {
        "control_id": row["id"],
        "musicbrainz_group_mbid": row.get("musicbrainz_group_mbid"),
        "musicbrainz_release_mbid": row["musicbrainz_release_mbid"],
        "discogs_release_id": row["discogs_release_id"],
        "musicbrainz_primary_type": mb_type if mb_type in KNOWN_TYPES else None,
        "discogs_type": provider_type if provider_type in KNOWN_TYPES else None,
        "direct_release_relation": anchor,
        "reviewed": False,
        "verified": False,
        "type_correction_applied": False,
        "missing_editional_proofs": [],

    }
    if row.get("status") in UNAVAILABLE:
        state = "provider_unavailable"
    elif row.get("status") != "edition_facts_compared_not_reviewed" or (
        row.get("artist_identity_confirmed_by_source_ids") is not True
    ) or not isinstance(row.get("musicbrainz_group_mbid"), str) or (
        not MBID.fullmatch(row["musicbrainz_group_mbid"])
    ):
        state = "source_identity_unconfirmed"
    elif row.get("musicbrainz_release_status") != "Official":
        state = "musicbrainz_release_not_official"
    elif mb_type not in KNOWN_TYPES:
        state = "musicbrainz_type_unsupported"
    elif provider_type not in KNOWN_TYPES:
        state = "source_type_insufficient"
    elif anchor != "points_to_this_release":
        # A physical edition may belong to the group, but the unlinked case
        # has not proven that relation. No type correction from this candidate.
        state = "unlinked_type_context_only"
    elif mb_type == provider_type:
        state = "linked_type_agreement_not_adjudicated"
    else:
        # A typed link is an identity anchor, not a license to compare
        # conflicting pressings. Require independently corroborated source
        # observations before even calling a type discrepancy a review lead.
        missing = [name for name in EDITION_PROOFS
                   if comparison.get(name) is not True]
        result["missing_editional_proofs"] = missing
        state = ("linked_edition_evidence_inconclusive" if missing else
                 "linked_type_disagreement_needs_review")
        # This state never edits primary type and never approves a B edition.
    result["state"] = state
    return result

def triage(controls: dict, observations: dict) -> dict:
    pairs = case_list(controls)
    if not isinstance(observations, dict) or (
        observations.get("schema_version") != 1 or
        observations.get("reviewed_corpus_cases") != 0
    ):
        raise ValueError("invalid_or_reviewed_observation")
    rows = observations.get("cases")
    if not isinstance(rows, list) or len(rows) != len(pairs):
        raise ValueError("incomplete_type_controls")
    by_id = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str) or (
            row["id"] in by_id
        ):
            raise ValueError("duplicate_or_invalid_control")
        by_id[row["id"]] = row
    output = []
    for expected in pairs:
        actual = by_id.get(expected["id"])
        if actual is None or any(
            actual.get(k) != expected[k] for k in (
                "musicbrainz_release_mbid", "discogs_release_id",
                "musicbrainz_artist_mbid", "discogs_artist_id",
            )
        ):
            raise ValueError("wrong_source_identity")
        output.append(classify(actual))
    return {
        "schema_version": 1,
        "reviewed_corpus_cases": 0,
        "type_corrections": 0,
        "linked_type_disagreement_leads": sum(
            row["state"] == "linked_type_disagreement_needs_review" for row in output
        ),
        "warning": "Offline editorial leads only; no MusicBrainz or app classification changed",
        "cases": output,
    }

def main() -> None:
    control = json.loads(PAIRS.read_text(encoding="utf-8"))
    observed = json.loads(OBSERVATIONS.read_text(encoding="utf-8"))
    result = triage(control, observed)
    OUTPUT.write_text(json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True)
                      + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()

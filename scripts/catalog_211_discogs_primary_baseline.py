#!/usr/bin/env python3
"""Read-only #211 baseline for a hypothetical Discogs-first catalog.

This is an audit, not a provider switch or a go/no-go approval. A missing
source-code fingerprint means UNKNOWN, never "the migration is complete".
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from catalog_211_discogs_corpus_report import report

# Exact source contracts already implemented at this checkpoint. These are
# evidence of coupling, not automated verification of every call site.
DEPENDENCIES = {
    "artist_discovery_requires_musicbrainz_artist": (
        "app/src/main/java/com/music/bitchord/data/catalog/metadata/discogs/"
        "DiscogsPublicArtistDiscovery.kt",
        "musicBrainzArtistMbid: String",
    ),
    "artist_discovery_requires_completed_musicbrainz_groups": (
        "app/src/main/java/com/music/bitchord/data/catalog/metadata/discogs/"
        "DiscogsPublicArtistDiscovery.kt",
        "completedMusicBrainzTitles: Set<String>",
    ),
    "edition_enrichment_starts_with_musicbrainz_release": (
        "app/src/main/java/com/music/bitchord/data/catalog/metadata/discogs/"
        "DiscogsCanonicalEditionCoordinator.kt",
        "observeRelease(musicBrainzReleaseMbid: String)",
    ),
    "musicbrainz_supplies_track_recording_identities": (
        "app/src/main/java/com/music/bitchord/data/catalog/metadata/"
        "MusicBrainzCatalogGraph.kt",
        "val recordingMbid: String",
    ),
    "release_primary_type_rejects_discogs_override": (
        "app/src/main/java/com/music/bitchord/data/catalog/metadata/"
        "CanonicalProviderEnrichmentPolicy.kt",
        "CanonicalEnrichmentField.RELEASE_PRIMARY_TYPE -> false",
    ),
}


def baseline(manifest: dict, repository_root: Path) -> dict:
    """Observe current contracts without promoting a proposed migration."""
    try:
        corpus = report(manifest)
    except (ValueError, TypeError, KeyError, AttributeError):
        return {
            "schema_version": 1, "hypothesis": "discogs_editorial_first",
            "decision": "exploratory_not_approved", "status": "invalid_evidence",
            "corpus_complete": False, "corpus_reviewed": None,
            "source_dependencies": {}, "unresolved": ["invalid_corpus"],
            "automatic_switch_authorized": False,
        }
    dependencies = {}
    for key, (path, needle) in DEPENDENCIES.items():
        try:
            raw = (repository_root / path).read_text(encoding="utf-8")
            status = "dependency_observed" if needle in raw else "unknown_requires_code_review"
        except (OSError, UnicodeError):
            status = "unknown_requires_code_review"
        dependencies[key] = {"source": path, "state": status}
    reviewed = sum(s["reviewed"] for s in corpus["slots"].values())
    metrics = corpus["metrics"] if corpus["complete"] else None
    blockers = []
    if corpus["errors"]:
        blockers.append("invalid_corpus")
    if not corpus["complete"]:
        blockers.append("corpus_not_independently_adjudicated")
    if any(x["state"] == "dependency_observed" for x in dependencies.values()):
        blockers.append("musicbrainz_identity_and_discovery_coupling")
    if any(x["state"] == "unknown_requires_code_review" for x in dependencies.values()):
        blockers.append("source_contracts_require_manual_review")
    if metrics is None or metrics["false_positive_count"] != 0:
        blockers.append("precision_not_independently_proven")
    if metrics is None or metrics["source_only_recovery"]["visible"] == 0:
        blockers.append("no_demonstrated_source_only_recovery")
    # These are separate, unmeasured architecture proofs. A 30/30 manifest
    # can never, by itself, authorize an inversion or mutate any runtime state.
    blockers.extend([
        "standalone_discogs_artist_bootstrap_not_verified",
        "recording_and_local_user_identity_migration_not_verified",
        "provider_first_rights_cache_and_attribution_not_verified",
        "cross_context_ui_and_playback_parity_not_verified",
        "owner_has_not_approved_provider_inversion",
    ])
    return {
        "schema_version": 1,
        "hypothesis": "discogs_editorial_first",
        "decision": "exploratory_not_approved",
        "status": "research_only_not_ready",
        "corpus_complete": corpus["complete"],
        "corpus_reviewed": reviewed,
        "corpus_required": 30,
        "corpus_selected_unreviewed": {
            kind: row["selected_unreviewed"] for kind, row in corpus["slots"].items()
        },
        "corpus_metrics": metrics,
        "source_dependencies": dependencies,
        "unresolved": blockers,
        "automatic_switch_authorized": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest", type=Path,
        default=Path("docs/plans/catalog-211-discogs-corpus-manifest.json"),
    )
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        document = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        document = {}
    output = baseline(document, args.repository_root)
    payload = json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")
    # An honest "research only" report is an expected, successful audit.
    return 0 if output["status"] == "research_only_not_ready" and (
        output["automatic_switch_authorized"] is False and
        not output["source_dependencies"].get(
            "musicbrainz_supplies_track_recording_identities", {}
        ).get("state") == "unknown_requires_code_review"
    ) else 2


if __name__ == "__main__":
    raise SystemExit(main())

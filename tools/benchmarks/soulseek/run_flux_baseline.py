"""Run ACTUAL Noqlen Flux scorer against a gold-blind input JSONL.

Developer-only, no Soulseek/slskd network. Flux source must be checked out
separately at a pinned Git commit; not bundled into the Android application.
The public result contains offer IDs/order only, never private peer metadata.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from evaluate import BenchmarkInputError, case_fingerprint, _records

PINNED_FLUX_SHA = "3e6fa935fc139ed80e4e75ae3aa7c88ebd36747d"


def check_flux_ref(root: Path, expected: str = PINNED_FLUX_SHA) -> str:
    if not root.is_dir():
        raise BenchmarkInputError("flux_checkout_not_found")
    try:
        command = ["git", "-C", str(root), "rev-parse", "HEAD"]
        actual = subprocess.run(
            command, check=True, capture_output=True, text=True, timeout=10
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "-C", str(root), "status", "--porcelain"],
            check=True, capture_output=True, text=True, timeout=10,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise BenchmarkInputError("flux_revision_not_verifiable") from error
    if actual != expected:
        raise BenchmarkInputError("flux_revision_differs_from_pinned")
    if dirty:
        raise BenchmarkInputError("flux_checkout_dirty")
    return actual


def _load_blind(path: Path) -> list[dict]:
    cases = _records(path)
    ids: set[str] = set()
    for c in cases:
        if c.get("schema_version") != 1 or c.get("case_id") in ids:
            raise BenchmarkInputError("invalid_blind_cases")
        ids.add(c.get("case_id"))
        if not isinstance(c.get("observations"), list):
            raise BenchmarkInputError("missing_blind_observations")
        for offer in c["observations"]:
            if "truth" in offer or "truth_evidence" in offer:
                raise BenchmarkInputError("gold_labels_must_not_reach_ranker")
    return cases


def score_flux(cases: list[dict], root: Path) -> list[dict]:
    # Import only AFTER verifying commit and that the file is blinded.
    sys.path.insert(0, str(root / "src"))
    from noqlen_flux.search import CandidateFile, SearchCandidate, SearchKind, SearchQuery
    from noqlen_flux.services.scoring import CandidateScoringService
    from noqlen_flux.scoring import DEFAULT_SCORING_PROFILE

    if DEFAULT_SCORING_PROFILE.name != "default_v1":
        raise BenchmarkInputError("unexpected_flux_scoring_profile")
    service = CandidateScoringService()
    predictions = []
    for case in cases:
        intent = case["intent"]
        query = SearchQuery(
            kind=SearchKind.TRACK, artist=intent["artist"],
            title=intent["title"], album=intent.get("album"),
        )
        all_offers = []
        for offer in case["observations"]:
            file = CandidateFile(
                filename=offer["filename"],
                size_bytes=offer.get("size_bytes"),
                declared_bitrate=offer.get("bitrate_kbps"),
                extension=offer.get("format"),
                duration_seconds=offer.get("duration_seconds"),
                locked=bool(offer.get("locked", False)),
            )
            candidate = SearchCandidate(
                candidate_id=offer["offer_id"], provider="slskd",
                files=[file], directory=offer.get("directory"),
                artist=offer.get("claimed_artist"),
                title=offer.get("claimed_title"),
                album=offer.get("claimed_album"),
            )
            score = service.score_candidate(query, candidate, DEFAULT_SCORING_PROFILE)
            all_offers.append((offer["offer_id"],score.total))
        all_offers.sort(key=lambda item: (-item[1], item[0]))
        ranking = [offer_id for offer_id, _ in all_offers]
        # Flux scoring is a ranker, not a native automatic-download gate.
        # rank_first is explicitly a benchmark top-1 operationalization.
        predictions.append({
            "case_id": case["case_id"],
            "input_sha256": case_fingerprint(case),
            "observed_offer_ids": [o["offer_id"] for o in case["observations"]],
            "ranked_offer_ids": ranking,
            "recommended_offer_id": ranking[0] if ranking else None,
            "model_version": "flux_default_v1",
            "recommendation_policy": "rank_first",
        })
    return predictions


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run pinned Flux scorer offline.")
    parser.add_argument("--blind", required=True, type=Path)
    parser.add_argument("--flux-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        check_flux_ref(args.flux_root)
        cases = _load_blind(args.blind)
        predictions = score_flux(cases,args.flux_root)
        args.output.write_text(
            "".join(json.dumps(p,ensure_ascii=False,separators=(",",":"))+"\n"
                    for p in predictions), encoding="utf-8",
        )
        print(json.dumps({"status":"ranked_offline", "cases":len(predictions),
                          "comparator":"flux_default_v1"}))
        return 0
    except (BenchmarkInputError, OSError, KeyError, ValueError, ImportError) as error:
        kind = str(error) if isinstance(error, BenchmarkInputError) else "adapter_failed"
        print("flux_benchmark_error:" + kind,file=sys.stderr)
        return 2


if __name__=="__main__":
    raise SystemExit(main())

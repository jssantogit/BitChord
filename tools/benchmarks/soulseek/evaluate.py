"""Privacy-preserving, deterministic pre-download benchmark scoring.

Accepts the *same* labelled offer manifest and two independent prediction
files. This module does NOT implement either music ranker, access Soulseek,
download music, or claim that synthetic examples represent real accuracy.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any


class BenchmarkInputError(ValueError):
    """Invalid or incomparable inputs (never include raw filenames)."""


TRUTH_LABELS = frozenset({
    "EXACT_EDITION", "CORRECT_RECORDING_EDITION_UNKNOWN",
    "WRONG_EDITION_VERSION", "WRONG_ARTIST_OR_RECORDING",
    "TECHNICALLY_INELIGIBLE", "UNKNOWN_UNVERIFIABLE",
})
SETTLED = frozenset({
    "EXACT_EDITION", "WRONG_EDITION_VERSION",
    "WRONG_ARTIST_OR_RECORDING", "TECHNICALLY_INELIGIBLE",
})
STRATA = frozenset({
    "ordinary", "versions", "editions", "artists", "ambiguous",
    "quality", "empty",
})


def _fraction(numerator: int, denominator: int) -> dict[str, int]:
    return {"numerator": numerator, "denominator": denominator}


def blind_case(case: dict[str, Any]) -> dict[str, Any]:
    """Projection given to rankers: no truth labels or annotation evidence.

    Gold stays ONLY with the benchmark judge. Changes in labels cannot
    change model-visible fingerprints or accidentally leak into ranking.
    """
    return {
        **{key: value for key, value in case.items() if key != "observations"},
        "observations": [
            {key: value for key, value in offer.items()
             if key not in {"truth", "truth_evidence"}}
            for offer in case["observations"]
        ],
    }


def wilson_interval(k: int, n: int, z: float = 1.96) -> tuple[float, float] | None:
    """95% score interval for a Bernoulli proportion, not a proof of truth."""
    if not (isinstance(k, int) and isinstance(n, int)) or k < 0 or n < 0 or k > n:
        raise BenchmarkInputError("invalid_proportion")
    if n == 0:
        return None
    from math import sqrt
    observed = k / n
    denominator = 1 + z*z/n
    centre = (observed + z*z/(2*n)) / denominator
    half = z * sqrt((observed*(1-observed) + z*z/(4*n))/n) / denominator
    return max(0.0, centre-half), min(1.0, centre+half)


def _intervals(scores: dict[str, Any]) -> dict[str, Any]:
    return {
        key: wilson_interval(scores[key]["numerator"], scores[key]["denominator"])
        for key in ("exact_precision_at_1", "recommendation_coverage",
                    "correct_coverage", "exact_recall_at_5")
    }


def case_fingerprint(case: dict[str, Any]) -> str:
    """Input-only hash, deliberately blinded to gold labels."""
    payload = json.dumps(blind_case(case), sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False, allow_nan=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _records(path: str | Path) -> list[dict[str, Any]]:
    path = Path(path)
    if path.stat().st_size > 12_000_000:
        raise BenchmarkInputError("manifest_exceeds_byte_budget")
    records = []
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if len(line) > 2_000_000:
                raise BenchmarkInputError("line_exceeds_byte_budget")
            if not line.strip():
                continue
            try:
                item = json.loads(line, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            except (ValueError, TypeError) as error:
                raise BenchmarkInputError("malformed_jsonl") from error
            if not isinstance(item, dict):
                raise BenchmarkInputError("record_is_not_object")
            records.append(item)
            if len(records) > 2000:
                raise BenchmarkInputError("case_limit_exceeded")
    if not records:
        raise BenchmarkInputError("empty_manifest")
    return records


def _is_opaque_id(value: Any) -> bool:
    return (isinstance(value, str) and 1 <= len(value) <= 80 and
            value.isascii() and all(ch.isalnum() or ch in "-_" for ch in value))


def load_cases(path: str | Path) -> list[dict[str, Any]]:
    cases = _records(path)
    ids: set[str] = set()
    for c in cases:
        if c.get("schema_version") != 1:
            raise BenchmarkInputError("unsupported_schema")
        cid = c.get("case_id")
        if not _is_opaque_id(cid) or cid in ids:
            raise BenchmarkInputError("invalid_or_duplicate_case_id")
        ids.add(cid)
        if c.get("dataset_kind") not in {"real", "synthetic"}:
            raise BenchmarkInputError("missing_dataset_provenance")
        if c.get("split") not in {"development", "holdout"}:
            raise BenchmarkInputError("invalid_dataset_split")
        if c.get("stratum") not in STRATA:
            raise BenchmarkInputError("invalid_stratum")
        intent = c.get("intent")
        if not isinstance(intent, dict) or not isinstance(intent.get("title"), str) or not isinstance(intent.get("artist"), str):
            raise BenchmarkInputError("missing_canonical_query")
        offers = c.get("observations")
        if not isinstance(offers, list) or len(offers) > 500:
            raise BenchmarkInputError("invalid_offer_count")
        offer_ids: set[str] = set()
        for o in offers:
            if not isinstance(o, dict):
                raise BenchmarkInputError("invalid_offer")
            oid = o.get("offer_id")
            if not _is_opaque_id(oid) or oid in offer_ids:
                raise BenchmarkInputError("duplicate_or_unsafe_offer_id")
            offer_ids.add(oid)
            if o.get("truth") not in TRUTH_LABELS:
                raise BenchmarkInputError("invalid_truth_label")
            evidence = o.get("truth_evidence")
            if not isinstance(evidence, str) or not evidence.strip():
                raise BenchmarkInputError("missing_truth_provenance")
            if c["dataset_kind"] == "real" and evidence == "synthetic_constructed":
                raise BenchmarkInputError("synthetic_truth_masquerading_as_real")
    return cases


def _check_prediction(c: dict[str, Any], p: dict[str, Any]) -> None:
    if p.get("case_id") != c["case_id"] or p.get("input_sha256") != case_fingerprint(c):
        raise BenchmarkInputError("prediction_input_mismatch")
    ids = {o["offer_id"] for o in c["observations"]}
    observed = p.get("observed_offer_ids")
    ranked = p.get("ranked_offer_ids")
    if (not isinstance(observed, list) or len(observed) != len(ids) or
            set(observed) != ids or not isinstance(ranked, list) or
            len(set(ranked)) != len(ranked) or not set(ranked).issubset(ids)):
        raise BenchmarkInputError("prediction_offer_parity_failed")
    rec = p.get("recommended_offer_id")
    if rec is not None and rec not in ranked:
        raise BenchmarkInputError("recommendation_not_ranked")
    if not _is_opaque_id(p.get("model_version")):
        raise BenchmarkInputError("invalid_model_version")
    if not _is_opaque_id(p.get("recommendation_policy")):
        raise BenchmarkInputError("invalid_recommendation_policy")


def _validate_all(cases: list[dict[str, Any]], predictions: dict[str, dict[str, Any]]) -> None:
    expected = {c["case_id"] for c in cases}
    if set(predictions) != expected:
        raise BenchmarkInputError("predictions_do_not_cover_manifest")
    for c in cases:
        _check_prediction(c, predictions[c["case_id"]])


def load_predictions(path: str | Path, cases: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    records = _records(path)
    predictions: dict[str, dict[str, Any]] = {}
    for p in records:
        cid = p.get("case_id")
        if not _is_opaque_id(cid) or cid in predictions:
            raise BenchmarkInputError("duplicate_or_invalid_prediction_case")
        predictions[cid] = p
    _validate_all(cases, predictions)
    return predictions


def score_model(cases: list[dict[str, Any]], predictions: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """Exact-edition metrics only when independent labels settle the outcome.

    No conditional metric is interpreted as a probability of being correct.
    UNKNOWN never counts as correct NOR as an error.
    """
    _validate_all(cases, predictions)
    counts: Counter[str] = Counter()
    for c in cases:
        p = predictions[c["case_id"]]
        truth = {o["offer_id"]: o["truth"] for o in c["observations"]}
        exact_available = any(label == "EXACT_EDITION" for label in truth.values())
        counts["cases"] += 1
        if exact_available:
            counts["eligible_exact_cases"] += 1
            if any(truth[oid] == "EXACT_EDITION" for oid in p["ranked_offer_ids"][:5]):
                counts["exact_at_5"] += 1
        top = p["recommended_offer_id"]
        if top is None:
            counts["abstentions"] += 1
            if exact_available:
                counts["abstained_despite_exact_available"] += 1
            continue
        counts["recommendations"] += 1
        label = truth[top]
        if label == "EXACT_EDITION":
            counts["exact_top1"] += 1
        elif label == "WRONG_EDITION_VERSION":
            counts["wrong_version_top1"] += 1
        elif label == "WRONG_ARTIST_OR_RECORDING":
            counts["wrong_artist_top1"] += 1
        elif label == "TECHNICALLY_INELIGIBLE":
            counts["ineligible_top1"] += 1
        else:
            counts["unknown_recommendations"] += 1
        if label in SETTLED:
            counts["settled_recommendations"] += 1
    n = counts["cases"]
    return {
        "cases": n,
        "recommendations": counts["recommendations"],
        "abstentions": counts["abstentions"],
        "settled_recommendations": counts["settled_recommendations"],
        "unknown_recommendations": counts["unknown_recommendations"],
        "exact_top1": counts["exact_top1"],
        "wrong_version_top1": counts["wrong_version_top1"],
        "wrong_artist_top1": counts["wrong_artist_top1"],
        "ineligible_top1": counts["ineligible_top1"],
        "abstained_despite_exact_available": counts["abstained_despite_exact_available"],
        "eligible_exact_cases": counts["eligible_exact_cases"],
        "exact_precision_at_1": _fraction(counts["exact_top1"], counts["settled_recommendations"]),
        "recommendation_coverage": _fraction(counts["recommendations"], n),
        "correct_coverage": _fraction(counts["exact_top1"], counts["eligible_exact_cases"]),
        "exact_recall_at_5": _fraction(counts["exact_at_5"], counts["eligible_exact_cases"]),
    }


def _paired(cases: list[dict[str, Any]], left: dict, right: dict) -> dict[str, int]:
    pairs: Counter[str] = Counter()
    for c in cases:
        truth = {o["offer_id"]: o["truth"] for o in c["observations"]}
        labels = []
        for result in (left, right):
            rec = result[c["case_id"]]["recommended_offer_id"]
            labels.append(truth[rec] if rec else None)
        if any(x is not None and x not in SETTLED for x in labels):
            pairs["excluded_unknown"] += 1
            continue
        def grade(label: str | None) -> int:
            if label == "EXACT_EDITION":
                return 2
            if label is None:
                return 1
            return 0
        lscore, rscore = (grade(v) for v in labels)
        if lscore > rscore:
            pairs["left_wins"] += 1
        elif lscore < rscore:
            pairs["right_wins"] += 1
        else:
            pairs["ties"] += 1
    return {key: pairs[key] for key in ("left_wins", "right_wins", "ties", "excluded_unknown")}


def compare(cases: list[dict[str, Any]], left: dict, right: dict,
            *, allow_synthetic: bool = False) -> dict[str, Any]:
    _validate_all(cases, left)
    _validate_all(cases, right)
    kinds = {c["dataset_kind"] for c in cases}
    if len(kinds) != 1:
        raise BenchmarkInputError("mixed_real_and_synthetic_corpus")
    kind = next(iter(kinds))
    if kind != "real" and not allow_synthetic:
        raise BenchmarkInputError("synthetic_benchmark_requires_opt_in")
    if kind == "real":
        # Declaring dataset_kind=real is NOT independent verification.
        # Require two external annotations and explicit sealed adjudication;
        # the code still cannot verify the underlying audio/master itself.
        for case in cases:
            audit = case.get("audit")
            if (not isinstance(audit, dict) or
                    audit.get("independent_annotators", 0) < 2 or
                    audit.get("adjudication") != "resolved" or
                    audit.get("gold_sealed") is not True):
                raise BenchmarkInputError("real_corpus_requires_independent_gold_audit")
    if any(c["split"] == "holdout" for c in cases):
        raise BenchmarkInputError("sealed_holdout_cannot_be_compared_in_development")
    strata = {
        stratum: {
            "left": score_model([c for c in cases if c["stratum"] == stratum], left_subset(left, cases, stratum)),
            "right": score_model([c for c in cases if c["stratum"] == stratum], left_subset(right, cases, stratum)),
        }
        for stratum in sorted({c["stratum"] for c in cases})
    }
    return {
        "schema_version": 1,
        "dataset_kind": kind,
        # Machine-checkable audit metadata is NOT independent proof of audio.
        "valid_real_world_claim": False,
        "audit_metadata_complete": kind == "real",
        "n_cases": len(cases),
        "left_model_version": sorted({p["model_version"] for p in left.values()}),
        "right_model_version": sorted({p["model_version"] for p in right.values()}),
        "left_recommendation_policy": sorted({p["recommendation_policy"] for p in left.values()}),
        "right_recommendation_policy": sorted({p["recommendation_policy"] for p in right.values()}),
        "left": score_model(cases, left),
        "right": score_model(cases, right),
        "left_wilson_95": _intervals(score_model(cases, left)),
        "right_wilson_95": _intervals(score_model(cases, right)),
        "paired": _paired(cases, left, right),
        "strata": strata,
        "warning": ("synthetic_truth_not_measured_music_accuracy" if kind == "synthetic" else
                    "real_ground_truth_human_review_still_required"),
    }


def left_subset(predictions: dict, cases: list[dict], stratum: str) -> dict:
    return {c["case_id"]: predictions[c["case_id"]] for c in cases if c["stratum"] == stratum}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only, count-only Soulseek benchmark.")
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--left", type=Path)
    parser.add_argument("--right", type=Path)
    parser.add_argument("--allow-synthetic", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--export-blind", type=Path,
                        help="Export scorer input JSONL WITHOUT ground-truth labels")
    args = parser.parse_args(argv)
    try:
        cases = load_cases(args.cases)
        if args.export_blind is not None:
            if args.left is not None or args.right is not None or args.output is not None:
                raise BenchmarkInputError("blind_export_must_be_isolated")
            with args.export_blind.open("w", encoding="utf-8") as output_stream:
                for case in cases:
                    output_stream.write(json.dumps(blind_case(case), ensure_ascii=False,
                                                   separators=(",", ":")) + "\n")
            print(json.dumps({"export": "blind_input", "n_cases": len(cases)}))
            return 0
        if args.left is None and args.right is None:
            result = {"schema_version": 1, "n_cases": len(cases),
                      "dataset_kind": sorted({c["dataset_kind"] for c in cases}),
                      "validation": "valid_manifest_only"}
        elif args.left is not None and args.right is not None:
            result = compare(cases, load_predictions(args.left, cases),
                             load_predictions(args.right, cases),
                             allow_synthetic=args.allow_synthetic)
        else:
            raise BenchmarkInputError("both_predictions_required")
        output = json.dumps(result, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.write_text(output, encoding="utf-8")
        else:
            print(output, end="")
        return 0
    except (BenchmarkInputError, OSError) as e:
        # Sanitize: never emit user-supplied filename/path/query details.
        print("benchmark_input_error:" + (str(e) if isinstance(e, BenchmarkInputError)
                                         else "read_write_failed"), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

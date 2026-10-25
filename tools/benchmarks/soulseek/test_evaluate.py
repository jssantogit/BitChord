"""Behavioral tests for the read-only offline Soulseek benchmark; no network."""
import json
import tempfile
import unittest
from pathlib import Path

from evaluate import (
    BenchmarkInputError,
    case_fingerprint,
    compare,
    load_cases,
    load_predictions,
    score_model,
)


def make_case(case_id, labels, stratum="versions", dataset="synthetic"):
    return {
        "schema_version": 1,
        "case_id": case_id,
        "stratum": stratum,
        "split": "development",
        "dataset_kind": dataset,
        "intent": {"artist": "Example", "title": "Track", "album": "Main Album",
                   "strict_album": True, "format_profile": "high"},
        "observations": [
            {"offer_id": key, "filename": "Synthetic-" + key + ".mp3",
             "directory": "Synthetic/Example", "claimed_artist": "Example",
             "claimed_title": "Track", "claimed_album": "Main Album",
             "format": "mp3", "bitrate_kbps": 320, "duration_seconds": 180,
             "size_bytes": 7_200_000, "locked": False,
             "truth": value, "truth_evidence": "synthetic_constructed"}
            for key, value in labels.items()
        ],
    }


def make_prediction(case, ranked, top, model="baseline"):
    return {
        "case_id": case["case_id"],
        "input_sha256": case_fingerprint(case),
        "observed_offer_ids": [o["offer_id"] for o in case["observations"]],
        "ranked_offer_ids": ranked,
        "recommended_offer_id": top,
        "model_version": model,
        "recommendation_policy": "rank_first",
    }


class OfflineBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.case1 = make_case("c1", {
            "correct": "EXACT_EDITION", "remaster": "WRONG_EDITION_VERSION"})
        self.case2 = make_case("c2", {
            "unknown": "UNKNOWN_UNVERIFIABLE", "wrong": "WRONG_ARTIST_OR_RECORDING"})
        self.case3 = make_case("c3", {}, "empty")
        self.cases = [self.case1, self.case2, self.case3]

    def test_metrics_do_not_count_unknown_as_correct_or_wrong(self):
        picks = {
            "c1": make_prediction(self.case1, ["correct", "remaster"], "correct"),
            "c2": make_prediction(self.case2, ["unknown", "wrong"], "unknown"),
            "c3": make_prediction(self.case3, [], None),
        }
        result = score_model(self.cases, picks)
        self.assertEqual(result["cases"], 3)
        self.assertEqual(result["recommendations"], 2)
        self.assertEqual(result["settled_recommendations"], 1)
        self.assertEqual(result["unknown_recommendations"], 1)
        self.assertEqual(result["exact_top1"], 1)
        self.assertEqual(result["wrong_version_top1"], 0)
        self.assertEqual(result["eligible_exact_cases"], 1)
        self.assertEqual(result["exact_recall_at_5"], {"numerator": 1, "denominator": 1})
        self.assertEqual(result["exact_precision_at_1"], {"numerator": 1, "denominator": 1})
        self.assertEqual(result["recommendation_coverage"], {"numerator": 2, "denominator": 3})

    def test_wrong_version_top_is_not_compensated_by_score(self):
        picks = {"c1": make_prediction(self.case1, ["remaster", "correct"], "remaster")}
        result = score_model([self.case1], picks)
        self.assertEqual(result["wrong_version_top1"], 1)
        self.assertEqual(result["exact_precision_at_1"], {"numerator": 0, "denominator": 1})
        self.assertEqual(result["exact_recall_at_5"], {"numerator": 1, "denominator": 1})

    def test_missing_right_candidate_is_not_counted_as_failure_to_recall(self):
        only_bad = make_case("only-bad", {"bad": "WRONG_EDITION_VERSION"})
        result = score_model([only_bad], {"only-bad": make_prediction(only_bad, ["bad"], None)})
        self.assertEqual(result["eligible_exact_cases"], 0)
        self.assertEqual(result["exact_recall_at_5"], {"numerator": 0, "denominator": 0})

    def test_identical_manifest_and_offers_are_required_for_paired_comparison(self):
        lhs = {c["case_id"]: make_prediction(c, [o["offer_id"] for o in c["observations"]],
                                               c["observations"][0]["offer_id"] if c["observations"] else None)
               for c in self.cases}
        rhs = {k: dict(v) for k, v in lhs.items()}
        rhs["c1"]["input_sha256"] = "0" * 64
        with self.assertRaises(BenchmarkInputError):
            compare(self.cases, lhs, rhs, allow_synthetic=True)

    def test_refuses_to_publish_synthetic_results_as_real(self):
        picks = {"c1": make_prediction(self.case1, ["correct", "remaster"], "correct")}
        with self.assertRaises(BenchmarkInputError):
            compare([self.case1], picks, picks)
        report = compare([self.case1], picks, picks, allow_synthetic=True)
        self.assertEqual(report["dataset_kind"], "synthetic")
        self.assertFalse(report["valid_real_world_claim"])
        self.assertEqual(report["paired"]["ties"], 1)

    def test_predictions_must_contain_all_offers_but_may_rank_subset(self):
        p = make_prediction(self.case1, ["correct"], "correct")
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            file = root / "p.jsonl"
            file.write_text(json.dumps(p) + "\n")
            data = load_predictions(file, [self.case1])
        self.assertEqual(data["c1"]["ranked_offer_ids"], ["correct"])
        p["observed_offer_ids"] = ["correct"]
        with tempfile.TemporaryDirectory() as td:
            file = Path(td) / "bad.jsonl"
            file.write_text(json.dumps(p) + "\n")
            with self.assertRaises(BenchmarkInputError):
                load_predictions(file, [self.case1])

    def test_rejects_unknown_ids_duplicate_ids_and_ground_truth_without_provenance(self):
        c = make_case("c", {"a": "EXACT_EDITION"})
        c["observations"][0]["truth_evidence"] = ""
        with tempfile.TemporaryDirectory() as td:
            file = Path(td) / "cases.jsonl"
            file.write_text(json.dumps(c) + "\n")
            with self.assertRaises(BenchmarkInputError):
                load_cases(file)
        c["observations"][0]["truth_evidence"] = "synthetic_constructed"
        p = make_prediction(c, ["a", "a"], "a")
        with self.assertRaises(BenchmarkInputError):
            score_model([c], {"c": p})

    def test_checked_in_manifest_is_synthetic_and_stratified(self):
        cases = load_cases(Path(__file__).parent / "synthetic-cases.jsonl")
        self.assertEqual(len(cases), 18)
        self.assertEqual(
            {name: sum(c["stratum"] == name for c in cases)
             for name in {"ordinary", "versions", "editions",
                          "artists", "ambiguous", "quality"}},
            {name: 3 for name in {"ordinary", "versions", "editions",
                                  "artists", "ambiguous", "quality"}},
        )
        self.assertEqual({c["dataset_kind"] for c in cases}, {"synthetic"})

    def test_public_input_fingerprint_excludes_sealed_gold_labels(self):
        original = make_case("blind-01", {"a": "EXACT_EDITION"})
        changed_truth = make_case("blind-01", {"a": "WRONG_EDITION_VERSION"})
        self.assertEqual(case_fingerprint(original), case_fingerprint(changed_truth))
        changed_offer = make_case("blind-01", {"a": "EXACT_EDITION"})
        changed_offer["observations"][0]["bitrate_kbps"] = 96
        self.assertNotEqual(case_fingerprint(original), case_fingerprint(changed_offer))

    def test_comparison_report_does_not_export_peer_filename_or_query(self):
        picks = {"c1": make_prediction(self.case1, ["correct", "remaster"], "correct")}
        report = compare([self.case1], picks, picks, allow_synthetic=True)
        blob = json.dumps(report, sort_keys=True)
        self.assertNotIn("Synthetic-correct", blob)
        self.assertNotIn("Main Album", blob)
        self.assertNotIn("Example", blob)


if __name__ == "__main__":
    unittest.main()

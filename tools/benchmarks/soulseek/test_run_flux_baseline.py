"""Do not permit Flux replay to see gold labels or drift from pinned code."""
import json
import tempfile
import unittest
from pathlib import Path

from evaluate import BenchmarkInputError, blind_case, load_cases
from run_flux_baseline import _load_blind, check_flux_ref, PINNED_FLUX_SHA


class FluxBaselineTest(unittest.TestCase):
    def test_comparator_has_an_exact_pinned_commit(self):
        self.assertEqual(len(PINNED_FLUX_SHA), 40)
        self.assertTrue(all(ch in "0123456789abcdef" for ch in PINNED_FLUX_SHA))
        with self.assertRaises(BenchmarkInputError):
            check_flux_ref(Path("/no-such-repository"))

    def test_runner_rejects_unblinded_truth_labels(self):
        cases = load_cases(
            Path(__file__).resolve().parents[3] /
            "app/src/test/resources/soulseek/synthetic-cases.jsonl"
        )
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "blind.jsonl"
            f.write_text(json.dumps(cases[0]) + "\n")
            with self.assertRaises(BenchmarkInputError):
                _load_blind(f)
            f.write_text(json.dumps(blind_case(cases[0])) + "\n")
            public = _load_blind(f)
            self.assertEqual(len(public), 1)
            self.assertNotIn("truth", public[0]["observations"][0])


if __name__ == "__main__":
    unittest.main()

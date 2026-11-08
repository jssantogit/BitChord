import unittest
from catalog_211_discogs_corpus_report import report

def case(cohort, i):
    return dict(id=f"{cohort}-{i}", cohort=cohort, reviewed=True,
                musicbrainz_group_mbid=("00000000-0000-4000-8000-"
                    + f"{i + {'type': 0, 'unlinked': 100, 'source_only': 200}[cohort]:012d}"),
                discogs_release_id=(1000
                    + {'type': 0, 'unlinked': 100, 'source_only': 200}[cohort] + i),
                sources=["artist", "discogs"], reference_type="EP",
                musicbrainz_type="Album", app_type="EP",
                match_state="verified", match_correct=True,
                visible=True, absent_in_musicbrainz=cohort == "source_only")

class CorpusReportTests(unittest.TestCase):
    def test_missing_cases_cannot_invent_coverage(self):
        result = report({"schema_version": 1, "cases": []})
        self.assertFalse(result["complete"])
        self.assertIsNone(result["metrics"])

    def test_complete_evidence_yields_explicit_denominators(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        result = report({"schema_version": 1, "cases": cases})
        self.assertTrue(result["complete"])
        self.assertEqual({"corrected": 10, "eligible": 10},
                         result["metrics"]["type_correction"])
        self.assertEqual({"visible": 10, "confirmed_absent": 10},
                         result["metrics"]["source_only_recovery"])
        self.assertEqual(0, result["metrics"]["false_positive_count"])

    def test_unreviewed_and_invalid_case_disables_metrics(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        cases[5]["reviewed"] = False
        self.assertIsNone(report({"schema_version": 1, "cases": cases})["metrics"])
        cases[5]["reviewed"] = True
        cases[7]["sources"] = []
        self.assertIsNone(report({"schema_version": 1, "cases": cases})["metrics"])
        cases[7]["sources"] = ["discogs"]
        self.assertIsNone(report({"schema_version": 1, "cases": cases})["metrics"])
        cases[7]["sources"] = ["label"]
        cases[7]["discogs_release_id"] = None
        self.assertIsNone(report({"schema_version": 1, "cases": cases})["metrics"])

    def test_unknown_type_fill_is_not_counted_as_correction(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        cases[0]["musicbrainz_type"] = "Unknown"
        result = report({"schema_version": 1, "cases": cases})
        self.assertEqual({"corrected": 9, "eligible": 9},
                         result["metrics"]["type_correction"])
        self.assertEqual({"filled": 1, "eligible": 1},
                         result["metrics"]["type_fill"])

    def test_reusing_same_release_group_cannot_inflate_thirty_case_sample(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        cases[1]["musicbrainz_group_mbid"] = cases[0]["musicbrainz_group_mbid"]
        result = report({"schema_version": 1, "cases": cases})
        self.assertFalse(result["complete"])
        self.assertIsNone(result["metrics"])
        self.assertTrue(any("duplicate source identity" in error
                            for error in result["errors"]))

    def test_false_positive_is_not_hidden(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        cases[9]["match_correct"] = False
        result = report({"schema_version": 1, "cases": cases})
        self.assertEqual(1, result["metrics"]["false_positive_count"])
        self.assertEqual(29, result["metrics"]["verified_precision"]["correct"])

if __name__ == "__main__":
    unittest.main()

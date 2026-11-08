import unittest
from catalog_211_discogs_corpus_report import report

def case(cohort, i):
    return dict(id=f"{cohort}-{i}", cohort=cohort, reviewed=True,
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

    def test_false_positive_is_not_hidden(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        cases[9]["match_correct"] = False
        result = report({"schema_version": 1, "cases": cases})
        self.assertEqual(1, result["metrics"]["false_positive_count"])
        self.assertEqual(29, result["metrics"]["verified_precision"]["correct"])

if __name__ == "__main__":
    unittest.main()

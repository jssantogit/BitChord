import unittest
import json
from pathlib import Path
from catalog_211_discogs_corpus_report import report

def case(cohort, i):
    index = i + {"type": 0, "unlinked": 100, "source_only": 200}[cohort]
    group = "00000000-0000-4000-8000-" + f"{index:012d}"
    release = "00000000-0000-4000-8000-" + f"{index + 1000:012d}"
    artist = "00000000-0000-4000-8000-" + f"{index + 2000:012d}"
    discogs_id = 1000 + index
    links = {
        "discogs_release": f"https://www.discogs.com/release/{discogs_id}",
        # Synthetic link for validator tests; the test does not assert live truth.
        "independent_editorial": "https://example.org/discography/official",
        "observed_workflow": "https://github.com/jssantogit/BitChord/actions/runs/42818809120",
        "musicbrainz_release": f"https://musicbrainz.org/release/{release}",
        "musicbrainz_release_group": f"https://musicbrainz.org/release-group/{group}",
        "musicbrainz_artist": f"https://musicbrainz.org/artist/{artist}",
    }
    return dict(id=f"{cohort}-{i}", cohort=cohort, reviewed=True,
                musicbrainz_group_mbid=group,
                musicbrainz_release_mbid=release,
                musicbrainz_artist_mbid=artist,
                artist_browse_complete=True,
                discogs_release_id=discogs_id,
                evidence_links=links,
                reviewed_at_utc="2026-11-10T00:00:00Z",
                review_notes="Independent editorial source checked alongside public source IDs; synthetic validator test case.",
                sources=["artist", "discogs"], reference_type="EP",
                musicbrainz_type="Album", app_type="EP",
                match_state="verified", match_correct=True,
                visible=True, absent_in_musicbrainz=cohort == "source_only")

class CorpusReportTests(unittest.TestCase):
    def test_real_staged_manifest_reports_selected_but_no_reviewed_or_metrics(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        result = report(manifest)
        self.assertEqual([], result["errors"])
        self.assertFalse(result["complete"])
        self.assertIsNone(result["metrics"])
        self.assertEqual(5, result["slots"]["unlinked"]["selected_unreviewed"])
        self.assertEqual(3, result["slots"]["source_only"]["selected_unreviewed"])
        self.assertEqual(3, result["slots"]["source_only"]["excluded_preliminary"])
        self.assertEqual(0, sum(x["reviewed"] for x in result["slots"].values()))

    def test_wrong_provider_links_and_duplicate_seeded_editions_fail_closed(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        first = manifest["cases"][10]
        self.assertEqual("B-01", first["id"])
        first["evidence_links"]["discogs_release"] = "https://www.discogs.com/release/123"
        result = report(manifest)
        self.assertTrue(any("invalid unreviewed selection evidence" in s
                            for s in result["errors"]))
        self.assertEqual(4, result["slots"]["unlinked"]["selected_unreviewed"])
        self.assertIsNone(result["metrics"])
        first["evidence_links"]["discogs_release"] = (
            f"https://www.discogs.com/release/{first['discogs_release_id']}"
        )
        second = manifest["cases"][11]
        second["discogs_release_id"] = first["discogs_release_id"]
        second["evidence_links"]["discogs_release"] = (
            f"https://www.discogs.com/release/{second['discogs_release_id']}"
        )
        result = report(manifest)
        self.assertTrue(any("duplicate unreviewed source edition" in s
                            for s in result["errors"]))
        self.assertIsNone(result["metrics"])

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

    def test_review_requires_typed_source_urls_independent_date_and_notes(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        valid = report({"schema_version": 1, "cases": cases})
        self.assertTrue(valid["complete"])
        for changes in (
            {"reviewed_at_utc": "sometime"},
            {"review_notes": "ok"},
            {"evidence_links": {**cases[0]["evidence_links"],
                                "independent_editorial": "https://discogs.com/release/1000"}},
            {"evidence_links": {**cases[0]["evidence_links"],
                                "discogs_release": "https://www.discogs.com/release/999"}},
            {"evidence_links": {**cases[0]["evidence_links"],
                                "musicbrainz_release_group": "https://musicbrainz.org/release-group/invalid"}},
        ):
            item = dict(cases[0])
            item.update(changes)
            result = report({"schema_version": 1, "cases": [item] + cases[1:]})
            self.assertFalse(result["complete"], changes)
            self.assertIsNone(result["metrics"])
            self.assertTrue(any("independent, dated review evidence" in e for e in result["errors"]))
        for cohort, index, field in (("unlinked", 10, "musicbrainz_release"),
                                    ("source_only", 20, "musicbrainz_artist")):
            changed = [dict(x) for x in cases]
            changed[index]["evidence_links"] = {
                **changed[index]["evidence_links"], field: "https://evil.example/p"}
            result = report({"schema_version": 1, "cases": changed})
            self.assertFalse(result["complete"], cohort)
            self.assertIsNone(result["metrics"])

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

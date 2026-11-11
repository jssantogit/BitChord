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
        self.assertEqual(0, result["slots"]["unlinked"]["selected_unreviewed"])
        self.assertEqual(3, result["slots"]["source_only"]["selected_unreviewed"])
        self.assertEqual(3, result["slots"]["source_only"]["excluded_preliminary"])
        self.assertEqual(10, sum(x["reviewed"] for x in result["slots"].values()))

    def test_unreviewed_c_exclusions_reject_wrong_links_and_duplicate_ids(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        first = manifest["cases"][20]
        self.assertEqual("C-01", first["id"])
        first["evidence_links"]["discogs_release"] = "https://www.discogs.com/release/123"
        result = report(manifest)
        self.assertTrue(any("invalid unreviewed selection evidence" in s
                            for s in result["errors"]))
        self.assertEqual(2, result["slots"]["source_only"]["selected_unreviewed"])
        self.assertIsNone(result["metrics"])
        first["evidence_links"]["discogs_release"] = (
            f"https://www.discogs.com/release/{first['discogs_release_id']}"
        )
        second = manifest["cases"][21]
        second["discogs_release_id"] = first["discogs_release_id"]
        second["evidence_links"]["discogs_release"] = (
            f"https://www.discogs.com/release/{second['discogs_release_id']}"
        )
        result = report(manifest)
        self.assertTrue(any("duplicate unreviewed source edition" in s
                            for s in result["errors"]))
        self.assertIsNone(result["metrics"])

    def test_all_ten_b_controls_are_source_reviewed_without_verified_editions(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        b_cases = [x for x in manifest["cases"] if x["cohort"] == "unlinked"
                   and x.get("discogs_release_id")]
        self.assertEqual(10, len(b_cases))
        self.assertTrue(all(x["reviewed"] for x in b_cases))
        self.assertEqual(
            {"B-01": "conflict", "B-02": "candidate", "B-03": "candidate",
             "B-04": "candidate", "B-05": "conflict",
             "B-06": "candidate", "B-07": "conflict",
             "B-08": "conflict", "B-09": "conflict", "B-10": "candidate"},
            {x["id"]: x["match_state"] for x in b_cases},
        )
        self.assertTrue(all(x["musicbrainz_type"] in {"EP", "Single"}
                            and x["reference_type"] == (
                                "Unknown" if x["id"] == "B-01" else x["musicbrainz_type"])
                            and x["app_type"] == "Unknown"
                            and x["absent_in_musicbrainz"] is False
                            and x["match_correct"] is False
                            and x["visible"] is False
                            and "independent_editorial" in x["evidence_links"]
                            and "selection_state" not in x
                            for x in b_cases))
        result = report(manifest)
        self.assertEqual([], result["errors"])
        self.assertEqual(10, result["slots"]["unlinked"]["reviewed"])
        self.assertIsNone(result["metrics"])
        for case_id, mbid, discogs_id in (
            ("B-06", "6ca81526-ecfe-4bc9-be65-d5f20a23f084", 16620135),
            ("B-07", "cf60fbce-4c79-45d2-9249-293b17a8f8fc", 23314901),
            ("B-08", "47106725-be2e-4af7-9d27-4610f4116e0b", 21255514),
            ("B-09", "251ad807-4bf0-4810-a4f9-34673d6d2ff8", 25759165),
            ("B-10", "b9290e0d-be3a-41d0-b145-88fe342aa5da", 25524349),
        ):
            item = next(x for x in b_cases if x["id"] == case_id)
            self.assertEqual(mbid, item["musicbrainz_release_mbid"])
            self.assertEqual(discogs_id, item["discogs_release_id"])
            self.assertEqual("no_direct_release_link",
                             item["direct_release_relation"])
            self.assertIn("independent_editorial", item["evidence_links"])
            self.assertFalse(item["match_correct"])
            self.assertFalse(item["absent_in_musicbrainz"])

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
        # Preserve valid source URLs so this specifically exercises the
        # duplicate ReleaseGroup guard, not the new provenance guard.
        cases[1]["evidence_links"]["musicbrainz_release_group"] = (
            cases[0]["evidence_links"]["musicbrainz_release_group"]
        )
        result = report({"schema_version": 1, "cases": cases})
        self.assertFalse(result["complete"])
        self.assertIsNone(result["metrics"])
        self.assertTrue(any("duplicate source identity" in error
                            for error in result["errors"]))

    def test_adjudicated_barcode_conflict_is_not_a_verified_edition_match(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        cases[10]["match_state"] = "conflict"
        cases[10]["match_correct"] = False
        cases[10]["visible"] = False
        result = report({"schema_version": 1, "cases": cases})
        self.assertTrue(result["complete"])
        self.assertEqual([], result["errors"])
        self.assertEqual(9, result["metrics"]["unlinked_matching"]["verified"])
        self.assertEqual(29, result["metrics"]["verified_precision"]["verified"])
        self.assertEqual(0, result["metrics"]["false_positive_count"])

    def test_live_b05_is_documented_as_conflict_not_an_album_correction(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        b05 = next(case for case in manifest["cases"] if case["id"] == "B-05")
        self.assertTrue(b05["reviewed"])
        self.assertEqual("conflict", b05["match_state"])
        self.assertEqual("EP", b05["musicbrainz_type"])
        self.assertEqual("Unknown", b05["app_type"])
        self.assertFalse(b05["absent_in_musicbrainz"])

    def test_false_positive_is_not_hidden(self):
        cases = [case(c, i) for c in ("type", "unlinked", "source_only")
                 for i in range(10)]
        cases[9]["match_correct"] = False
        result = report({"schema_version": 1, "cases": cases})
        self.assertEqual(1, result["metrics"]["false_positive_count"])
        self.assertEqual(29, result["metrics"]["verified_precision"]["correct"])

if __name__ == "__main__":
    unittest.main()

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
    def test_reusing_one_discogs_edition_in_b_and_c_must_be_rejected(self):
        # Reproduce the historical C07/B02 or C08/B01 double counting even
        # after the live corpus itself is repaired to independent C editions.
        manifest=json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        existing=next(x for x in manifest["cases"] if x["id"]=="B-02")
        target=next(x for x in manifest["cases"] if x["id"]=="C-07")
        target["discogs_release_id"]=existing["discogs_release_id"]
        target["evidence_links"]["discogs_release"]=existing["evidence_links"]["discogs_release"]
        self.assertTrue(any("C-07: duplicate reviewed Discogs edition across cohorts"
                            in e for e in report(manifest)["errors"]))

    def test_ten_independent_c_audio_editions_and_legacy_exclusion_archive(self):
        manifest=json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        expected={
            "C-01":(31961039,"b51a484e-872d-4355-9de8-f6a7034f79d2"),
            "C-02":(31781147,"56384077-7321-4f57-8186-38ef11710552"),
            "C-03":(31422908,"518945b0-343b-404d-9b63-7c57c8b311dc"),
            "C-04":(38526540,"e248dce6-bef8-4d1f-a6d8-164de112151f"),
            "C-05":(32437206,"0c668bdb-328c-4dba-8407-8eddb5373a5d"),
            "C-06":(28089088,"14e1a869-8378-47df-818e-07dfe1d0c7fc"),
            "C-07":(27107343,"829216ed-7ab6-434f-a3ab-a504ac32819f"),
            "C-08":(34417612,"f95117e6-cd4f-4d7b-8e92-f7d216a156cd"),
            "C-09":(29892898,"b64d0fbf-a084-40e9-9087-b8c333ebb775"),
            "C-10":(27690417,"72f51e2e-0257-41d5-8b5c-5f21c7e338ed"),
        }
        c=[x for x in manifest["cases"] if x["cohort"]=="source_only"]
        b={x["discogs_release_id"] for x in manifest["cases"]
           if x["cohort"]=="unlinked"}
        self.assertEqual(10,len(c))
        self.assertEqual(10,len({x["discogs_release_id"] for x in c}))
        self.assertFalse({x["discogs_release_id"] for x in c}&b)
        self.assertTrue(all(x["reviewed"] and x["absent_in_musicbrainz"] is False
                            and x["match_state"]=="candidate" and
                            x["app_type"]=="Unknown" and x["visible"] is False
                            for x in c))
        for row in c:
            self.assertEqual(expected[row["id"]],
                             (row["discogs_release_id"],row["musicbrainz_group_mbid"]))
        archived=manifest["screened_out_sources"]
        self.assertEqual({35180680,38613330,33375572},
                         {x["discogs_release_id"] for x in archived})
        self.assertTrue(all(x["selection_state"]=="excluded_by_provider_metadata"
                            for x in archived))
        result=report(manifest)
        self.assertEqual([],result["errors"])
        self.assertTrue(result["complete"])
        self.assertEqual(10,result["slots"]["source_only"]["reviewed"])
        self.assertEqual(0,result["metrics"]["source_only_recovery"]["confirmed_absent"])
        self.assertEqual(0,result["metrics"]["source_only_recovery"]["visible"])

    def test_actual_corpus_complete_but_no_positive_provider_gain(self):
        manifest=json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        result=report(manifest)
        self.assertEqual([], result["errors"])
        self.assertTrue(result["complete"])
        self.assertEqual(0, result["slots"]["unlinked"]["selected_unreviewed"])
        self.assertEqual(0, result["slots"]["source_only"]["selected_unreviewed"])
        self.assertEqual(0, result["slots"]["source_only"]["excluded_preliminary"])
        self.assertEqual(3, result["screened_out_not_reviewed"])
        self.assertEqual(30, sum(x["reviewed"] for x in result["slots"].values()))
        self.assertIsNotNone(result["metrics"])
        self.assertEqual(0, result["metrics"]["unlinked_matching"]["verified"])
        self.assertEqual(0, result["metrics"]["source_only_recovery"]["confirmed_absent"])
        self.assertEqual(0, result["metrics"]["type_correction"]["corrected"])

    def test_archived_c_exclusions_reject_wrong_links_and_duplicate_ids(self):
        manifest=json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        archived=manifest["screened_out_sources"]
        first=archived[0]
        self.assertEqual("C-01",first["original_slot"])
        old=first["evidence_links"]["discogs_release"]
        first["evidence_links"]["discogs_release"]="https://www.discogs.com/release/123"
        result=report(manifest)
        self.assertTrue(any("screened-out source C-01: invalid archived evidence" in e
                            for e in result["errors"]))
        self.assertIsNone(result["metrics"])
        first["evidence_links"]["discogs_release"]=old
        second=archived[1]
        second["discogs_release_id"]=first["discogs_release_id"]
        second["evidence_links"]["discogs_release"]=old
        result=report(manifest)
        self.assertTrue(any("screened-out source C-02: duplicate source edition" in e
                            for e in result["errors"]))
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
        self.assertIsNotNone(result["metrics"])
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

    def test_a01_fearless_primary_type_is_already_correct_by_source_music(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        a01 = next(x for x in manifest["cases"] if x["id"] == "A-01")
        self.assertTrue(a01["reviewed"])
        self.assertEqual("fb426d76-525d-4b25-90d1-fa4d1b3140c9",
                         a01["musicbrainz_group_mbid"])
        self.assertEqual(23314901, a01["discogs_release_id"])
        self.assertEqual("EP", a01["musicbrainz_type"])
        self.assertEqual("EP", a01["reference_type"])
        self.assertEqual("Unknown", a01["app_type"])
        self.assertEqual("candidate", a01["match_state"])
        self.assertFalse(a01["match_correct"])
        self.assertEqual("https://weverse.io/lesserafim/notice/2469",
                         a01["evidence_links"]["independent_editorial"])
        report_data = report(manifest)
        self.assertEqual([], report_data["errors"])
        self.assertIsNotNone(report_data["metrics"])
        self.assertEqual(10, report_data["slots"]["type"]["reviewed"])

    def test_a02_japanese_fearless_is_distinct_2023_single_not_2022_ep(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        a02 = next(x for x in manifest["cases"] if x["id"] == "A-02")
        a01 = next(x for x in manifest["cases"] if x["id"] == "A-01")
        self.assertTrue(a02["reviewed"])
        self.assertNotEqual(a01["musicbrainz_group_mbid"],
                            a02["musicbrainz_group_mbid"])
        self.assertEqual("c54392ad-7d95-447b-8b1a-bc5668d2f00d",
                         a02["musicbrainz_group_mbid"])
        self.assertEqual("49f40a51-d69c-4ef9-9ecf-c069fc70c11f",
                         a02["musicbrainz_release_mbid"])
        self.assertEqual(26168966, a02["discogs_release_id"])
        self.assertEqual("Single", a02["musicbrainz_type"])
        self.assertEqual("Single", a02["reference_type"])
        self.assertEqual("Unknown", a02["app_type"])
        self.assertEqual("candidate", a02["match_state"])
        self.assertFalse(a02["match_correct"])
        self.assertFalse(a02["absent_in_musicbrainz"])
        self.assertFalse(a02["visible"])
        self.assertIn("2023", a02["review_notes"])
        self.assertIn("2022", a02["review_notes"])
        self.assertEqual("https://www.universal-music.co.jp/lesserafim/news/2022-11-25-2/",
                         a02["evidence_links"]["independent_editorial"])
        self.assertEqual("https://github.com/jssantogit/BitChord/actions/runs/43135307603",
                         a02["evidence_links"]["observed_workflow"])
        result = report(manifest)
        self.assertEqual([], result["errors"])
        self.assertEqual(10, result["slots"]["type"]["reviewed"])
        self.assertIsNotNone(result["metrics"])

    def test_a_bulk_seven_reviews_distinguish_genuine_dispute_from_agreements(self):
        document = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        expectations = {
            # JYPE calls it a Holiday Special Single; MB's EP classification
            # is a REAL source disagreement, not an automatic type correction.
            "A-03": ("62812a62-f532-490a-9b52-1feccb3fdaba",
                     22076131, "EP", "Single",
                     "https://www.bandwagon.asia/articles/stray-kids-drop-christmas-evel-surprise-single-november-comeback-release-2021"),
            "A-04": ("1c8c9fa5-8b86-4dd1-81cb-b22f8dcd487c",
                     24725813, "Single", "Single",
                     "https://www.starship-ent.com/musician/ive"),
            "A-05": ("9750354e-78df-4058-8758-b5a7a2eccf66",
                     30848370, "Single", "Single",
                     "https://www.newjeans.jp/news/dwmgij"),
            "A-06": ("48e4a94a-7fe5-4764-8880-b23a91bd7ee3",
                     35165884, "EP", "EP",
                     "https://www.enmixx.com/en/discography/fe3o4-break"),
            "A-07": ("58e44e7a-8ebb-4f08-9dbb-9b7998a2a483",
                     25179088, "Single", "Single",
                     "https://www.starship-ent.com/musician/ive"),
            "A-08": ("b389c57b-ccf1-4d9f-b3cc-015b66365641",
                     15387239, "EP", "EP",
                     "https://ibighit.com/legacy/txt/the_dream_chapter-eternity-en.html"),
            "A-09": ("cfc67890-7a0a-465a-b817-d506f57b9058",
                     24854783, "EP", "EP",
                     "https://www.universal-music.co.jp/lesserafim/news/2022-09-19/"),
        }
        for case_id, (group, discogs_release, mb_kind, official_kind, official_url) in expectations.items():
            with self.subTest(case=case_id):
                row = next(x for x in document["cases"] if x["id"] == case_id)
                self.assertTrue(row["reviewed"])
                self.assertEqual(group, row["musicbrainz_group_mbid"])
                self.assertEqual(discogs_release, row["discogs_release_id"])
                self.assertEqual(mb_kind, row["musicbrainz_type"])
                self.assertEqual(official_kind, row["reference_type"])
                self.assertEqual(official_url, row["evidence_links"]["independent_editorial"])
                self.assertEqual("candidate", row["match_state"])
                self.assertEqual("Unknown", row["app_type"])
                self.assertFalse(row["match_correct"])
                self.assertFalse(row["visible"])
                self.assertFalse(row["absent_in_musicbrainz"])
                self.assertGreaterEqual(len(row["review_notes"].strip()), 80)
        self.assertEqual([], report(document)["errors"])
        self.assertTrue(report(document)["complete"])
        self.assertEqual(10, report(document)["slots"]["type"]["reviewed"])
        self.assertIsNotNone(report(document)["metrics"])

    def test_a10_ate_is_ep_according_to_jyp_without_exact_discogs_pressing_claim(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        row = next(x for x in manifest["cases"] if x["id"] == "A-10")
        self.assertTrue(row["reviewed"])
        self.assertEqual("350ea536-dbf2-437d-913f-40040a3932ab",
                         row["musicbrainz_group_mbid"])
        self.assertEqual(31284695, row["discogs_release_id"])
        self.assertEqual("EP", row["musicbrainz_type"])
        self.assertEqual("EP", row["reference_type"])
        self.assertEqual("candidate", row["match_state"])
        self.assertEqual("https://straykids.jype.com/discography/37ab301d",
                         row["evidence_links"]["independent_editorial"])
        self.assertEqual("Unknown", row["app_type"])
        self.assertFalse(row["visible"])
        self.assertFalse(row["absent_in_musicbrainz"])
        self.assertEqual([], report(manifest)["errors"])
        self.assertEqual(10, report(manifest)["slots"]["type"]["reviewed"])
        self.assertIsNotNone(report(manifest)["metrics"])

    def test_c04_stray_kids_slash_name_is_known_musicbrainz_ep_not_recovered(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        c04 = next(item for item in manifest["cases"] if item["id"] == "C-04")
        self.assertTrue(c04["reviewed"])
        self.assertEqual(38526540, c04["discogs_release_id"])
        self.assertEqual("e248dce6-bef8-4d1f-a6d8-164de112151f",
                         c04["musicbrainz_group_mbid"])
        self.assertEqual("https://www.sonymusic.co.jp/artist/StrayKids/info/554372",
                         c04["evidence_links"]["independent_editorial"])
        self.assertFalse(c04["absent_in_musicbrainz"])
        self.assertFalse(c04["visible"])
        self.assertFalse(c04["match_correct"])
        self.assertEqual("candidate", c04["match_state"])
        result = report(manifest)
        self.assertEqual([], result["errors"])
        self.assertEqual(10, result["slots"]["source_only"]["reviewed"])
        self.assertIsNotNone(result["metrics"])

    def test_c05_daydream_repackage_is_existing_mb_release_inside_older_group(self):
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        case = next(x for x in manifest["cases"] if x["id"] == "C-05")
        self.assertTrue(case["reviewed"])
        self.assertEqual(32437206, case["discogs_release_id"])
        self.assertEqual("0c668bdb-328c-4dba-8407-8eddb5373a5d",
                         case["musicbrainz_group_mbid"])
        self.assertEqual("4c42b226-24c8-4481-91ce-744ae59eaa5b",
                         case["musicbrainz_release_mbid"])
        self.assertEqual("Album", case["musicbrainz_type"])
        self.assertEqual("Album", case["reference_type"])
        self.assertEqual("candidate", case["match_state"])
        self.assertFalse(case["absent_in_musicbrainz"])
        self.assertFalse(case["visible"])
        self.assertFalse(case["match_correct"])
        self.assertIn("ReleaseGroup", case["review_notes"])
        self.assertEqual("https://beliftlab.com/artist/discography/ENHYPEN/12",
                         case["evidence_links"]["independent_editorial"])
        result = report(manifest)
        self.assertEqual([], result["errors"])
        self.assertEqual(10, result["slots"]["source_only"]["reviewed"])
        self.assertIsNotNone(result["metrics"])

    def test_three_more_c_negative_revisions_have_source_and_mb_group_evidence(self):
        # Independent official announcements plus pre-existing exact MB RGs
        # disprove these apparent Discogs-only catalog gaps. None proves
        # the same physical Discogs pressing, user UI visibility or recovery.
        manifest = json.loads(Path(
            "docs/plans/catalog-211-discogs-corpus-manifest.json"
        ).read_text(encoding="utf-8"))
        expectations = {
            "C-06": ("14e1a869-8378-47df-818e-07dfe1d0c7fc",
                     28089088, "Single", "Single",
                     "https://www.universal-music.co.jp/lesserafim/news/2023-07-25-2/"),
            "C-07": ("cb79234e-c3d3-4973-a3e3-6ec603b3d1f4",
                     38302455, "Single", "Single",
                     "https://www.universal-music.co.jp/txt/news/2026-07-23/"),
            "C-08": ("4e219d4e-c5e0-4ed9-acec-b948c7319b37",
                     31008160, "EP", "Unknown",
                     "https://weverse.io/enhypen/notice/19177"),
        }
        for case_id, (group, discogs_release, mb_kind, ref_kind, source) in expectations.items():
            with self.subTest(case=case_id):
                item = next(x for x in manifest["cases"] if x["id"] == case_id)
                self.assertTrue(item["reviewed"])
                self.assertEqual(group, item["musicbrainz_group_mbid"])
                self.assertEqual(discogs_release, item["discogs_release_id"])
                self.assertEqual(mb_kind, item["musicbrainz_type"])
                self.assertEqual(ref_kind, item["reference_type"])
                self.assertEqual(source, item["evidence_links"]["independent_editorial"])
                self.assertEqual("candidate", item["match_state"])
                self.assertEqual("Unknown", item["app_type"])
                self.assertFalse(item["match_correct"])
                self.assertFalse(item["visible"])
                self.assertFalse(item["absent_in_musicbrainz"])
                self.assertGreaterEqual(len(item["review_notes"]), 80)
        result = report(manifest)
        self.assertEqual([], result["errors"])
        self.assertEqual(10, result["slots"]["source_only"]["reviewed"])
        self.assertFalse(result["complete"])
        self.assertIsNotNone(result["metrics"])

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

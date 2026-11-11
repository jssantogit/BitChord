"""Offline regressions for exhaustive, anonymous Master-version observations."""
import unittest
from catalog_211_discogs_master_versions_probe import (
    typed_master, versions, release_summary, investigate, observe, MAX_REQUESTS,
)

GROUP = "4e219d4e-c5e0-4ed9-acec-b948c7319b37"
RELEASE = "d0c0cfe6-0d31-469e-965a-3a5c18b1eb10"

def case():
    return {"id": "B-01", "musicbrainz_release_mbid": RELEASE,
            "musicbrainz_group_mbid": GROUP, "discogs_release_id": 102}

def group(relations=None):
    return {"id": GROUP, "relations": [] if relations is None else relations}

def link(url):
    return {"type": "discogs", "url": {"resource": url}}

def page(ids=(101, 102), pages=1, items=None):
    return {"pagination": {"page": 1, "pages": pages, "per_page": 8,
                           "items": len(ids) if items is None else items},
            "versions": [{"id": i} for i in ids]}

def detail(id, master=987):
    return {"id": id, "master_id": master, "formats": [{"name": "CD"}],
            "tracklist": [{"type_": "track", "title": "Magnetic"}],
            "identifiers": [{"type": "Barcode", "value": "8809704427821"}],
            "year": 2024, "country": "South Korea"}

def responses(*, many=False, wrong_detail=False, missing_main=False):
    data = {
        f"https://musicbrainz.org/ws/2/release-group/{GROUP}?fmt=json&inc=url-rels":
            group([link("https://www.discogs.com/master/987")]),
        "https://api.discogs.com/masters/987":
            {"id": 987, "main_release": 999 if missing_main else 101},
        "https://api.discogs.com/masters/987/versions?page=1&per_page=8":
            page(pages=2, items=9) if many else page(),
        "https://api.discogs.com/releases/101": detail(101),
        "https://api.discogs.com/releases/102": detail(102, 999 if wrong_detail else 987),
    }
    def get(url):
        if url not in data:
            raise AssertionError("Unexpected provider URL: " + url)
        return data[url]
    return get

class MasterVersionsProbeTest(unittest.TestCase):
    def test_exact_musicbrainz_group_and_safe_typed_master_relation(self):
        self.assertEqual(("typed_master_relation", 987), typed_master(group([
            link("https://www.discogs.com/master/987-edition")]), GROUP))
        self.assertEqual(("no_typed_master_relation", None), typed_master(group(), GROUP))
        self.assertEqual(("ambiguous_master_relations", None), typed_master(group([
            link("https://discogs.com/master/987"),
            link("https://www.discogs.com/master/988")]), GROUP))
        self.assertEqual(("no_typed_master_relation", None), typed_master(group([
            link("https://www.discogs.com/release/101")]), GROUP))
        for bad in ("https://www.discogs.com.evil.com/master/987",
                    "https://www.discogs.com/master/987?token=abc",
                    "https://x@www.discogs.com/master/987",
                    "https://www.discogs.com:8080/master/987",
                    "https://www.discogs.com/unknown/987"):
            self.assertEqual("invalid_musicbrainz_relations", typed_master(
                group([link(bad)]), GROUP)[0])
        self.assertEqual("invalid_musicbrainz_group",
                         typed_master({"id": "wrong", "relations": []}, GROUP)[0])

    def test_version_listing_is_never_silently_truncated(self):
        self.assertEqual(("complete_bounded_versions", [101, 102]), versions(page()))
        self.assertEqual("master_too_large_not_exhaustive",
                         versions(page(pages=2, items=9))[0])
        self.assertEqual("invalid_versions", versions(page(ids=(101, 101)))[0])
        self.assertEqual("invalid_versions", versions(page(items=3))[0])
        self.assertEqual("invalid_versions", versions(page(items=True))[0])

    def test_complete_public_versions_are_observations_not_verified(self):
        row = investigate(case(), responses())
        self.assertEqual("all_versions_observed_not_adjudicated", row["status"])
        self.assertTrue(row["selected_discogs_release_in_versions"])
        self.assertEqual([101, 102], [x["discogs_release_id"] for x in row["editions"]])
        self.assertEqual(0, row["verified_edition_count"])
        self.assertFalse(row["reviewed_by_this_probe"])

    def test_large_master_main_release_mismatch_or_bad_detail_are_inconclusive(self):
        self.assertEqual("master_too_large_not_exhaustive",
                         investigate(case(), responses(many=True))["status"])
        self.assertEqual("main_release_missing_from_complete_list",
                         investigate(case(), responses(missing_main=True))["status"])
        self.assertEqual("incomplete_release_hydration",
                         investigate(case(), responses(wrong_detail=True))["status"])
        self.assertIsNone(release_summary(detail(101, master=999), 101, 987))

    def test_all_five_controls_use_a_shared_request_budget_and_no_new_reviews(self):
        row = case()
        manifest = {"schema_version": 1, "cases": [
            {"id": f"A-{i:02}", "cohort": "type", "reviewed": False}
            for i in range(1, 11)
        ] + [
            {"id": f"B-{i:02}", "cohort": "unlinked", "reviewed": True,
             **{k: v for k, v in row.items() if k != "id"}}
            for i in range(1, 6)
        ] + [
            {"id": f"B-{i:02}", "cohort": "unlinked", "reviewed": False}
            for i in range(6, 11)
        ] + [
            {"id": f"C-{i:02}", "cohort": "source_only", "reviewed": False}
            for i in range(1, 11)
        ]}
        result = observe(manifest, responses())
        self.assertEqual(25, result["source_http_requests"])
        self.assertLessEqual(result["source_http_requests"], MAX_REQUESTS)
        self.assertEqual(0, result["reviewed_corpus_cases"])
        self.assertEqual(0, result["verified_edition_count"])

if __name__ == "__main__":
    unittest.main()

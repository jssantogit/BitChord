"""Offline guardrails for bounded RG-to-Master scouting, not adjudication."""
import unittest

from catalog_211_discogs_master_lead_scout import (
    ARTIST_LIMIT, MASTER_LIMIT, REQUEST_LIMIT, browse_page, scout,
)

A = "6b128dd7-e998-4edb-a4f5-400fef190445"
B = "95663592-1447-4579-90f7-c94733476667"
G = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
H = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
EXISTING = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
RELEASE = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

def relation(master):
    return {"type": "discogs",
            "url": {"resource": f"https://www.discogs.com/master/{master}"}}

def row(gid=G, master=500, artist=A, title="A real album", kind="Album"):
    return {"id": gid, "title": title, "primary-type": kind,
            "artist-credit": [{"artist": {"id": artist}, "name": "Artist"}],
            "relations": [relation(master)] if master is not None else []}

def page(rows, total=None):
    return {"release-group-offset": 0, "release-group-count": (
        len(rows) if total is None else total),
        "release-groups": rows}

def url_artist(a):
    return ("https://musicbrainz.org/ws/2/release-group/?artist=" + a +
            "&fmt=json&limit=25&offset=0&inc=artist-credits%2Burl-rels")

def url_master(mid):
    return f"https://api.discogs.com/masters/{mid}"

def url_versions(mid):
    return f"https://api.discogs.com/masters/{mid}/versions?page=1&per_page=8"

def listing(ids):
    return {"pagination": {"page": 1, "pages": 1, "per_page": 8, "items": len(ids)},
            "versions": [{"id": i} for i in ids]}

def fixtures(*, conflicting=False, large=False):
    payloads = {
        url_artist(A): page([row(), row(H, 501)]),
        url_artist(B): page([row(H, 500, B)] if conflicting else []),
        url_master(500): {"id": 500, "main_release": 900},
        url_master(501): {"id": 501, "main_release": 901},
        url_versions(500): ({"pagination": {
            "page": 1, "pages": 2, "per_page": 8, "items": 11},
            "versions": [{"id": 900}]} if large else listing([900, 902])),
        url_versions(501): listing([901]),
    }
    def get(url):
        if url not in payloads:
            raise AssertionError("Unapproved source request: " + url)
        return payloads[url]
    return get

class MasterLeadScoutTest(unittest.TestCase):
    def test_browse_must_keep_exact_credit_and_typed_relation(self):
        rows, meta = browse_page(page([
            row(), row(H, 501, artist=B),
            row("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", 502, kind="Other"),
            row("ffffffff-ffff-4fff-8fff-ffffffffffff", None),
        ]), A)
        self.assertEqual([(G, 500)], [(x["group_mbid"], x["master_id"]) for x in rows])
        self.assertEqual(4, meta["group_rows_seen"])
        self.assertEqual(1, meta["typed_master_rows"])
        self.assertFalse(meta["exhaustive_artist_catalog"])

    def test_bad_browse_cannot_be_used_to_select_leads(self):
        invalid = [
            {"release-group-offset": 1, "release-group-count": 1,
             "release-groups": [row()]},
            page([row(), row()]),
            page([row()], total=0),
            {"release-group-offset": 0, "release-group-count": True,
             "release-groups": [row()]},
            page([dict(row(), relations=None)]),
            page([row()], total=9999),
        ]
        for raw in invalid:
            with self.subTest(raw=raw):
                self.assertEqual("invalid_browse", browse_page(raw, A)[1]["status"])

    def test_known_b_groups_are_not_reselected_and_no_claims_are_made(self):
        report = scout([("Artist A", A), ("Artist B", B)], {G}, fixtures())
        self.assertEqual([], [x for x in report["leads"] if x["group_mbid"] == G])
        self.assertEqual(1, len(report["leads"]))
        self.assertEqual(H, report["leads"][0]["group_mbid"])
        self.assertEqual("complete_small_master_candidate", report["leads"][0]["status"])
        self.assertEqual([901], report["leads"][0]["version_ids"])
        self.assertEqual(0, report["reviewed_corpus_cases"])
        self.assertEqual(0, report["verified_edition_count"])
        self.assertEqual(4, report["source_http_requests"])

    def test_master_more_than_eight_is_never_eligible_for_exact_match(self):
        report = scout([("Artist A", A)], set(), fixtures(large=True))
        self.assertEqual(2, len(report["leads"]))
        self.assertEqual("master_too_large_not_exhaustive", report["leads"][0]["status"])
        self.assertEqual([], report["leads"][0]["version_ids"])
        self.assertEqual("complete_small_master_candidate", report["leads"][1]["status"])

    def test_different_groups_with_one_master_cannot_be_unique_leads(self):
        report = scout([("Artist A", A), ("Artist B", B)], set(),
                       fixtures(conflicting=True))
        self.assertEqual(3, len(report["leads"]))
        self.assertTrue(all(x["status"] == "master_shared_across_groups"
                            for x in report["leads"] if x["master_id"] == 500))
        self.assertTrue(all(x["status"] == "master_shared_across_groups"
                            for x in report["leads"]))
        self.assertFalse(any(x["status"] == "complete_small_master_candidate"
                             for x in report["leads"]))

    def test_later_source_rows_cannot_be_truncated_before_master_conflict_check(self):
        # 102 source observations: the last RG contradicts an earlier
        # Master. Scanning only the first 100 would fabricate uniqueness.
        artist_ids = [f"{i:08x}-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                      for i in range(1, 7)]
        pages = {}
        for index, artist in enumerate(artist_ids):
            if index == 0:
                rows = [row(G, 500, artist, title="AAA")]
            elif index == 5:
                rows = [row(H, 500, artist, title="ZZZ")]
            else:
                rows = [
                    row(f"{index:08x}-{k:04x}-4aaa-8aaa-aaaaaaaaaaaa",
                        1_000 + index * 25 + k, artist,
                        title=f"Filler {k:02}")
                    for k in range(25)
                ]
            pages[url_artist(artist)] = page(rows)

        def get(url):
            if url in pages:
                return pages[url]
            if "/versions?" in url:
                return listing([900])
            if "/masters/" in url:
                mid = int(url.split("/")[-1])
                return {"id": mid, "main_release": 900}
            raise AssertionError(url)

        report = scout([(str(i), mbid) for i, mbid in enumerate(artist_ids)],
                       set(), get)
        early = next(x for x in report["leads"] if x["master_id"] == 500)
        self.assertEqual("master_shared_across_groups", early["status"])
        self.assertLessEqual(report["source_http_requests"], REQUEST_LIMIT)

    def test_fail_closed_on_provider_error_and_global_budget(self):
        def unavailable(url):
            if url == url_artist(A):
                raise OSError("provider down")
            return fixtures()(url)
        report = scout([("Artist A", A), ("Artist B", B)], set(), unavailable)
        self.assertEqual(1, report["source_http_requests"])
        self.assertEqual("source_unavailable", report["artists"][0]["status"])
        self.assertEqual(0, report["verified_edition_count"])
        self.assertEqual(0, report["reviewed_corpus_cases"])
        self.assertLessEqual(ARTIST_LIMIT, 10)
        self.assertLessEqual(MASTER_LIMIT, 5)
        self.assertLessEqual(report["source_http_requests"], REQUEST_LIMIT)

if __name__ == "__main__":
    unittest.main()

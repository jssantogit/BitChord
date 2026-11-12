"""Offline tests for bounded Discogs artist index page-2 scouting."""
import unittest

from catalog_211_discogs_c_second_page_probe import scout_second_pages, ARTISTS

def first_index():
    return {"schema_version": 1, "artists": [
        {"artist": name, "musicbrainz_artist_mbid": mbid,
         "status": "index_compared_not_adjudicated",
         "discogs_artist_id": 1000 + i,
         "candidate_index_hints": [],
         "pages_checked": [1]} for i,(name,mbid) in enumerate(ARTISTS)
    ]}

def discogs_page(page, title, rid):
    return {"pagination": {"page":page, "pages":3},
            "releases":[{"id":rid, "title":title, "role":"Main", "type":"release"}]}

class SecondPageTest(unittest.TestCase):
    def test_batch_at_most_ten_public_gets_with_no_editorial_claims(self):
        urls=[]
        def get(url):
            urls.append(url)
            self.assertIn("?page=2&per_page=12&sort=year&sort_order=desc",url)
            aid=int(url.split("/artists/")[1].split("/")[0])
            return discogs_page(2, f"Second page {aid}", aid+10000)
        output=scout_second_pages(first_index(),get)
        self.assertLessEqual(len(urls),10)
        self.assertEqual(len(urls),output["http_requests"])
        self.assertEqual(0,output["reviewed_corpus_cases"])
        self.assertEqual(0,output["confirmed_source_only_cases"])
        self.assertEqual(0,output["verified_edition_count"])
        self.assertTrue(all(row["status"]=="page2_observed_not_adjudicated"
                            and all(x["source_kind"]=="release" for x in row["hints"])
                            for row in output["artists"]))

    def test_invalid_page2_rejected_without_release_hints(self):
        index=first_index()
        index["artists"][0]["discogs_artist_id"]=False
        def get(url):
            return discogs_page(1,"Wrong page",100)
        output=scout_second_pages(index,get)
        self.assertEqual("invalid_source_identity",output["artists"][0]["status"])
        self.assertEqual([],output["artists"][0]["hints"])
        self.assertEqual([],output["artists"][1]["hints"])
        self.assertEqual("invalid_discogs_page2",output["artists"][1]["status"])

    def test_malformed_index_refused_before_network(self):
        broken=first_index()
        broken["artists"][1]["musicbrainz_artist_mbid"]=ARTISTS[0][1]
        calls=[]
        with self.assertRaises(ValueError):
            scout_second_pages(broken,lambda u:calls.append(u))
        self.assertEqual([],calls)

if __name__=="__main__":
    unittest.main()

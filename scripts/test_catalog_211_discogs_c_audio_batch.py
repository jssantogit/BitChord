"""C review queue: separate page-2 audio leads from duplicate B/C pressings."""
import unittest
from catalog_211_discogs_c_audio_batch import inspect_audio_batch, AUDIO_LEADS
from catalog_211_discogs_corpus_candidates import ARTISTS

ARTIST_IDS = {"IVE":7122521, "NMIXX":10946579,
              "LE SSERAFIM":11171795, "aespa":8724412}

def page2_index():
    artists=[]
    for name,mbid in ARTISTS:
        rows=[{"source_kind":"release","source_id":did,"title":"Audio Title",
               "url":f"https://www.discogs.com/release/{did}"}
              for artist,did in AUDIO_LEADS if artist==name]
        artists.append({"artist":name,"musicbrainz_artist_mbid":mbid,
                        "status":"page2_observed_not_adjudicated" if name in ARTIST_IDS else "invalid_source_identity",
                        "hints":rows})
    return {"schema_version":1,"artists":artists}

def primary_index():
    return {"schema_version":1,"artists":[
        {"artist":name,"musicbrainz_artist_mbid":mbid,
         "discogs_artist_id":ARTIST_IDS.get(name),
         "status":"index_compared_not_adjudicated" if name in ARTIST_IDS else "no_unique_artist_relation"}
        for name,mbid in ARTISTS]}

def get(url):
    did=int(url.rsplit("/",1)[1])
    found=next(a for a,d in AUDIO_LEADS if d==did)
    return {"id":did,"title":"Audio Title",
            "artists":[{"id":ARTIST_IDS[found],"name":found}],
            "formats":[{"name":"File","descriptions":["MP3","Single"]}],
            "tracklist":[{"type_":"track","title":"Track"}],
            "labels":[{"name":"Source label"}],"identifiers":[],
            "year":2024}

class AudioBatchTest(unittest.TestCase):
    def test_seven_distinct_live_hydrations_never_adjudicate(self):
        calls=[]
        def checked(url):
            calls.append(url)
            return get(url)
        result=inspect_audio_batch(page2_index(),primary_index(),
                                   reviewed_source_ids={37110012,34121731,37253067},get=checked)
        self.assertEqual(len(AUDIO_LEADS),len(calls))
        self.assertEqual(len(AUDIO_LEADS),result["provider_requests"])
        self.assertEqual(len(AUDIO_LEADS),len(result["observations"]))
        self.assertEqual(set(did for _,did in AUDIO_LEADS),
                         {x["discogs_release_id"] for x in result["observations"]})
        self.assertTrue(all(x["status"]=="edition_metadata_observed_not_adjudicated"
                            and x["artist_id_matches_relation"] for x in result["observations"]))
        self.assertEqual(0,result["reviewed_corpus_cases"])
        self.assertEqual(0,result["verified_edition_count"])
        self.assertEqual(0,result["confirmed_source_only_cases"])

    def test_known_b_released_id_must_not_be_hydrated_or_claimed_as_c(self):
        blocked={did for _,did in AUDIO_LEADS}
        seen=[]
        result=inspect_audio_batch(page2_index(),primary_index(),
                                   blocked,lambda url:seen.append(url))
        self.assertEqual([],seen)
        self.assertEqual(0,result["provider_requests"])
        self.assertTrue(all(x["status"]=="already_reviewed_pressing" for x in result["observations"]))

    def test_malformed_artist_identity_cannot_trigger_network(self):
        index=primary_index()
        index["artists"][0]["discogs_artist_id"]=False
        called=[]
        output=inspect_audio_batch(page2_index(),index,set(),lambda url:called.append(url))
        self.assertLess(len(called),len(AUDIO_LEADS))
        self.assertTrue(all(item.get("reviewed") is not True for item in output["observations"]))
        bad=page2_index()
        bad["artists"][0]["musicbrainz_artist_mbid"]=ARTISTS[1][1]
        with self.assertRaises(ValueError):
            inspect_audio_batch(bad,primary_index(),set(),lambda url:called.append(url))

if __name__=="__main__":
    unittest.main()

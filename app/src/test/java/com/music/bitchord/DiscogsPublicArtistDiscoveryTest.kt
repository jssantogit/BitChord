package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsPublicArtistDiscoveryTest {
    private val mbid = "95663592-1447-4579-90f7-c94733476667"

    private fun relation(id: Long = 8303009L): String =
        """{"id":"$mbid","relations":[{
            "type":"discogs",
            "url":{"resource":"https://www.discogs.com/artist/$id-ENHYPEN"}
        }]}"""

    @Test fun onlyExactMusicBrainzArtistRelationCanAnchorAnonymousListing() {
        assertEquals(8303009L,
            DiscogsPublicArtistParsing.artistId(mbid, relation()))
        assertNull(DiscogsPublicArtistParsing.artistId(
            "00000000-0000-4000-8000-000000000001", relation()))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            relation().replace("www.discogs.com", "evil.example")))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            relation().replace("/artist/8303009", "/release/8303009")))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            """{"id":"$mbid","relations":[
                {"type":"discogs","url":{"resource":"https://discogs.com/artist/11"}},
                {"type":"discogs","url":{"resource":"https://discogs.com/artist/12"}}
            ]}"""))
    }

    @Test fun pageUsesMainRoleAndTypedMasterReleaseIdsWithStrictPagination() {
        val page = DiscogsPublicArtistParsing.page("""
            {"pagination":{"page":1,"pages":3},"releases":[
              {"id":10,"type":"master","role":"Main","title":"Album"},
              {"id":11,"type":"release","role":"Main","title":"Single"},
              {"id":12,"type":"release","role":"Producer","title":"Credit"},
              {"id":13,"type":"master","role":"Appearance"},
              {"id":10,"type":"master","role":"Main"}
            ]}""", 1)
        assertNotNull(page)
        assertEquals(listOf(
            DiscogsArtistIndexEntry(DiscogsKind.MASTER, 10, "Album"),
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 11, "Single"),
        ), page?.entries)
        assertEquals(3, page?.totalPages)
        assertNull(DiscogsPublicArtistParsing.page(
            """{"pagination":{"page":9,"pages":2},"releases":[]}""", 1))
        assertNull(DiscogsPublicArtistParsing.page(
            """{"pagination":{"page":1,"pages":3},"releases":null}""", 1))
    }

    @Test fun anonymousLinkedArtistCanRecoverTypedMetadataWithoutSearchToken() = runBlocking {
        val requested = mutableListOf<Int>()
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String {
                assertEquals("artist/$mbid", path)
                return relation()
            }
        })
        val artist = object : DiscogsPublicArtistTransport {
            override suspend fun list(
                artistId: Long, page: Int, userAgent: String,
            ): DiscogsHttpResponse {
                assertEquals(8303009L, artistId)
                requested += page
                return DiscogsHttpResponse(200, """{
                    "pagination":{"page":1,"pages":1},
                    "releases":[{"id":12001,"type":"release","role":"Main",
                      "title":"NOT ON MUSICBRAINZ"}]}""")
            }
        }
        val editions = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                assertEquals("releases/12001", path)
                return DiscogsHttpResponse(200, """{
                    "id":12001,"title":"NOT ON MUSICBRAINZ",
                    "artists":[{"name":"ENHYPEN"}],
                    "country":"South Korea","year":2026,
                    "labels":[{"name":"BELIFT LAB","catno":"ABC-1"}],
                    "formats":[{"name":"CD","descriptions":["EP"]}],
                    "tracklist":[{"position":"1","title":"Song One"}]
                }""")
            }
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        val discovery = DiscogsPublicArtistDiscovery(
            musicBrainz = mb, transport = artist, editions = editions,
            enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            nowMillis = { 1_000L },
        )
        val recovered = discovery.findForArtist(
            musicBrainzArtistMbid = mbid,
            artistName = "ENHYPEN",
            completedMusicBrainzTitles = setOf("KNOWN TITLE"),
        )
        assertEquals(listOf(1), requested)
        assertEquals(listOf("NOT ON MUSICBRAINZ"), recovered.map { it.title })
        assertEquals(DiscogsEditorialShelf.EPS, recovered.single().shelf)
        assertNull(recovered.single().musicBrainzId)
        assertFalse(recovered.single().canPlay)
    }

    @Test fun noExactArtistRelationMeansNoAnonymousDiscogsHttpRequest() = runBlocking {
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String = """{"id":"$mbid","relations":[]}"""
        })
        val discovery = DiscogsPublicArtistDiscovery(
            musicBrainz = mb,
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(
                    artistId: Long, page: Int, userAgent: String,
                ): DiscogsHttpResponse = error("No artist relation: no public Discogs request")
            },
            enabled = true,
        )
        assertTrue(discovery.findForArtist(mbid, "ENHYPEN", emptySet()).isEmpty())
    }

    @Test fun withoutExactArtistLinkNoCatalogDiscoveryShouldBeAuthorized() {
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            """{"id":"$mbid","relations":[]}"""))
        assertNull(DiscogsPublicArtistParsing.artistId("local:artist", relation()))
        assertNull(DiscogsPublicArtistParsing.artistId(mbid,
            """{"id":"$mbid","relations":[{
                "type":"discogs","ended":true,
                "url":{"resource":"https://discogs.com/artist/8303009"}
            }]}"""))
    }
}

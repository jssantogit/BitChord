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

    @Test fun duplicatePublicIndexIdKeepsFirstNamedTitleOrEnrichesEmptyTitle() {
        val page = DiscogsPublicArtistParsing.page("""
            {"pagination":{"page":1,"pages":1},"releases":[
              {"id":50,"type":"master","role":"Main","title":""},
              {"id":50,"type":"master","role":"Main","title":"Named Master"},
              {"id":50,"type":"master","role":"Main","title":""},
              {"id":60,"type":"release","role":"Main","title":"First Name"},
              {"id":60,"type":"release","role":"Main","title":"Other Name"}
            ]}""", 1)
        assertEquals(listOf(
            DiscogsArtistIndexEntry(DiscogsKind.MASTER, 50, "Named Master"),
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 60, "First Name"),
        ), page?.entries)
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
                    "artists":[{"id":8303009,"name":"ENHYPEN (12)"}],
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
        assertEquals("ENHYPEN", recovered.single().artist)
        assertNull(recovered.single().musicBrainzId)
        assertFalse(recovered.single().canPlay)
    }

    @Test fun anonymousIndexChecksSecondPageOnlyWhenFirstIsAlreadyInMusicBrainz() = runBlocking {
        val requested = mutableListOf<Int>()
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String = relation()
        })
        val discovery = DiscogsPublicArtistDiscovery(
            musicBrainz = mb,
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int,
                    userAgent: String): DiscogsHttpResponse {
                    requested += page
                    return DiscogsHttpResponse(200, """{
                        "pagination":{"page":$page,"pages":2},
                        "releases":[{"id":${if (page == 1) 9101 else 9102},
                            "type":"release","role":"Main",
                            "title":"${if (page == 1) "KNOWN TITLE" else "MISSING EP"}"}]}""")
                }
            },
            editions = DiscogsClient(transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    assertEquals("releases/9102", path)
                    return DiscogsHttpResponse(200, """{
                        "id":9102,"title":"MISSING EP","artists":[{"id":8303009,"name":"ENHYPEN"}],
                        "country":"South Korea","year":2026,
                        "labels":[{"name":"BELIFT LAB","catno":"BC-2"}],
                        "formats":[{"name":"CD","descriptions":["EP"]}],
                        "tracklist":[{"position":"1","title":"Different Song"}]}""")
                }
            }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L)),
            enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            nowMillis = { 1_000L },
        )
        val results = discovery.findForArtist(mbid, "ENHYPEN", setOf("KNOWN TITLE"))
        assertEquals(listOf(1, 2), requested)
        assertEquals(listOf("MISSING EP"), results.map { it.title })
    }

    @Test fun identicalArtistNameButWrongProviderIdCannotAuthorizeSourceOnly() = runBlocking {
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String = relation()
        })
        val discovery = DiscogsPublicArtistDiscovery(
            musicBrainz = mb,
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int,
                    userAgent: String): DiscogsHttpResponse = DiscogsHttpResponse(200, """{
                        "pagination":{"page":1,"pages":1},
                        "releases":[{"id":54321,"type":"release","role":"Main","title":"NEW"}]}""")
            },
            editions = DiscogsClient(transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse =
                    DiscogsHttpResponse(200, """{
                        "id":54321,"title":"NEW","artists":[{"id":8303010,"name":"ENHYPEN"}],
                        "country":"South Korea","year":2026,
                        "labels":[{"name":"Label"}],
                        "formats":[{"name":"CD","descriptions":["EP"]}],
                        "tracklist":[{"position":"1","title":"Track"}]}""")
            }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L)),
            enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            nowMillis = { 1_000L },
        )
        assertTrue(discovery.findForArtist(mbid, "ENHYPEN", emptySet()).isEmpty())
    }

    @Test fun knownPunctuationTitleSkipsHydrationAndChecksSecondPage() = runBlocking {
        val mb = MusicBrainzClient(transport = object : MusicBrainzTransport {
            override suspend fun get(path: String, parameters: Map<String, String>,
                userAgent: String): String = relation()
        })
        val pages = mutableListOf<Int>()
        val fetched = mutableListOf<String>()
        val discovery = DiscogsPublicArtistDiscovery(
            musicBrainz = mb,
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int,
                    userAgent: String): DiscogsHttpResponse {
                    pages += page
                    val sourceId = if (page == 1) 900 else 999
                    val title = if (page == 1) "EASY-CRAZY-HOT" else "NEW EP"
                    return DiscogsHttpResponse(200, """{
                        "pagination":{"page":$page,"pages":2},
                        "releases":[{"id":$sourceId,"type":"release","role":"Main",
                            "title":"$title"}]}""")
                }
            },
            editions = DiscogsClient(transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    fetched += path
                    return DiscogsHttpResponse(200, """{
                        "id":999,"title":"NEW EP",
                        "artists":[{"id":8303009,"name":"ENHYPEN (12)"}],
                        "country":"South Korea","year":2026,
                        "labels":[{"name":"Label"}],
                        "formats":[{"name":"CD","descriptions":["EP"]}],
                        "tracklist":[{"position":"1","title":"One"}]}""")
                }
            }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L)),
            enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            nowMillis = { 1_000L },
        )
        val releases = discovery.findForArtist(mbid, "ENHYPEN", setOf("EASY CRAZY HOT"))
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf("releases/999"), fetched)
        assertEquals(listOf("NEW EP"), releases.map { it.title })
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

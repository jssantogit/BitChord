package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Discogs catalog mode is source-owned, never a list of allegedly absent MB
 * releases. This is a non-playable backend slice, not a UI mode yet.
 */
class DiscogsStandaloneCatalogTest {
    private val artistId = 8303009L

    private fun record(
        releaseId: Long, title: String = "HOT", masterId: Long? = 555L,
        ids: List<Long> = listOf(artistId), unofficial: Boolean = false,
        year: Int? = 2025,
    ) = DiscogsRecord(
        link = DiscogsLink(DiscogsKind.RELEASE, releaseId),
        title = title,
        artistNames = listOf("LE SSERAFIM"),
        country = "KR",
        year = year,
        labels = listOf("Source Music" to ""),
        barcodes = emptyList(),
        formats = listOf("CD", "EP"),
        tracklist = listOf("1" to "HOT"),
        unofficial = unofficial,
        masterId = masterId,
        artistIds = ids,
    )

    @Test fun standaloneIncludesKnownMusicBrainzTitlesWithoutInventingMbidsOrPlayback() {
        // "HOT" also exists in MusicBrainz. Standalone cannot filter it away
        // and does not even accept a MusicBrainz catalog as an input.
        val result = DiscogsStandaloneProjection.project(
            artistId, listOf(record(11)))
        assertEquals(1, result.size)
        assertEquals("HOT", result.single().title)
        assertEquals("discogs:release:11", result.single().browseId)
        assertEquals("https://www.discogs.com/release/11", result.single().attributionUrl)
        assertEquals(DiscogsEditorialKind.EP, result.single().kind)
        assertNull(result.single().musicBrainzId)
        assertFalse(result.single().canPlay)
    }

    @Test fun editionsWithSameMasterAreNotFusedAndUnknownDatesRemainUnknown() {
        val result = DiscogsStandaloneProjection.project(
            artistId, listOf(record(11), record(12, year = null), record(11)))
        assertEquals(2, result.size)
        assertEquals(setOf("discogs:release:11", "discogs:release:12"),
            result.map { it.browseId }.toSet())
        assertEquals(setOf(555L), result.mapNotNull { it.masterId }.toSet())
        assertNull(result.single { it.releaseId == 12L }.year)
    }

    @Test fun typedArtistIdentityIsRequiredAndUnofficialEditionsStayLabeled() {
        assertTrue(DiscogsStandaloneProjection.project(
            artistId, listOf(record(31, ids = emptyList()),
                record(32, ids = listOf(44L)))).isEmpty())
        val included = DiscogsStandaloneProjection.project(
            artistId, listOf(record(33, unofficial = true)))
        assertEquals(true, included.single().unofficial)
        assertFalse(included.single().canPlay)
    }

    @Test fun anonymousDirectDiscogsArtistBrowseNeverCallsMusicBrainz() = runBlocking {
        val calls = mutableListOf<String>()
        val index = object : DiscogsPublicArtistTransport {
            override suspend fun list(artistId: Long, page: Int, userAgent: String):
                DiscogsHttpResponse {
                assertEquals(8303009L, artistId)
                calls += "index:$page"
                return DiscogsHttpResponse(200, """{
                    "pagination":{"page":1,"pages":2},
                    "releases":[{"id":11,"type":"release","role":"Main","title":"HOT"}]
                }""")
            }
        }
        val editionTransport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                calls += path
                return DiscogsHttpResponse(200, """{
                    "id":11,"title":"HOT","artists":[{"id":8303009,"name":"LE SSERAFIM"}],
                    "country":"South Korea","year":2025,
                    "labels":[{"name":"Source Music","catno":"SM-1"}],
                    "formats":[{"name":"CD","descriptions":["EP"]}],
                    "tracklist":[{"position":"1","title":"HOT"}],
                    "master_id":555
                }""")
            }
        }
        val page = DiscogsStandaloneArtistCatalog(
            index = index,
            editions = DiscogsClient(transport = editionTransport, enabled = true,
                limiter = DiscogsRateLimiter(minimumIntervalMs = 0L)),
            enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        ).browse(artistId, 1)
        assertTrue(page is DiscogsStandaloneArtistResult.Found)
        page as DiscogsStandaloneArtistResult.Found
        assertEquals(1, page.releases.size)
        assertEquals(2, page.nextPage)
        assertFalse(page.partial)
        assertEquals(listOf("index:1", "releases/11"), calls)
    }

    @Test fun incompleteHydrationIsVisibleAndNeverMistakenForEmptyCatalog() = runBlocking {
        val index = object : DiscogsPublicArtistTransport {
            override suspend fun list(artistId: Long, page: Int, userAgent: String):
                DiscogsHttpResponse = DiscogsHttpResponse(200, """{
                    "pagination":{"page":1,"pages":1},
                    "releases":[{"id":11,"type":"release","role":"Main","title":"HOT"}]
                }""")
        }
        val editions = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse =
                DiscogsHttpResponse(503, "")
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        val result = DiscogsStandaloneArtistCatalog(
            index = index, editions = editions, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        ).browse(artistId, 1)
        assertTrue(result is DiscogsStandaloneArtistResult.Found)
        result as DiscogsStandaloneArtistResult.Found
        assertTrue(result.partial)
        assertTrue(result.releases.isEmpty())
    }

    @Test fun disabledAndBadArtistIdsNeverQueryProviders() = runBlocking {
        var calls = 0
        val index = object : DiscogsPublicArtistTransport {
            override suspend fun list(artistId: Long, page: Int, userAgent: String):
                DiscogsHttpResponse {
                calls++
                return DiscogsHttpResponse(200, "{}")
            }
        }
        val disabled = DiscogsStandaloneArtistCatalog(index = index)
        assertEquals(DiscogsStandaloneArtistResult.Disabled, disabled.browse(artistId, 1))
        val enabled = DiscogsStandaloneArtistCatalog(index = index, enabled = true)
        assertEquals(DiscogsStandaloneArtistResult.InvalidRequest, enabled.browse(-1, 1))
        assertEquals(DiscogsStandaloneArtistResult.InvalidRequest, enabled.browse(artistId, 4))
        assertEquals(0, calls)
    }
}

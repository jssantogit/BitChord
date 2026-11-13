package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistHttp
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistTransport
import com.music.bitchord.data.catalog.metadata.ListenBrainzRateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Public playlist calls can be slow. Duplicate active requests must not fill
 * the shared upstream rate-limit queue before the 10/30-minute cache exists. */
class ListenBrainzPublicPlaylistSingleFlightTest {
    private val playlistId = "11111111-1111-4111-8111-111111111111"

    @Test fun simultaneousExactTextSearchesUseOneWideRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun searchWide(query: String): ListenBrainzPublicPlaylistHttp {
                    calls++
                    started.complete(Unit)
                    release.await()
                    return ListenBrainzPublicPlaylistHttp(200,
                        """{"playlists":[{"playlist_mbid":"$playlistId",
                            "title":"Best K-pop songs","public":true}]}""")
                }
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            client.searchPublicQuery("K-pop")
        }
        withTimeout(2_000L) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            client.searchPublicQuery(" k-POP ")
        }
        release.complete(Unit)
        assertEquals(listOf(playlistId), withTimeout(2_000L) { first.await() }.map { it.mbid })
        assertEquals(listOf(playlistId), withTimeout(2_000L) { second.await() }.map { it.mbid })
        assertEquals(1, calls)
    }

    @Test fun simultaneousDetailLookupsShareOneJspfRequestAndCacheResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val selected = ListenBrainzPublicPlaylist(playlistId, "K-pop", "Curator", null)
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp {
                    calls++
                    started.complete(Unit)
                    release.await()
                    return ListenBrainzPublicPlaylistHttp(200,
                        """{"playlist":{"identifier":"https://listenbrainz.org/playlist/$playlistId",
                            "track":[]}}""")
                }
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) { client.detail(selected) }
        withTimeout(2_000L) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { client.detail(selected) }
        release.complete(Unit)
        assertNotNull(withTimeout(2_000L) { first.await() })
        assertNotNull(withTimeout(2_000L) { second.await() })
        assertNotNull(client.detail(selected))
        assertEquals(1, calls)
    }

    @Test fun failedDetailMayRetryAfterFirstRequestFinishes() = runBlocking {
        var requests = 0
        val selected = ListenBrainzPublicPlaylist(playlistId, "K-pop", "Curator", null)
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun detail(mbid: String): ListenBrainzPublicPlaylistHttp {
                    requests++
                    return if (requests == 1) ListenBrainzPublicPlaylistHttp(503, "")
                        else ListenBrainzPublicPlaylistHttp(200,
                            """{"playlist":{"track":[]}}""")
                }
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertNull(client.detail(selected))
        assertNotNull(client.detail(selected))
        assertEquals(2, requests)
    }
    @Test fun returningToHomeAndGenreReusesVerifiedPublicDiscovery() = runBlocking {
        var now = 10_000L
        val queries = mutableListOf<String>()
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    queries += query
                    return ListenBrainzPublicPlaylistHttp(200,
                        """{"playlists":[{"playlist_mbid":"$playlistId",
                            "title":"Classic rock essentials","public":true}]}""")
                }
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = {}),
            nowMillis = { now },
        )
        assertEquals(1, client.searchHome()!!.size)
        assertEquals(1, client.searchHome()!!.size)
        assertEquals(1, client.searchGenre("rock")!!.size)
        assertEquals(1, client.searchGenre("rock")!!.size)
        assertEquals(listOf("essentials", "rock"), queries)
        now += 5 * 60_000L + 1L
        assertEquals(1, client.searchHome()!!.size)
        assertEquals(listOf("essentials", "rock", "essentials"), queries)
    }

    @Test fun concurrentSameHomeDiscoveryDoesNotQueueDuplicateUpstreamWork() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    calls++
                    started.complete(Unit)
                    release.await()
                    return ListenBrainzPublicPlaylistHttp(200,
                        """{"playlists":[{"playlist_mbid":"$playlistId",
                            "title":"Classic rock essentials","public":true}]}""")
                }
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 10_000L }, waitMillis = {}),
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) { client.searchHome() }
        withTimeout(2_000L) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { client.searchHome() }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    @Test fun unavailableHomeDiscoveryDoesNotPoisonCache() = runBlocking {
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String): ListenBrainzPublicPlaylistHttp {
                    calls++
                    return if (calls <= 3) ListenBrainzPublicPlaylistHttp(503, "")
                        else ListenBrainzPublicPlaylistHttp(200,
                            """{"playlists":[{"playlist_mbid":"$playlistId",
                                "title":"Classic rock essentials","public":true}]}""")
                }
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
            },
            ListenBrainzRateLimiter(nowMillis = { 10_000L }, waitMillis = {}),
        )
        assertNull(client.searchHome())
        assertEquals(1, client.searchHome()!!.size)
        assertEquals(4, calls)
    }


    @Test fun repeatedRecordingArtworkLookupsReuseExactVerifiedBatchDespiteOrder() = runBlocking {
        val id1 = "33333333-3333-4333-8333-333333333333"
        val id2 = "44444444-4444-4444-8444-444444444444"
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
                override suspend fun recordingArtwork(
                    recordingMbids: List<String>,
                ): ListenBrainzPublicPlaylistHttp {
                    calls++
                    return ListenBrainzPublicPlaylistHttp(200,
                        """{"$id1":{"release":{"caa_release_mbid":"$playlistId","caa_id":123}},
                            "$id2":{"release":{"caa_release_mbid":"$playlistId","caa_id":124}}}""")
                }
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        val first = client.artworkForRecordings(listOf(id1, id2))
        assertEquals(first, client.artworkForRecordings(listOf(id2, id1)))
        assertEquals(2, first.size)
        assertEquals(1, calls)
    }

    @Test fun concurrentRecordingArtworkRequestsShareOneUpstreamLookup() = runBlocking {
        val recording = "33333333-3333-4333-8333-333333333333"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
                override suspend fun recordingArtwork(
                    recordingMbids: List<String>,
                ): ListenBrainzPublicPlaylistHttp {
                    calls++
                    started.complete(Unit)
                    release.await()
                    return ListenBrainzPublicPlaylistHttp(200,
                        """{"$recording":{"release":{"caa_release_mbid":"$playlistId","caa_id":123}}}""")
                }
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            client.artworkForRecordings(listOf(recording))
        }
        withTimeout(2_000L) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            client.artworkForRecordings(listOf(recording))
        }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    @Test fun temporaryRecordingArtworkErrorCanRetryWithoutNegativeCache() = runBlocking {
        val recording = "33333333-3333-4333-8333-333333333333"
        var calls = 0
        val client = ListenBrainzPublicPlaylistClient(
            object : ListenBrainzPublicPlaylistTransport {
                override suspend fun search(query: String) =
                    ListenBrainzPublicPlaylistHttp(200, """{"playlists":[]}""")
                override suspend fun detail(mbid: String) = ListenBrainzPublicPlaylistHttp(404, "")
                override suspend fun recordingArtwork(
                    recordingMbids: List<String>,
                ): ListenBrainzPublicPlaylistHttp {
                    calls++
                    return if (calls == 1) ListenBrainzPublicPlaylistHttp(503, "")
                        else ListenBrainzPublicPlaylistHttp(200,
                            """{"$recording":{"release":{"caa_release_mbid":"$playlistId","caa_id":123}}}""")
                }
            },
            ListenBrainzRateLimiter(nowMillis = { 1_000L }, waitMillis = {}),
        )
        assertEquals(emptyMap<String, String>(), client.artworkForRecordings(listOf(recording)))
        assertEquals(1, client.artworkForRecordings(listOf(recording)).size)
        assertEquals(2, calls)
    }
}

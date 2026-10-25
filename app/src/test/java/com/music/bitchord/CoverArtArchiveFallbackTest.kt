package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtArchiveResponse
import com.music.bitchord.data.catalog.metadata.CoverArtArchiveTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzCoverArtCacheEntry
import com.music.bitchord.data.catalog.metadata.MusicBrainzCoverArtStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverArtArchiveFallbackTest {
    private val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"

    @Test fun release404FallsBackToSameGroupWithPositiveAndNegativeCaching() = runBlocking {
        val requests = mutableListOf<String>()
        val saved = mutableMapOf<Pair<String, String>, MusicBrainzCoverArtCacheEntry>()
        val store = object : MusicBrainzCoverArtStore {
            override suspend fun read(type: String, mbid: String) = saved[type to mbid]
            override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                saved[value.entityType to value.mbid] = value
            }
        }
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    requests += path
                    return if (path.startsWith("release/")) CoverArtArchiveResponse(404, null)
                    else CoverArtArchiveResponse(200, """{"images":[
                        {"front":false,"thumbnails":{"250":"https://archive.org/back.jpg"}},
                        {"front":true,"thumbnails":{"250":"https://coverartarchive.org/release-group/$group/front-250"}}
                    ]}""")
                }
            },
            store = store,
            nowEpochMs = { 1000L },
        )
        val expected = "https://coverartarchive.org/release-group/$group/front-250"
        assertEquals(expected, cover.findCover(release, group))
        assertEquals(expected, cover.findCover(release, group))
        assertEquals(listOf("release/$release", "release-group/$group"), requests)
        assertNull(saved["release" to release]?.artworkUri)
        assertEquals(expected, saved["release-group" to group]?.artworkUri)
    }

    @Test fun failuresAreNotNegativeCachedAndUnsafeUrlsAreIgnored() = runBlocking {
        val requests = mutableListOf<String>()
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    requests += path
                    return if (requests.size == 1) CoverArtArchiveResponse(503, null)
                    else CoverArtArchiveResponse(200, """{"images":[
                        {"front":true,"thumbnails":{"250":"http://evil.example/cover.jpg"}}
                    ]}""")
                }
            },
        )
        assertNull(cover.findCover(release, null))
        assertNull(cover.findCover(release, null))
        assertEquals(2, requests.size)
        assertNull(cover.findCover("invalid", group))
        assertEquals(2, requests.size)
    }
}

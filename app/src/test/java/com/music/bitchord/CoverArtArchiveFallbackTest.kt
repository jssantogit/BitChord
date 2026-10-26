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
    @Test fun stalledCoverApiRequestIsBoundedAndMayRecoverOnRetry() = runBlocking {
        var calls = 0
        val archive = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    calls++
                    if (calls == 1) kotlinx.coroutines.delay(120L)
                    return CoverArtArchiveResponse(200,
                        """{"images":[{"front":true,"types":["Front"],
                        "image":"https://archive.org/recovered-cover.jpg"}]}""")
                }
            },
            requestTimeoutMs = 20L,
        )
        assertNull(archive.findCover(release, null))
        assertEquals("https://archive.org/recovered-cover.jpg",
            archive.findCover(release, null))
        assertEquals(2, calls)
    }

    @Test fun release503DoesNotSilentlyReplaceEditionCoverWithGroupArtwork() = runBlocking {
        val requests = mutableListOf<String>()
        var releaseRecovered = false
        val cover = CoverArtArchive(transport = object : CoverArtArchiveTransport {
            override suspend fun get(path: String): CoverArtArchiveResponse {
                requests += path
                return when {
                    path.startsWith("release-group/") -> CoverArtArchiveResponse(
                        200, """{"images":[{"front":true,"image":"https://archive.org/group.jpg"}]}""",
                    )
                    !releaseRecovered -> CoverArtArchiveResponse(503, null)
                    else -> CoverArtArchiveResponse(
                        200, """{"images":[{"front":true,"image":"https://archive.org/edition.jpg"}]}""",
                    )
                }
            }
        })
        assertNull(cover.findCover(release, group))
        assertEquals(listOf("release/$release"), requests)
        releaseRecovered = true
        assertEquals("https://archive.org/edition.jpg", cover.findCover(release, group))
        assertEquals(listOf("release/$release", "release/$release"), requests)
    }

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

    @Test fun unsafeEditionArtworkIsTransientFailureAndNeverTriggersGroupFallback() = runBlocking {
        val requests = mutableListOf<String>()
        val cover = CoverArtArchive(transport = object : CoverArtArchiveTransport {
            override suspend fun get(path: String): CoverArtArchiveResponse {
                requests += path
                return when {
                    path.startsWith("release-group/") -> CoverArtArchiveResponse(
                        200, """{"images":[{"front":true,"image":"https://archive.org/group.jpg"}]}""",
                    )
                    else -> CoverArtArchiveResponse(
                        200, """{"images":[{"front":true,"image":"http://unsafe.invalid/edition.jpg"}]}""",
                    )
                }
            }
        })
        assertNull(cover.findCover(release, group))
        assertNull(cover.findCover(release, group))
        assertEquals(listOf("release/$release", "release/$release"), requests)
    }


    @Test fun untrustedCachedCoverNeverBypassesArtworkUrlValidation() = runBlocking {
        val stored = mutableMapOf(
            ("release" to release) to MusicBrainzCoverArtCacheEntry(
                "release", release, "https://untrusted.invalid/cover.jpg", 1000L,
            ),
        )
        var requests = 0
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    requests++
                    return CoverArtArchiveResponse(
                        200, """{"images":[{"front":true,"image":"https://archive.org/valid.jpg"}]}""",
                    )
                }
            },
            store = object : MusicBrainzCoverArtStore {
                override suspend fun read(type: String, mbid: String) = stored[type to mbid]
                override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                    stored[value.entityType to value.mbid] = value
                }
            },
            nowEpochMs = { 1001L },
        )
        assertEquals("https://archive.org/valid.jpg", cover.findCover(release, group))
        assertEquals(1, requests)
    }

    @Test fun posterOrTrackImageMarkedAsFrontIsNotAnAlbumSleeve() = runBlocking {
        val cover = CoverArtArchive(transport = object : CoverArtArchiveTransport {
            override suspend fun get(path: String) = CoverArtArchiveResponse(
                200, """{"images":[
                    {"front":true,"types":["Poster"],"approved":true,
                     "image":"https://archive.org/promotional-video-poster.jpg"},
                    {"front":true,"types":["Front"],"approved":true,
                     "image":"https://archive.org/official-audio-cover.jpg"}
                ]}""",
            )
        })
        assertEquals("https://archive.org/official-audio-cover.jpg", cover.findCover(release, null))
    }

    @Test fun explicitlyUnapprovedFrontIsNotPromotedAsCover() = runBlocking {
        val cover = CoverArtArchive(transport = object : CoverArtArchiveTransport {
            override suspend fun get(path: String) = CoverArtArchiveResponse(
                200, """{"images":[{"front":true,"types":["Front"],"approved":false,
                    "image":"https://archive.org/unapproved-art.jpg"}]}""",
            )
        })
        assertNull(cover.findCover(release, null))
    }

    @Test fun chooses1200Over250AndNormalizesTrustedCaaHttpToHttps() = runBlocking {
        val cover = CoverArtArchive(transport = object : CoverArtArchiveTransport {
            override suspend fun get(path: String) = CoverArtArchiveResponse(
                200, """{"images":[{"front":true,"thumbnails":{
                    "250":"http://coverartarchive.org/release/$release/art-250.jpg",
                    "500":"http://coverartarchive.org/release/$release/art-500.jpg",
                    "1200":"http://coverartarchive.org/release/$release/art-1200.jpg"
                }}]}""",
            )
        })
        assertEquals("https://coverartarchive.org/release/$release/art-1200.jpg",
            cover.findCover(release, null))
    }

    @Test fun strictFrontPolicyUsesSeparatePersistentKeysFromLegacyCoverCache() {
        assertEquals("release:front-v2",
            com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType("release"))
        assertEquals("release-group:front-v2",
            com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType("release-group"))
    }

    @Test fun coversCachedBeforeStricterFrontPolicyAreRecheckedOnce() = runBlocking {
        val oldTime = 1_792_886_400_000L
        val now = 1_793_000_000_000L
        val storeRows = mutableMapOf(
            ("release" to release) to MusicBrainzCoverArtCacheEntry(
                "release", release, "https://archive.org/old-video-still.jpg", oldTime,
            ),
        )
        var calls = 0
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    calls++
                    return CoverArtArchiveResponse(200,
                        """{"images":[
                            {"front":true,"types":["Poster"],"image":"https://archive.org/still.jpg"},
                            {"front":true,"types":["Front"],"approved":true,
                             "image":"https://archive.org/official-sleeve.jpg"}
                        ]}""")
                }
            },
            store = object : MusicBrainzCoverArtStore {
                override suspend fun read(type: String, mbid: String) = storeRows[type to mbid]
                override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                    storeRows[value.entityType to value.mbid] = value
                }
            },
            nowEpochMs = { now },
        )
        assertEquals("https://archive.org/official-sleeve.jpg", cover.findCover(release, null))
        assertEquals("https://archive.org/official-sleeve.jpg", cover.findCover(release, null))
        assertEquals(1, calls)
    }

    @Test fun old250pxRoomCacheCanBeUpgradedWithoutTrustingTextMatches() = runBlocking {
        val entries = mutableMapOf(
            ("release" to release) to MusicBrainzCoverArtCacheEntry(
                "release", release,
                "https://coverartarchive.org/release/$release/art-250.jpg", 1000L,
            )
        )
        var calls = 0
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    calls++
                    return CoverArtArchiveResponse(200, """{"images":[{"front":true,
                        "thumbnails":{"1200":"https://coverartarchive.org/release/$release/art-1200.jpg"}
                    }]}""")
                }
            },
            store = object : MusicBrainzCoverArtStore {
                override suspend fun read(type: String, mbid: String) = entries[type to mbid]
                override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                    entries[value.entityType to value.mbid] = value
                }
            },
            nowEpochMs = { 1100L },
        )
        assertEquals("https://coverartarchive.org/release/$release/art-1200.jpg",
            cover.findCover(release, null))
        assertEquals(1, calls)
    }


}

package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.CoverArtSize
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtArchiveResponse
import com.music.bitchord.data.catalog.metadata.CoverArtArchiveTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzCoverArtCacheEntry
import com.music.bitchord.data.catalog.metadata.MusicBrainzCoverArtStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
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
        assertEquals("release-group:front-v3",
            com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType("verified-artist-group"))
    }

    @Test fun oldFrontCacheCannotBlockNewPolicyAndNewCacheIsReusable() = runBlocking {
        val oldTime = 1_792_886_400_000L
        val now = 1_793_000_000_000L
        val oldKey = "release" to release
        val freshKey = com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType("release") to release
        val storeRows = mutableMapOf(
            oldKey to MusicBrainzCoverArtCacheEntry(
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
                override suspend fun read(type: String, mbid: String) =
                    storeRows[com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType(type) to mbid]
                override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                    storeRows[com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType(value.entityType) to value.mbid] = value
                }
            },
            nowEpochMs = { now },
        )
        assertEquals("https://archive.org/official-sleeve.jpg", cover.findCover(release, null))
        assertEquals("https://archive.org/official-sleeve.jpg", cover.findCover(release, null))
        assertEquals(1, calls)
        assertEquals("https://archive.org/old-video-still.jpg", storeRows[oldKey]?.artworkUri)
        assertEquals("https://archive.org/official-sleeve.jpg", storeRows[freshKey]?.artworkUri)
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
            nowEpochMs = { 8L * 60 * 60 * 1000 + 1100L },
        )
        assertEquals("https://coverartarchive.org/release/$release/art-1200.jpg",
            cover.findCover(release, null))
        assertEquals(1, calls)
    }


    @Test fun repeatedRequestsReuseFreshSmallCoverInsteadOfRefetchingForever() = runBlocking {
        var calls = 0
        val cache = mutableMapOf<Pair<String, String>, MusicBrainzCoverArtCacheEntry>()
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    calls++
                    return CoverArtArchiveResponse(200,
                        """{"images":[{"front":true,"types":["Front"],"approved":true,
                            "thumbnails":{"250":"https://coverartarchive.org/release/$release/front-250.jpg"}}]}""")
                }
            },
            store = object : MusicBrainzCoverArtStore {
                override suspend fun read(type: String, mbid: String) = cache[type to mbid]
                override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                    cache[value.entityType to value.mbid] = value
                }
            },
            nowEpochMs = { 50_000L },
        )
        val uri = "https://coverartarchive.org/release/$release/front-250.jpg"
        assertEquals(uri, cover.findCover(release, null))
        assertEquals(uri, cover.findCover(release, null))
        assertEquals(1, calls)
    }

    @Test fun concurrentlyRequestedSameReleaseSharesOneArtworkLookup() = runBlocking {
        val releaseStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseContinue = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val cover = CoverArtArchive(transport = object : CoverArtArchiveTransport {
            override suspend fun get(path: String): CoverArtArchiveResponse {
                calls++
                releaseStarted.complete(Unit)
                releaseContinue.await()
                return CoverArtArchiveResponse(200,
                    """{"images":[{"front":true,"types":["Front"],"approved":true,
                        "image":"https://archive.org/shared-front.jpg"}]}""")
            }
        })
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cover.findCover(release, null)
        }
        releaseStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cover.findCover(release, null)
        }
        assertEquals(1, calls)
        releaseContinue.complete(Unit)
        assertEquals("https://archive.org/shared-front.jpg", first.await())
        assertEquals("https://archive.org/shared-front.jpg", second.await())
        assertEquals(1, calls)
    }

    @Test fun frontPosterMixedTypeCannotBeUsedAsAlbumCover() = runBlocking {
        val cover = CoverArtArchive(transport = object : CoverArtArchiveTransport {
            override suspend fun get(path: String) = CoverArtArchiveResponse(
                200, """{"images":[
                    {"front":true,"types":["Poster","Front"],"approved":true,
                     "image":"https://archive.org/video-poster.jpg"},
                    {"front":true,"types":["Front"],"approved":true,
                     "image":"https://archive.org/actual-release-cover.jpg"}
                ]}""",
            )
        })
        assertEquals("https://archive.org/actual-release-cover.jpg", cover.findCover(release, null))
    }

    @Test fun thumbnailAndHeroReuseSameApprovedImageEvidenceWithoutExtraNetwork() = runBlocking {
        val releaseId = release
        var calls = 0
        val cached = mutableMapOf<Pair<String,String>, MusicBrainzCoverArtCacheEntry>()
        val store = object : MusicBrainzCoverArtStore {
            override suspend fun read(type: String, mbid: String) = cached[type to mbid]
            override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                cached[value.entityType to value.mbid] = value
            }
        }
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    calls++
                    return CoverArtArchiveResponse(200, """{"images":[
                        {"front":true,"types":["Front"],"approved":true,
                         "thumbnails":{"500":"https://coverartarchive.org/release/$releaseId/front-500",
                                       "1200":"https://coverartarchive.org/release/$releaseId/front-1200"}}
                    ]}""")
                }
            }, store = store,
        )
        assertEquals("https://coverartarchive.org/release/$releaseId/front-500",
            cover.findCover(releaseId, null, CoverArtSize.THUMBNAIL))
        assertEquals("https://coverartarchive.org/release/$releaseId/front-1200",
            cover.findCover(releaseId, null, CoverArtSize.HERO))
        assertEquals(1, calls)
        assertEquals("https://coverartarchive.org/release/$releaseId/front-500",
            cached["release" to releaseId]?.thumbnailUri)
    }


    @Test fun verifiedGroupCacheNeverReadsLegacyAlternateEditionArtworkOrCallsCAA() = runBlocking {
        val now = 1_793_034_000_000L
        val records = mutableMapOf(
            "release-group:front-v2" to MusicBrainzCoverArtCacheEntry(
                "release-group", group, "https://archive.org/old-video.jpg", now,
            ),
        )
        var network = 0
        val store = object : MusicBrainzCoverArtStore {
            override suspend fun read(type: String, mbid: String): MusicBrainzCoverArtCacheEntry? =
                records[com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType(type)]
            override suspend fun write(value: MusicBrainzCoverArtCacheEntry) {
                records[com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType(value.entityType)] = value
            }
        }
        val cover = CoverArtArchive(
            transport = object : CoverArtArchiveTransport {
                override suspend fun get(path: String): CoverArtArchiveResponse {
                    network++
                    error("V3 cache probe must not make a request")
                }
            },
            store = store, nowEpochMs = { now + 1000L },
        )
        assertNull(cover.cachedVerifiedGroupCover(group))
        // Generic group fallback is not evidence for an Official edition;
        // it must never write into the verified front-v3 artist namespace.
        assertEquals("release-group:front-v2",
            com.music.bitchord.data.catalog.metadata.verifiedFrontCacheType("release-group"))
        records["release-group:front-v3"] = MusicBrainzCoverArtCacheEntry(
            "release-group", group,
            "https://archive.org/official-1200.jpg", now,
            "https://archive.org/official-500.jpg",
        )
        assertEquals("https://archive.org/official-500.jpg",
            cover.cachedVerifiedGroupCover(group, CoverArtSize.THUMBNAIL))
        assertEquals("https://archive.org/official-1200.jpg",
            cover.cachedVerifiedGroupCover(group, CoverArtSize.HERO))
        assertEquals(0, network)
        records["release-group:front-v3"] = MusicBrainzCoverArtCacheEntry(
            "release-group", group, null, now,
            "https://coverartarchive.org/release/$release/front-500",
        )
        assertEquals("https://coverartarchive.org/release/$release/front-500",
            cover.cachedVerifiedGroupCover(group, CoverArtSize.THUMBNAIL))
        assertNull("A 500px shortcut cannot pretend to be high-quality hero art",
            cover.cachedVerifiedGroupCover(group, CoverArtSize.HERO))
        assertEquals(0, network)
    }

    @Test fun staleOrUntrustedGroupV3ArtworkCannotBeRenderedAsVerified() = runBlocking {
        val now = 1_793_034_000_000L
        var entry = MusicBrainzCoverArtCacheEntry(
            "release-group", group, "https://evil.invalid/wrong.jpg", now,
        )
        val cover = CoverArtArchive(
            store = object : MusicBrainzCoverArtStore {
                override suspend fun read(type: String, mbid: String) = entry
                override suspend fun write(value: MusicBrainzCoverArtCacheEntry) { entry = value }
            },
            nowEpochMs = { now },
        )
        assertNull(cover.cachedVerifiedGroupCover(group))
        entry = entry.copy(artworkUri = "https://archive.org/correct.jpg",
            fetchedAtEpochMs = now - 32L * 24 * 60 * 60 * 1000)
        assertNull(cover.cachedVerifiedGroupCover(group))
    }

}

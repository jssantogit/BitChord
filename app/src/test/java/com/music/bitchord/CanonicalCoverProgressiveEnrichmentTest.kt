package com.music.bitchord

import com.music.bitchord.ui.loadCanonicalCoversProgressively
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCoverProgressiveEnrichmentTest {
    @Test fun slowArchiveLookupDoesNotHoldBackAnAlreadyVerifiedCover() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val fastShown = CompletableDeferred<Unit>()
        val published = mutableMapOf<String, String>()
        val worker = launch {
            loadCanonicalCoversProgressively(listOf("slow", "fast"), maxConcurrent = 2,
                fetch = { key ->
                    if (key == "slow") {
                        slowStarted.complete(Unit)
                        releaseSlow.await()
                    }
                    "https://archive.org/$key.jpg"
                },
                publish = { ready ->
                    published.putAll(ready)
                    if ("fast" in published) fastShown.complete(Unit)
                },
            )
        }
        withTimeout(5000L) { slowStarted.await(); fastShown.await() }
        assertFalse("A stalled archive must not block the first painted cover", worker.isCompleted)
        assertEquals("https://archive.org/fast.jpg", published["fast"])
        releaseSlow.complete(Unit)
        withTimeout(5000L) { worker.join() }
        assertEquals(setOf("slow", "fast"), published.keys)
    }

    @Test fun missingArtIsNotPublishedAndDoesNotBlockOtherCompletions() = runBlocking {
        val published = mutableMapOf<String, String>()
        loadCanonicalCoversProgressively(listOf("missing", "available"), maxConcurrent = 1,
            fetch = { key -> if (key == "missing") null else "verified-art" },
            publish = { published.putAll(it) },
        )
        assertEquals(mapOf("available" to "verified-art"), published)
    }

    @Test fun largeArtistUsesBoundedOfficialEditionPagesInsteadOfPerGroupLookups() = runBlocking {
        val first = com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage(
            items = listOf(com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
                "edition1", "Get Up", "XW", "2023-07-21", "NewJeans",
                status = "Official", releaseGroupMbid = "group1",
            )),
            total = 250, offset = 0, consumedCount = 100,
        )
        val requests = mutableListOf<Int>()
        val published = mutableListOf<Map<String,
            List<com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit>>>()
        com.music.bitchord.ui.collectCanonicalArtistOfficialEditionPages(
            firstPage = first,
            maxAdditionalPages = 3,
            fetch = { offset ->
                requests += offset
                com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult.Success(
                    com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage(
                        items = listOf(
                            com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
                                "edition" + offset, "How Sweet", "XW", "2024-05-24",
                                "NewJeans", status = "Official",
                                releaseGroupMbid = "group" + offset,
                            ),
                            com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
                                "bootleg" + offset, "How Sweet", "XW", null,
                                "NewJeans", status = "Bootleg",
                                releaseGroupMbid = "bootleg-group",
                            ),
                        ),
                        total = 250, offset = offset,
                        consumedCount = if (offset == 100) 100 else 50,
                    ),
                )
            },
            onPage = { published += it },
        )
        assertEquals(listOf(100, 200), requests)
        assertEquals(listOf("group1", "group100", "group200"),
            published.last().keys.toList())
        assertFalse(published.any { "bootleg-group" in it })
    }


    @Test fun aLargeArtistBoundedWorkerPoolStreamsEveryCoverWithoutUnboundedConcurrency() =
        runBlocking {
            val active = java.util.concurrent.atomic.AtomicInteger()
            val peak = java.util.concurrent.atomic.AtomicInteger()
            val found = mutableMapOf<Int, Int>()
            loadCanonicalCoversProgressively(
                keys = (1..160).toList(), maxConcurrent = 3,
                fetch = { key ->
                    val current = active.incrementAndGet()
                    peak.updateAndGet { old -> maxOf(old, current) }
                    try {
                        kotlinx.coroutines.delay(2)
                        key * 2
                    } finally {
                        active.decrementAndGet()
                    }
                },
                publish = { found.putAll(it) },
            )
            assertEquals(160, found.size)
            assertEquals(0, active.get())
            assertTrue("No batch may create more than three active lookups",
                peak.get() <= 3)
        }


    @Test fun slowOfficialEditionPaginationCannotFreezeSecondCoverWave() = runBlocking {
        val expansionStarted = CompletableDeferred<Unit>()
        val finishExpansion = CompletableDeferred<Unit>()
        val coverAppeared = CompletableDeferred<Unit>()
        val observed = mutableMapOf<String, String>()
        val worker = launch {
            com.music.bitchord.ui.loadCanonicalCoversWhileExpandingEditions(
                keys = listOf("second-wave-1", "second-wave-2"),
                maxConcurrent = 2,
                expandEditions = {
                    expansionStarted.complete(Unit)
                    finishExpansion.await()
                },
                fetch = { key -> "validated:$key" },
                publish = { batch ->
                    observed.putAll(batch)
                    if ("second-wave-2" in observed) coverAppeared.complete(Unit)
                },
            )
        }
        withTimeout(5000L) { expansionStarted.await(); coverAppeared.await() }
        assertFalse("MusicBrainz paging must not hold visible CAA completions", worker.isCompleted)
        assertEquals("validated:second-wave-2", observed["second-wave-2"])
        finishExpansion.complete(Unit)
        withTimeout(5000L) { worker.join() }
    }

    @Test fun concurrentEditionExpansionFailureDoesNotPreventAvailableArtwork() = runBlocking {
        val observed = mutableMapOf<Int, Int>()
        com.music.bitchord.ui.loadCanonicalCoversWhileExpandingEditions(
            keys = listOf(1, 2), maxConcurrent = 2,
            expandEditions = { throw IllegalStateException("MusicBrainz unavailable") },
            fetch = { it * 10 },
            publish = { observed.putAll(it) },
        )
        assertEquals(mapOf(1 to 10, 2 to 20), observed)
    }


    @Test fun cancellingArtistNavigationCancelsSlowEditionExpansion() = runBlocking {
        val expansionStarted = CompletableDeferred<Unit>()
        val fetchStarted = CompletableDeferred<Unit>()
        val editionCancelled = CompletableDeferred<Unit>()
        val coverGate = CompletableDeferred<Unit>()
        val job = launch {
            com.music.bitchord.ui.loadCanonicalCoversWhileExpandingEditions(
                keys = listOf("cover"),
                maxConcurrent = 1,
                expandEditions = {
                    expansionStarted.complete(Unit)
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        editionCancelled.complete(Unit)
                    }
                },
                fetch = {
                    fetchStarted.complete(Unit)
                    coverGate.await()
                    "art"
                },
                publish = { _ -> },
            )
        }
        withTimeout(5000L) { expansionStarted.await(); fetchStarted.await() }
        job.cancel()
        withTimeout(5000L) { editionCancelled.await(); job.join() }
        assertTrue("Navigation cancels edition queries and queued cover workers", job.isCancelled)
    }


    @Test fun twentyVisibleTracksShareOneCachedAlbumSleeveWithoutArchiveCalls() = runBlocking {
        val group = "11111111-1111-4111-8111-111111111111"
        val ids = (1..20).map { "recording-$it" }
        val rows = ids.map { mbid ->
            com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                musicBrainzId = mbid,
                kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
                title = "Song", subtitle = "Artist", coverGroupMbid = group,
            )
        }
        var cachedLookups = 0
        var databaseCalls = 0
        val proven = com.music.bitchord.ui.canonicalCachedRecordingArtForVisibleRows(
            rows = rows,
            databaseLinks = { requested ->
                databaseCalls++
                assertEquals(emptyList<String>(), requested)
                emptyList()
            },
            cachedCover = { requested ->
                cachedLookups++
                assertEquals(group, requested)
                "https://archive.org/verified-album-500.jpg"
            },
        )
        assertEquals(20, proven.size)
        assertEquals(1, cachedLookups)
        assertEquals(0, databaseCalls)
        assertTrue(proven.values.all { it == "https://archive.org/verified-album-500.jpg" })
    }

    @Test fun unavailableLiveGroupUsesOnlyVerifiedOfflineTrackLinksWithoutTitleMatching() =
        runBlocking {
            val r1 = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                "recording-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
                "Identical title", "Artist", coverGroupMbid = "group-no-cover",
            )
            val r2 = r1.copy(musicBrainzId = "recording-2", coverGroupMbid = null)
            val unrelated = r1.copy(musicBrainzId = "album-row",
                kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP)
            val results = com.music.bitchord.ui.canonicalCachedRecordingArtForVisibleRows(
                listOf(r1, r2, unrelated),
                databaseLinks = { ids ->
                    assertEquals(listOf("recording-1", "recording-2"), ids)
                    listOf(
                        com.music.bitchord.data.catalog.CanonicalRecordingCoverGroupRow(
                            "recording-1", "group-a",
                        ),
                        com.music.bitchord.data.catalog.CanonicalRecordingCoverGroupRow(
                            "recording-2", "group-b",
                        ),
                    )
                },
                cachedCover = { group ->
                    if (group == "group-no-cover") null else "art:$group"
                },
            )
            assertEquals(mapOf("recording-1" to "art:group-a",
                "recording-2" to "art:group-b"), results)
        }


    @Test fun verifiedAlbumCoversSurviveAnUnavailableOfflineGraph() = runBlocking {
        val group = "official-album-group"
        val first = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            musicBrainzId = "recording-1",
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            title = "First song",
            subtitle = "Artist",
            coverGroupMbid = group,
        )
        val second = first.copy(musicBrainzId = "recording-2")
        val unresolved = first.copy(musicBrainzId = "recording-3", coverGroupMbid = null)
        var lookups = 0
        val result = com.music.bitchord.ui.canonicalCachedRecordingArtForVisibleRows(
            rows = listOf(first, second, unresolved),
            databaseLinks = {
                assertEquals(listOf("recording-3"), it)
                throw IllegalStateException("Room temporarily unavailable")
            },
            cachedCover = {
                lookups++
                assertEquals(group, it)
                "https://archive.org/already-verified-500.jpg"
            },
        )
        assertEquals(1, lookups)
        assertEquals(
            mapOf(
                "recording-1" to "https://archive.org/already-verified-500.jpg",
                "recording-2" to "https://archive.org/already-verified-500.jpg",
            ),
            result,
        )
    }

    @Test fun newVerifiedAlbumArtRefreshesOnlyMissingRecordingSleeves() {
        val first = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "recording-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "Zero", "NewJeans",
        )
        val second = first.copy(musicBrainzId = "recording-2",
            artworkUri = "https://archive.org/own-front-500.jpg")
        val unrelated = first.copy(musicBrainzId = "recording-3")
        val album = first.copy(musicBrainzId = "album-id",
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP)
        val rows = listOf(first, second, unrelated, album)
        val refreshed = com.music.bitchord.ui.canonicalRowsWithVerifiedRecordingArt(
            rows,
            mapOf(
                "recording-1" to "https://archive.org/newjeans-front-500.jpg",
                "recording-2" to "https://archive.org/newjeans-front-500.jpg",
                "album-id" to "https://archive.org/newjeans-front-500.jpg",
            ),
        )
        assertEquals("https://archive.org/newjeans-front-500.jpg", refreshed[0].artworkUri)
        assertEquals("https://archive.org/own-front-500.jpg", refreshed[1].artworkUri)
        assertEquals(null, refreshed[2].artworkUri)
        assertEquals(null, refreshed[3].artworkUri)
    }

    @Test fun lateVerifiedAlbumCoverImmediatelyFansOutAcrossDirectOfficialRecordingEdges() {
        val official = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "rec-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "Attention", "NewJeans", coverGroupMbid = "verified-group",
        )
        val sibling = official.copy(musicBrainzId = "rec-2")
        val own = official.copy(musicBrainzId = "rec-3",
            artworkUri = "https://archive.org/track-owned-front.jpg")
        val unrelated = official.copy(musicBrainzId = "rec-other",
            coverGroupMbid = "other-group")
        val album = official.copy(musicBrainzId = "verified-group",
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP)
        val updated = com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
            listOf(official, sibling, own, unrelated, album),
            "verified-group",
            "https://archive.org/verified-group-front-500.jpg",
        )
        assertEquals("https://archive.org/verified-group-front-500.jpg", updated[0].artworkUri)
        assertEquals(updated[0].artworkUri, updated[1].artworkUri)
        assertEquals("https://archive.org/track-owned-front.jpg", updated[2].artworkUri)
        assertEquals(null, updated[3].artworkUri)
        assertEquals(null, updated[4].artworkUri)
    }
}

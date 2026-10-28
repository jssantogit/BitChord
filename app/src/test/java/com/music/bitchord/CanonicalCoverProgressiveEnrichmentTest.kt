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

    @Test fun visibleSearchTracksDoNotWaitBehindAnEntireAlbumArtworkQueue() {
        val song = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "r-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "Attention", "NewJeans", coverReleaseMbid = "release-1",
        )
        val album = song.copy(
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
            title = "Get Up", musicBrainzId = "g-1", coverReleaseMbid = null,
            releaseKind = "EP",
        )
        val recordings = (1..12).map {
            song.copy(musicBrainzId = "r-$it", coverReleaseMbid = "release-$it")
        }
        val groups = (1..8).map { album.copy(musicBrainzId = "g-$it") }
        val queue = com.music.bitchord.ui.canonicalSearchCoverPriorityQueue(
            groups + recordings,
        )
        assertEquals(20, queue.size)
        assertEquals(listOf("r-1", "g-1", "r-2", "g-2"),
            queue.take(4).map { it.musicBrainzId })
        assertEquals(12, queue.count {
            it.kind == com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING
        })
        assertEquals(20, queue.map { it.musicBrainzId }.distinct().size)
    }

    @Test fun twentyDistinctOfficialAlbumsNeedOneCachedCoverBatchNotTwentyRoomReads() = runBlocking {
        val rows = (1..20).map { index ->
            com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                "rec-$index", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
                "Song $index", "Artist", coverGroupMbid = "group-$index",
            )
        }
        var bulkCalls = 0
        val proven = com.music.bitchord.ui.canonicalCachedRecordingArtForVisibleRows(
            rows = rows,
            databaseLinks = { error("Direct official group proofs do not need Room graph") },
            cachedCover = { error("One-by-one artwork lookup is redundant") },
            cachedCovers = { groups ->
                bulkCalls++
                assertEquals((1..20).map { "group-$it" }, groups)
                groups.associateWith { "art:$it" }
            },
        )
        assertEquals(1, bulkCalls)
        assertEquals(20, proven.size)
        assertEquals("art:group-20", proven["rec-20"])
    }

    @Test fun batchFallbackOnlyQueriesUnresolvedAndNeverAssignsUnrelatedGroupCover() = runBlocking {
        val rows = listOf(
            com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                "rec-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
                "One", "Artist", coverGroupMbid = "direct",
            ),
            com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                "rec-2", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
                "Two", "Artist", coverGroupMbid = "no-art",
            ),
            com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                "rec-3", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
                "Three", "Artist",
            ),
        )
        val lookedUp = mutableListOf<List<String>>()
        val graphCalls = mutableListOf<List<String>>()
        val proven = com.music.bitchord.ui.canonicalCachedRecordingArtForVisibleRows(
            rows = rows,
            databaseLinks = { missing ->
                graphCalls += missing
                listOf(
                    com.music.bitchord.data.catalog.CanonicalRecordingCoverGroupRow("rec-2", "cached"),
                    com.music.bitchord.data.catalog.CanonicalRecordingCoverGroupRow("rec-3", "cached"),
                    com.music.bitchord.data.catalog.CanonicalRecordingCoverGroupRow("unrelated", "cached"),
                )
            },
            cachedCover = { error("Never query each group separately") },
            cachedCovers = { groupIds ->
                lookedUp += groupIds
                groupIds.mapNotNull {
                    when (it) {
                        "direct" -> it to "art:direct"
                        "cached" -> it to "art:cached"
                        else -> null
                    }
                }.toMap()
            },
        )
        assertEquals(listOf(listOf("direct", "no-art"), listOf("cached")), lookedUp)
        assertEquals(listOf(listOf("rec-2", "rec-3")), graphCalls)
        assertEquals(mapOf("rec-1" to "art:direct", "rec-2" to "art:cached",
            "rec-3" to "art:cached"), proven)
    }

    @Test fun missingBatchCacheFallsBackToSingleReadAndNeverDiscardsValidSiblingArt() =
        runBlocking {
            val row = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
                "rec-a", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
                "A", "Artist", coverGroupMbid = "group-a",
            )
            var batchAttempts = 0
            val perGroupAttempts = mutableListOf<String>()
            val result = com.music.bitchord.ui.canonicalCachedRecordingArtForVisibleRows(
                rows = listOf(row, row.copy(musicBrainzId = "rec-b", coverGroupMbid = "group-b")),
                databaseLinks = { error("Every direct group returned a valid sleeve") },
                cachedCovers = {
                    batchAttempts++
                    throw IllegalStateException("Temporary Room batch failure")
                },
                cachedCover = { group ->
                    perGroupAttempts += group
                    if (group == "group-a") "art:group-a" else "art:group-b"
                },
            )
            assertEquals(1, batchAttempts)
            assertEquals(listOf("group-a", "group-b"), perGroupAttempts)
            assertEquals(mapOf("rec-a" to "art:group-a", "rec-b" to "art:group-b"), result)
        }

    @Test fun batchReturningForeignGroupDoesNotPaintUnrelatedRecording() = runBlocking {
        val row = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "rec-a", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "A", "Artist", coverGroupMbid = "known-group",
        )
        val result = com.music.bitchord.ui.canonicalCachedRecordingArtForVisibleRows(
            rows = listOf(row),
            databaseLinks = { emptyList() },
            cachedCover = { error("Unneeded single lookup") },
            cachedCovers = { mapOf("unrelated-group" to "art:unrelated") },
        )
        assertTrue(result.isEmpty())
    }

    @Test fun lateGroupCoverReconcilesEarlyReleaseFallbackWithoutTouchingTrackArtwork() {
        val group = "11111111-1111-4111-8111-111111111111"
        val editionB = "https://archive.org/release-b-front-500.jpg"
        val editionA = "https://archive.org/verified-group-edition-a-front-500.jpg"
        val base = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "rec-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "Song", "Artist", coverGroupMbid = group,
        )
        val earlyFallback = base.copy(
            artworkUri = editionB,
            artworkProvenance = com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_RELEASE,
        )
        val genuineTrack = base.copy(musicBrainzId = "rec-2",
            artworkUri = "content://recording-specific-cover")
        val unrelated = base.copy(musicBrainzId = "rec-3",
            coverGroupMbid = "different-group", artworkUri = editionB,
            artworkProvenance = com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_RELEASE)
        val groupSelected = com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
            listOf(earlyFallback, genuineTrack, unrelated), group, editionA,
        )
        assertEquals(editionA, groupSelected[0].artworkUri)
        assertEquals(
            com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_GROUP,
            groupSelected[0].artworkProvenance,
        )
        assertEquals("content://recording-specific-cover", groupSelected[1].artworkUri)
        assertEquals(editionB, groupSelected[2].artworkUri)
        assertEquals(groupSelected, com.music.bitchord.ui.canonicalRowsWithVerifiedRecordingArt(
            groupSelected, mapOf("rec-1" to editionA),
        ))
    }

    @Test fun cachedOfficialGroupCoverReplacesInFlightReleaseFallbackButNotExplicitArtwork() {
        val group = "11111111-1111-4111-8111-111111111111"
        val base = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "rec-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "Song", "Artist", coverGroupMbid = group,
        )
        val rows = listOf(
            base.copy(
                artworkUri = "https://archive.org/edition-b.jpg",
                artworkProvenance =
                    com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_RELEASE,
            ),
            base.copy(musicBrainzId = "rec-2",
                artworkUri = "https://archive.org/track-owned.jpg"),
        )
        val updated = com.music.bitchord.ui.canonicalRowsWithVerifiedRecordingArt(
            rows, mapOf("rec-1" to "https://archive.org/official-group.jpg",
                "rec-2" to "https://archive.org/official-group.jpg"),
        )
        assertEquals("https://archive.org/official-group.jpg", updated[0].artworkUri)
        assertEquals("https://archive.org/track-owned.jpg", updated[1].artworkUri)
    }

    @Test fun lateEditionResponseNeverReplacesAlreadySelectedGroupFront() {
        val group = "11111111-1111-4111-8111-111111111111"
        val base = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "recording", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "Attention", "NewJeans", coverGroupMbid = group,
        )
        val edition = "https://archive.org/other-edition-front.jpg"
        val groupFront = "https://archive.org/verified-group-front.jpg"

        // Network completion order A: edition fallback before canonical group art.
        val firstFallback = com.music.bitchord.ui.canonicalRecordingWithInheritedReleaseArt(
            base, edition,
        )
        assertEquals(
            com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_RELEASE,
            firstFallback.artworkProvenance,
        )
        val frontArrives = com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
            listOf(firstFallback), group, groupFront,
        ).single()
        assertEquals(groupFront, frontArrives.artworkUri)
        assertEquals(
            com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_GROUP,
            frontArrives.artworkProvenance,
        )

        // Network completion order B: verified group art before stale edition.
        val groupFirst = com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
            listOf(base), group, groupFront,
        ).single()
        val staleEdition = com.music.bitchord.ui.canonicalRecordingWithInheritedReleaseArt(
            groupFirst, edition,
        )
        assertEquals(groupFirst, staleEdition)
        val trackOwnsCover = base.copy(artworkUri = "content://music/explicit-song-art")
        assertEquals(trackOwnsCover,
            com.music.bitchord.ui.canonicalRecordingWithInheritedReleaseArt(
                trackOwnsCover, edition,
            ))
    }

    @Test fun warmVerifiedGroupBatchPaintsAlbumAndSiblingSongsBeforeAnyNetworkRequest() {
        val group = "11111111-1111-4111-8111-111111111111"
        val official = "https://archive.org/official-front-500.jpg"
        val parent = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            group, com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
            "Get Up", "NewJeans", releaseKind = "EP",
        )
        val sibling = parent.copy(
            musicBrainzId = "recording-1",
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            title = "Super Shy", coverGroupMbid = group,
            artworkUri = "https://archive.org/edition-other.jpg",
            artworkProvenance =
                com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_RELEASE,
        )
        val own = sibling.copy(musicBrainzId = "recording-2",
            artworkUri = "content://tracks/own.jpg", artworkProvenance = null)
        val foreign = sibling.copy(musicBrainzId = "recording-other",
            coverGroupMbid = "other", artworkUri = null, artworkProvenance = null)
        val results = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            listOf(parent, sibling, own, foreign), mapOf(group to official),
        )
        assertEquals(official, results[0].artworkUri)
        assertEquals(official, results[1].artworkUri)
        assertEquals(
            com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_GROUP,
            results[1].artworkProvenance,
        )
        assertEquals("content://tracks/own.jpg", results[2].artworkUri)
        assertEquals(null, results[3].artworkUri)
        assertEquals(results, com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            results, mapOf(group to official)))
    }

    @Test fun verified250pxWarmCacheMayStillUpgradeGroupAndItsInheritedRecordingTo500px() {
        val group = "11111111-1111-4111-8111-111111111111"
        val small = "https://coverartarchive.org/release/abc/front-250"
        val large = "https://coverartarchive.org/release/abc/front-500"
        val album = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            group, com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
            "Get Up", "NewJeans", releaseKind = "EP",
        )
        val recording = album.copy(musicBrainzId = "recording",
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            coverGroupMbid = group)
        val warm = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            listOf(album, recording), mapOf(group to small),
        )
        assertEquals(small, warm[0].artworkUri)
        assertEquals(small, warm[1].artworkUri)
        assertTrue(com.music.bitchord.ui.canonicalNeedsCoverRefresh(warm[0].artworkUri))
        val upgraded = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            warm, mapOf(group to large),
        )
        assertEquals(large, upgraded[0].artworkUri)
        assertEquals(large, upgraded[1].artworkUri)
        val downgraded = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            upgraded, mapOf(group to small),
        )
        assertEquals(large, downgraded[0].artworkUri)
        // Inherited recordings must not downgrade after their verified group
        // sleeve has reached the larger variant.
        assertEquals(large, downgraded[1].artworkUri)
    }

    @Test fun verifiedWarmBatchNeverPaintsNonAudioReleaseGroupCard() {
        val group = "11111111-1111-4111-8111-111111111111"
        val row = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            group, com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
            "Music video collection", "Artist", releaseKind = "Other",
        )
        val updated = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            listOf(row), mapOf(group to "https://archive.org/album-front-500.jpg"),
        )
        assertEquals(null, updated.single().artworkUri)
    }

    @Test fun directOfficialGroupFrontReplacesEquivalentSizeFallbackFromOtherCachedGroup() {
        val intended = "11111111-1111-4111-8111-111111111111"
        val previous = "https://archive.org/compilation-front-500.jpg"
        val official = "https://archive.org/official-get-up-front-500.jpg"
        val base = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "recording-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            "Super Shy", "NewJeans", coverGroupMbid = intended,
        )
        // An older Room Track edge found an unrelated compilation group
        // while the current Recording->Official Group cover was still missing.
        val inheritedWrongGroup = com.music.bitchord.ui.canonicalRowsWithVerifiedRecordingArt(
            listOf(base), mapOf(base.musicBrainzId to previous),
        ).single()
        assertEquals(
            com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_GROUP,
            inheritedWrongGroup.artworkProvenance,
        )
        // Same-resolution images must not be treated as interchangeable:
        // direct MusicBrainz group proof wins when its verified Front arrives.
        val corrected = com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
            listOf(inheritedWrongGroup), intended, official,
        ).single()
        assertEquals(official, corrected.artworkUri)
        assertEquals(corrected,
            com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
                listOf(corrected), intended, official,
            ).single())

        // An unrelated group must never touch the recording. The same direct
        // group may not downgrade the selected 500px sleeve to front-250.
        assertEquals(corrected,
            com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
                listOf(corrected), "22222222-2222-4222-8222-222222222222",
                "https://archive.org/foreign-front-500.jpg",
            ).single())
        assertEquals(corrected,
            com.music.bitchord.ui.canonicalRowsWithNewVerifiedGroupArt(
                listOf(corrected), intended,
                "https://coverartarchive.org/release/abc/front-250",
            ).single())
    }

    @Test fun warmExactGroupFrontConvergesEqualResolutionRecordingWithoutChangingTrackOwnedArt() {
        val group = "11111111-1111-4111-8111-111111111111"
        val album = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            group, com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
            "Get Up", "NewJeans", releaseKind = "EP",
        )
        val song = album.copy(
            musicBrainzId = "recording-1",
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            coverGroupMbid = group,
            artworkUri = "https://archive.org/another-group-front-500.jpg",
            artworkProvenance =
                com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_GROUP,
        )
        val owned = song.copy(musicBrainzId = "recording-own",
            artworkUri = "content://tracks/own.jpg", artworkProvenance = null)
        val url = "https://archive.org/verified-official-front-500.jpg"
        val corrected = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            listOf(album, song, owned), mapOf(group to url),
        )
        assertEquals(url, corrected[0].artworkUri)
        assertEquals(url, corrected[1].artworkUri)
        assertEquals("content://tracks/own.jpg", corrected[2].artworkUri)
    }

    @Test fun verifiedGroupCacheCorrectsWrongSameSizeAlbumCardAndKeepsDowngradeProtection() {
        val group = "11111111-1111-4111-8111-111111111111"
        val old = "https://archive.org/old-incorrect-edition-front-500.jpg"
        val approved = "https://archive.org/new-approved-group-front-500.jpg"
        val album = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            group, com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
            "Get Up", "NewJeans", releaseKind = "EP", artworkUri = old,
        )
        // A fresh verified group cache record is stronger than the existing
        // (potentially stale) 500px image in a reconstructed album card.
        val corrected = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            listOf(album), mapOf(group to approved),
        ).single()
        assertEquals(approved, corrected.artworkUri)
        assertEquals(corrected,
            com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
                listOf(corrected), mapOf(group to approved),
            ).single())
        // An old verified 250px thumb cannot replace the selected 500px card.
        assertEquals(corrected,
            com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
                listOf(corrected),
                mapOf(group to "https://coverartarchive.org/release/abc/front-250"),
            ).single())
        val other = album.copy(kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE,
            artworkUri = old)
        assertEquals(other,
            com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
                listOf(other), mapOf(group to approved),
            ).single())
    }

    @Test fun warmValidatedGroupBatchIncludesFullResolutionCardsForIdentityCorrection() {
        val album = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "group-1", com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP,
            "Get Up", "NewJeans", releaseKind = "EP",
            artworkUri = "https://archive.org/old-front-500.jpg",
        )
        val second = album.copy(musicBrainzId = "group-2",
            artworkUri = "https://archive.org/other-front-500.jpg")
        val otherVideo = album.copy(musicBrainzId = "group-video",
            releaseKind = "Other", artworkUri = null)
        val track = album.copy(
            musicBrainzId = "recording",
            kind = com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING,
            coverGroupMbid = album.musicBrainzId,
        )
        assertEquals(
            listOf("group-1", "group-2"),
            com.music.bitchord.ui.canonicalWarmGroupArtworkCandidateMbids(
                listOf(album, album, second, otherVideo, track), limit = 10,
            ),
        )
        assertEquals(
            listOf("group-1"),
            com.music.bitchord.ui.canonicalWarmGroupArtworkCandidateMbids(
                listOf(album, second), limit = 1,
            ),
        )
    }
}

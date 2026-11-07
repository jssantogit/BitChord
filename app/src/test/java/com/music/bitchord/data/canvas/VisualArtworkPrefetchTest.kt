package com.music.bitchord.data.canvas

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualArtworkPrefetchTest {
    @Test fun visibleHeroAndRowsGoBeforeBelowTheFoldWithoutProviderSpecificRules() {
        val cards = listOf(
            VisualArtworkCandidate("https://cdn.example/album2.jpg",
                VisualArtworkPriority.NEARBY, 300),
            VisualArtworkCandidate("https://cdn.example/album1.jpg",
                VisualArtworkPriority.VISIBLE, 300),
            VisualArtworkCandidate("https://cdn.example/artist.jpg",
                VisualArtworkPriority.HERO, 900),
            VisualArtworkCandidate("https://cdn.example/album3.jpg",
                VisualArtworkPriority.NEARBY, 300),
        )
        assertEquals(
            listOf("artist.jpg", "album1.jpg", "album2.jpg"),
            planVisualArtworkPrefetch(cards, maxRequests = 3)
                .map { requireNotNull(it.url).substringAfterLast('/') },
        )
    }

    @Test fun duplicatesAcrossDifferentShelvesProduceOneNetworkRequest() {
        val art = "https://coverartarchive.org/release/ABC/front-500"
        val planned = planVisualArtworkPrefetch(
            listOf(
                VisualArtworkCandidate(art, VisualArtworkPriority.NEARBY, 250),
                VisualArtworkCandidate(art, VisualArtworkPriority.HERO, 900),
                VisualArtworkCandidate(art, VisualArtworkPriority.VISIBLE, 500),
            ),
        )
        assertEquals(1, planned.size)
        assertEquals(VisualArtworkPriority.HERO, planned.single().priority)
        assertEquals(900, planned.single().targetPx)
    }

    @Test fun visibleHomeShelvesWarmCurrentCardsBeforeDistantShelves() {
        val groups = listOf(
            listOf("https://cdn.example/a.jpg"),
            listOf("https://cdn.example/b.jpg"),
            listOf("https://cdn.example/c.jpg"),
            listOf("https://cdn.example/d.jpg"),
        )
        val targets = planVisualShelfArtwork(
            shelfUrls = groups, visibleShelfIndex = 2,
            targetPx = 300, maxCandidates = 4,
        )
        assertEquals(
            listOf("c.jpg", "d.jpg", "b.jpg", "a.jpg"),
            targets.map { requireNotNull(it.url).substringAfterLast('/') },
        )
        assertEquals(VisualArtworkPriority.VISIBLE, targets.first().priority)
        assertEquals(VisualArtworkPriority.NEARBY, targets.last().priority)
    }

    @Test fun prefetchCoalescesSameImageAcrossSimultaneousSurfaces() = runBlocking {
        val gate = VisualArtworkFetchGate(maxConcurrent = 3)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val first = async {
            gate.fetchOnce("https://cdn.example/artist.jpg") {
                requests++
                started.complete(Unit)
                release.await()
            }
        }
        withTimeout(2_000) { started.await() }
        val second = async {
            gate.fetchOnce("https://cdn.example/artist.jpg") { requests++ }
        }
        assertFalse(withTimeout(2_000) { second.await() })
        release.complete(Unit)
        withTimeout(2_000) { first.await() }
        assertEquals(1, requests)
    }

    @Test fun cancelledPrefetchReleasesTheKeyForARealRetry() = runBlocking {
        val gate = VisualArtworkFetchGate(maxConcurrent = 2)
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val cancelled = async {
            gate.fetchOnce("https://cdn.example/a.jpg") {
                entered.complete(Unit)
                never.await()
            }
        }
        withTimeout(2_000) { entered.await() }
        cancelled.cancelAndJoin()
        var secondAttempts = 0
        assertTrue(withTimeout(2_000) {
            gate.fetchOnce("https://cdn.example/a.jpg") { secondAttempts++ }
        })
        assertEquals(1, secondAttempts)
    }

    @Test fun invalidOrLocalImageDoesNotJoinSpeculativeHttpQueue() {
        val planned = planVisualArtworkPrefetch(listOf(
            VisualArtworkCandidate("https://cdn.example/a.jpg", VisualArtworkPriority.VISIBLE, 250),
            VisualArtworkCandidate("http://insecure.example/a.jpg", VisualArtworkPriority.HERO, 900),
            VisualArtworkCandidate("content://media/external/123", VisualArtworkPriority.HERO, 900),
            VisualArtworkCandidate("https://127.0.0.1/secret", VisualArtworkPriority.HERO, 900),
        ))
        assertEquals(listOf("https://cdn.example/a.jpg"), planned.map { it.url })
        assertTrue(planVisualArtworkPrefetch(emptyList()).isEmpty())
    }
}

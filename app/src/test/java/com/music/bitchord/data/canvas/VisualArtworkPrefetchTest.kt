package com.music.bitchord.data.canvas

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

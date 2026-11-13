package com.music.bitchord.ui.screens

import com.music.bitchord.data.canvas.VisualArtworkPriority
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicPlaylistArtworkPriorityTest {
    @Test fun warmupPrioritizesHeroAndOnlyFirstVisibleVerifiedRows() {
        val songs = (1..20).map { number ->
            ListenBrainzPublicPlaylistTrack(
                null, "Song $number", "Artist",
                "https://coverartarchive.org/release/sleeve-$number/front",
            )
        }
        val warm = publicPlaylistWarmupCandidates(
            "https://api.listenbrainz.org/1/art/playlist/cover/opengraph/",
            songs,
        )
        assertEquals(9, warm.size)
        assertEquals(VisualArtworkPriority.HERO, warm.first().priority)
        assertEquals(8, warm.count { it.priority == VisualArtworkPriority.VISIBLE })
        assertEquals("https://coverartarchive.org/release/sleeve-8/front",
            warm.last().url)
    }

    @Test fun emptyPublicPlaylistStillWarmsItsExactHero() {
        val warm = publicPlaylistWarmupCandidates("https://cdn.example/hero.jpg", emptyList())
        assertEquals(1, warm.size)
        assertEquals(VisualArtworkPriority.HERO, warm.single().priority)
    }
}

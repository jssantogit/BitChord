package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the single native artist carousel used by catalog and Library. */
class NativeArtistSharedSectionsTest {
    @Test fun artistCarouselCapsToTwentyAndKeepsOriginalOrder() {
        val tracks = (1..27).map { "recording-$it" }
        val columns = nativeArtistTrackColumns(tracks)
        assertEquals(listOf(4, 4, 4, 4, 4), columns.map { it.size })
        assertEquals(tracks.take(20), columns.flatten())
    }

    @Test fun artistCarouselRespectsFilteredOrderAndPartialLastColumn() {
        val tracks = listOf("mbid-b", "mbid-a", "mbid-c", "mbid-d", "mbid-e")
        assertEquals(listOf(tracks.take(4), tracks.takeLast(1)),
            nativeArtistTrackColumns(tracks))
    }

    @Test fun emptyArtistDoesNotCreatePhantomTrackColumns() {
        assertTrue(nativeArtistTrackColumns(emptyList<String>()).isEmpty())
    }
}

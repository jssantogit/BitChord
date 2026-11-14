package com.music.bitchord

import com.music.bitchord.ui.appleArtistArtworkTasksForNewPages
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleArtistArtworkPagingTest {
    private fun album(id: Int) = AppleEditorialSearchRow(
        browseId = "itunes:br:album:$id",
        title = "Album $id", artistName = "Verified artist",
        artworkUri = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg",
        releaseDate = "2026-01-01",
    )

    @Test fun allTwentyFourCardsFromEveryPageAreQueuedNotOnlyTwelve() {
        val queued = mutableSetOf<String>()
        val first = appleArtistArtworkTasksForNewPages((1..24).map(::album), queued)
        val next = appleArtistArtworkTasksForNewPages((25..48).map(::album), queued)
        assertEquals(24, first.size)
        assertEquals(24, next.size)
        assertEquals(48, queued.size)
        assertEquals("itunes:br:album:48", next.last().second.browseId)
    }

    @Test fun revisitingOldPageDoesNotCancelOrDuplicateAlreadyQueuedArt() {
        val queued = mutableSetOf<String>()
        appleArtistArtworkTasksForNewPages((1..24).map(::album), queued)
        val fresh = appleArtistArtworkTasksForNewPages(
            (20..27).map(::album), queued,
        )
        assertEquals(listOf("itunes:br:album:25",
            "itunes:br:album:26", "itunes:br:album:27"),
            fresh.map { it.second.browseId })
        assertEquals(27, queued.size)
    }
}

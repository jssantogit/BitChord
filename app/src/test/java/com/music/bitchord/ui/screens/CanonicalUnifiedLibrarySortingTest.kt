package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.settings.LocalMusicSort
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalUnifiedLibrarySortingTest {
    private fun track(
        id: String,
        title: String,
        added: Long? = null,
        modified: Long? = null,
    ) = CanonicalBrowseTrack(
        recordingId = id,
        title = title,
        artistDisplay = "Artist",
        durationMs = 60_000L,
        artworkUri = null,
        availableAssets = listOf(CanonicalBrowseMediaAsset(id, "local-mediastore")),
        dateAddedSeconds = added,
        dateModifiedSeconds = modified,
    )

    @Test fun titleAscendingUsesTrackTitleInsteadOfArtistGrouping() {
        val input = listOf(track("c", "Zulu"), track("b", "Beta"), track("a", "Alpha"))
        assertEquals(listOf("a", "b", "c"),
            sortCanonicalUnifiedTracks(input, LocalMusicSort.TITLE_ASC).map { it.recordingId })
    }

    @Test fun titleDescendingIsStable() {
        val input = listOf(track("a", "Alpha"), track("z", "Zulu"))
        assertEquals(listOf("z", "a"),
            sortCanonicalUnifiedTracks(input, LocalMusicSort.TITLE_DESC).map { it.recordingId })
    }

    @Test fun addedDateDoesNotInventUnknownTimestamps() {
        val input = listOf(track("unknown", "Z"), track("old", "A", added = 11),
            track("new", "B", added = 22))
        assertEquals(listOf("new", "old", "unknown"),
            sortCanonicalUnifiedTracks(input, LocalMusicSort.DATE_ADDED).map { it.recordingId })
    }

    @Test fun modifiedDateDoesNotFallBackToAdded() {
        val input = listOf(track("unknown", "B", added = 400),
            track("modified", "A", modified = 8))
        assertEquals(listOf("modified", "unknown"),
            sortCanonicalUnifiedTracks(input, LocalMusicSort.DATE_MODIFIED).map { it.recordingId })
    }
}

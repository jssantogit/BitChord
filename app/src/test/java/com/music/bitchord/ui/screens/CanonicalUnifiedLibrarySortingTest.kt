package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalEntitySortDateRow
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

    private data class Entry(val id: String, val label: String)

    @Test fun albumAndArtistSortUseRealLinkedAssetDates() {
        val entries = listOf(Entry("unknown", "Zulu"), Entry("older", "Alpha"),
            Entry("newer", "Beta"))
        val dates = mapOf(
            "older" to CanonicalEntitySortDateRow("older", 10, 100),
            "newer" to CanonicalEntitySortDateRow("newer", 20, 50),
        )
        val added = sortCanonicalUnifiedEntities(
            entries, LocalMusicSort.DATE_ADDED, dates,
            id = { it.id }, title = { it.label },
        )
        assertEquals(listOf("newer", "older", "unknown"), added.map { it.id })

        val modified = sortCanonicalUnifiedEntities(
            entries, LocalMusicSort.DATE_MODIFIED, dates,
            id = { it.id }, title = { it.label },
        )
        assertEquals(listOf("older", "newer", "unknown"), modified.map { it.id })
        val reverse = sortCanonicalUnifiedEntities(
            entries, LocalMusicSort.TITLE_DESC, dates,
            id = { it.id }, title = { it.label },
        )
        assertEquals(listOf("unknown", "newer", "older"), reverse.map { it.id })
    }

    @Test fun nativeUnifiedHeaderDisplaysComputedHoursNotKotlinSource() {
        assertEquals("594 faixas · 29h 20m",
            nativeUnifiedTrackCountLabel(594, listOf(29L * 3_600_000 + 20L * 60_000)))
        assertEquals("1 faixa · 0h 03m",
            nativeUnifiedTrackCountLabel(1, listOf(180_000L)))
        assertEquals("2 faixas · 0h 01m",
            nativeUnifiedTrackCountLabel(2, listOf(30_000L, 30_000L)))
    }

    @Test fun missingDurationRemainsUnknownRatherThanInventingFullTotal() {
        assertEquals("2 faixas", nativeUnifiedTrackCountLabel(2, listOf(180_000L, null)))
        assertEquals("0 faixas", nativeUnifiedTrackCountLabel(0, emptyList()))
    }

    @Test fun modifiedDateDoesNotFallBackToAdded() {
        val input = listOf(track("unknown", "B", added = 400),
            track("modified", "A", modified = 8))
        assertEquals(listOf("modified", "unknown"),
            sortCanonicalUnifiedTracks(input, LocalMusicSort.DATE_MODIFIED).map { it.recordingId })
    }
}

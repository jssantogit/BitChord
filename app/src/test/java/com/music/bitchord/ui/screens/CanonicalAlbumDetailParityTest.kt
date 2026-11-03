package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.settings.SongSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real album detail follows DetailScreen release grammar, not Library rows. */
class CanonicalAlbumDetailParityTest {
    private fun track(id: String, title: String) = CanonicalBrowseTrack(
        recordingId = id,
        title = title,
        artistDisplay = "Example artist",
        durationMs = 180_000L,
        artworkUri = null,
        availableAssets = emptyList(),
    )

    @Test fun albumUsesRecordedReleaseRunningOrderNotLibraryAlphabeticalOrder() {
        val tracks = listOf(track("a", "Alpha"), track("b", "Beta"), track("c", "Charlie"))
        assertEquals(
            listOf("c", "a", "b"),
            canonicalAlbumDetailTrackOrder(tracks, listOf("c", "a", "b"))
                .map { it.recordingId },
        )
    }

    @Test fun missingReleaseSequenceRetainsExistingMembershipWithoutDroppingTracks() {
        val tracks = listOf(track("b", "Beta"), track("a", "Alpha"))
        assertEquals(
            listOf("a", "b"),
            canonicalAlbumDetailTrackOrder(tracks, listOf("a", "unknown", "b"))
                .map { it.recordingId },
        )
        assertEquals(tracks, canonicalAlbumDetailTrackOrder(tracks, emptyList()))
    }

    @Test fun verifiedReleaseEvidenceAppearsAsTypeYearAndPlayableTrackCount() {
        val meta = canonicalAlbumDetailMeta(
            primaryType = "EP",
            releaseDate = "2025-06-23",
            tracks = listOf(track("a", "One"), track("b", "Two")),
        )
        assertTrue(meta.startsWith("EP · 2025 · 2 faixas · "))
    }

    @Test fun missingEvidenceNeverInventsAnAlbumTypeOrYear() {
        val meta = canonicalAlbumDetailMeta(
            primaryType = null,
            releaseDate = "not a date",
            tracks = listOf(track("a", "One")),
        )
        assertTrue(meta.startsWith("1 faixa · "))
        assertFalse(meta.contains("2025"))
        assertFalse(meta.contains("EP"))
    }


    @Test fun releaseSortKeepsNativeOrderUntilUserExplicitlyChangesIt() {
        val runningOrder = listOf(track("b", "Beta"), track("a", "Alpha"))
        assertEquals(
            listOf("b", "a"),
            canonicalAlbumDetailSort(runningOrder, SongSort.DEFAULT).map { it.recordingId },
        )
        assertEquals(
            listOf("a", "b"),
            canonicalAlbumDetailSort(runningOrder, SongSort.TITLE_ASC).map { it.recordingId },
        )
        assertEquals(
            listOf("b", "a"),
            canonicalAlbumDetailSort(runningOrder, SongSort.TITLE_DESC).map { it.recordingId },
        )
    }

    @Test fun epSuffixIsOnlyHiddenWhenVerifiedSubtypeAgrees() {
        assertEquals("ALLDAY PROJECT", canonicalAlbumDisplayTitle("ALLDAY PROJECT - EP", "EP"))
        assertEquals("ALLDAY PROJECT - EP", canonicalAlbumDisplayTitle("ALLDAY PROJECT - EP", null))
        assertEquals("ALLDAY PROJECT - EP", canonicalAlbumDisplayTitle("ALLDAY PROJECT - EP", "Album"))
        assertEquals("A - EP Collection", canonicalAlbumDisplayTitle("A - EP Collection", "EP"))
    }
}

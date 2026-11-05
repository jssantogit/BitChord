package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopRecording
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistCredit
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.ui.canonicalVerifiedArtistTopRows
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalArtistPublicTopRowsTest {
    private val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
    private val otherArtist = "b7ffd2af-418f-4be2-bdd1-22f8b48613db"
    private val first = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val second = "22ad712e-ce73-9f0c-4e97-9f0e53144411"

    @Test fun onlyIndependentMusicBrainzIdentityAndArtistCreditsCanSupplyTopRows() {
        val ranked = listOf(
            ListenBrainzArtistTopRecording(first, listens = 100, listeners = 10),
            ListenBrainzArtistTopRecording(second, listens = 80, listeners = 9),
        )
        val metadata = mapOf(
            first to RecordingMetadataEnrichment(
                musicBrainzId = first, title = "Closer", artistDisplay = "Nine Inch Nails",
                artistCredits = listOf(
                    MusicBrainzArtistCredit(artist, "Nine Inch Nails"),
                    MusicBrainzArtistCredit(otherArtist, "Guest"),
                ),
            ),
            second to RecordingMetadataEnrichment(
                musicBrainzId = second, title = "Other artist track", artistDisplay = "Nine Inch Nails",
                artistCredits = listOf(MusicBrainzArtistCredit(otherArtist, "Nine Inch Nails")),
            ),
        )
        val result = canonicalVerifiedArtistTopRows(artist, ranked, metadata)
        assertEquals(listOf(first), result.map { it.musicBrainzId })
        val row = result.single()
        assertEquals(CanonicalSearchKind.RECORDING, row.kind)
        assertEquals(artist, row.artistScopeMbid)
        assertEquals("Closer", row.title)
        assertFalse(row.hasCatalogedAsset)
        assertEquals(null, row.artworkUri)
    }

    @Test fun mismatchedRecordingIdentityMissingCreditsAndUnsafeVersionsFailClosed() {
        val ranking = listOf(
            ListenBrainzArtistTopRecording(first, 100, 10),
            ListenBrainzArtistTopRecording(second, 99, 10),
        )
        val wrongIdentity = mapOf(
            first to RecordingMetadataEnrichment(
                musicBrainzId = second, title = "Wrong", artistDisplay = "Artist",
                artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist")),
            ),
        )
        assertTrue(canonicalVerifiedArtistTopRows(artist, ranking, wrongIdentity).isEmpty())
        val unproven = mapOf(
            first to RecordingMetadataEnrichment(first, "Song", artistDisplay = "Artist",
                artistCredits = null),
            second to RecordingMetadataEnrichment(second, "Song (fan edit)", artistDisplay = "Artist",
                artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist"))),
        )
        assertTrue(canonicalVerifiedArtistTopRows(artist, ranking, unproven).isEmpty())
        assertTrue(canonicalVerifiedArtistTopRows("invalid", ranking, unproven).isEmpty())
    }
}

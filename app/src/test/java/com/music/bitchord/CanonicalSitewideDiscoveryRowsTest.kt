package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistCredit
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.ui.canonicalVerifiedSitewideRows
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSitewideDiscoveryRowsTest {
    private val recording = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val other = "22ad712e-ce73-9f0c-4e97-9f0e53144411"
    private val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"

    @Test fun sitewideTitlesAndArtistsRequireIndependentMusicBrainzVerification() {
        val ranks = listOf(
            ListenBrainzSitewideRecording(recording, 120L),
            ListenBrainzSitewideRecording(other, 90L),
        )
        val verified = mapOf(
            recording to RecordingMetadataEnrichment(
                musicBrainzId = recording, title = "Closer", artistDisplay = "Nine Inch Nails",
                artistCredits = listOf(MusicBrainzArtistCredit(artist, "Nine Inch Nails")),
            ),
            other to RecordingMetadataEnrichment(
                musicBrainzId = other, title = "Dance practice", artistDisplay = "Fake",
                artistCredits = listOf(MusicBrainzArtistCredit(artist, "Fake")),
            ),
        )
        val rows = canonicalVerifiedSitewideRows(ranks, verified)
        assertEquals(listOf(recording), rows.map { it.musicBrainzId })
        assertEquals(CanonicalSearchKind.RECORDING, rows.single().kind)
        assertEquals("Nine Inch Nails", rows.single().subtitle)
        assertFalse(rows.single().hasCatalogedAsset)
        assertEquals(null, rows.single().artworkUri)
    }

    @Test fun titleOnlyOrConflictingRecordingIdentityNeverCreatesAPublicChartRow() {
        val rank = listOf(ListenBrainzSitewideRecording(recording, 100L))
        val wrongIdentity = mapOf(recording to RecordingMetadataEnrichment(
            musicBrainzId = other, title = "Some song", artistDisplay = "Artist",
            artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist")),
        ))
        assertTrue(canonicalVerifiedSitewideRows(rank, wrongIdentity).isEmpty())
        val missingArtistProof = mapOf(recording to RecordingMetadataEnrichment(
            musicBrainzId = recording, title = "Some song", artistDisplay = "Artist",
            artistCredits = emptyList(),
        ))
        assertTrue(canonicalVerifiedSitewideRows(rank, missingArtistProof).isEmpty())
    }
}

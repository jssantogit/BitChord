package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzRecordingRecommendation
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistCredit
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.ui.CanonicalPersonalRecommendationState
import com.music.bitchord.ui.canonicalPersonalRecommendationIsFresh
import com.music.bitchord.ui.canonicalRecommendationAccountKey
import com.music.bitchord.ui.canonicalRecommendationBelongsToToken
import com.music.bitchord.ui.canonicalVerifiedPersonalRecommendationRows
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalPersonalRecommendationRowsTest {
    private val recording = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val other = "22ad712e-ce73-9f0c-4e97-9f0e53144411"
    private val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
    private val ranks = listOf(
        ListenBrainzRecordingRecommendation(recording, 9.2),
        ListenBrainzRecordingRecommendation(other, 7.4),
    )

    @Test fun onlyExactMusicBrainzRecordingAndArtistProofYieldMetadataCards() {
        val known = mapOf(
            recording to RecordingMetadataEnrichment(
                musicBrainzId = recording,
                title = "Spring Day",
                artistDisplay = "BTS",
                artistCredits = listOf(MusicBrainzArtistCredit(artist, "BTS")),
            ),
            other to RecordingMetadataEnrichment(
                musicBrainzId = "fb75403a-1111-2222-3333-444444444444",
                title = "Conflicting song",
                artistDisplay = "Other",
                artistCredits = listOf(MusicBrainzArtistCredit(artist, "Other")),
            ),
        )
        val rows = canonicalVerifiedPersonalRecommendationRows(ranks, known)
        assertEquals(listOf(recording), rows.map { it.musicBrainzId })
        assertEquals(CanonicalSearchKind.RECORDING, rows.single().kind)
        assertFalse(rows.single().hasCatalogedAsset)
        assertEquals(null, rows.single().artworkUri)
    }

    @Test fun anAccountSwitchRevokesOldRecommendationVisibilityImmediately() {
        val state = CanonicalPersonalRecommendationState(
            accountDigest = canonicalRecommendationAccountKey("old-token"),
            recordings = ranks,
        )
        assertTrue(canonicalRecommendationBelongsToToken(state, "old-token"))
        assertFalse(canonicalRecommendationBelongsToToken(state, "new-token"))
        assertFalse(canonicalRecommendationBelongsToToken(state, ""))
    }

    @Test fun aSnapshotDoesNotRemainFreshAfterCacheOrUpstreamExpiry() {
        val now = 1_783_000_000_000L
        val state = CanonicalPersonalRecommendationState(
            fetchedAtMs = now - 1000L,
            upstreamUpdatedSeconds = now / 1000L - 60L,
            recordings = ranks,
        )
        assertTrue(canonicalPersonalRecommendationIsFresh(state, now))
        assertFalse(canonicalPersonalRecommendationIsFresh(state, now + 61L * 60L * 1000L))
        assertFalse(canonicalPersonalRecommendationIsFresh(
            state.copy(upstreamUpdatedSeconds = now / 1000L - 40L * 24L * 60L * 60L),
            now,
        ))
    }
}

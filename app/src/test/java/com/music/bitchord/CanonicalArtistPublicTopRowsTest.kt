package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopRecording
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistCredit
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.ui.canonicalVerifiedArtistTopRows
import com.music.bitchord.ui.canonicalArtistSampledPopularityRanking
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.ui.CanonicalArtistPublicTopState
import com.music.bitchord.ui.canonicalArtistPublicTopIsFresh
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

    @Test fun artistPublicRankIsFreshOnlyWithinSameArtistAndSixHourWindow() {
        val row = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            musicBrainzId = first,
            kind = CanonicalSearchKind.RECORDING,
            title = "Song",
            subtitle = "Artist",
            artistScopeMbid = artist,
        )
        val state = CanonicalArtistPublicTopState(
            artistMbid = artist, rows = listOf(row), fetchedAtMs = 1_000L,
        )
        assertTrue(canonicalArtistPublicTopIsFresh(artist.uppercase(), state, 1_000L))
        assertTrue(canonicalArtistPublicTopIsFresh(artist, state, 1_000L + 60_000L))
        assertFalse(canonicalArtistPublicTopIsFresh(otherArtist, state, 1_001L))
        assertFalse(canonicalArtistPublicTopIsFresh(artist, state,
            1_000L + 6L * 60 * 60 * 1_000L))
        assertFalse(canonicalArtistPublicTopIsFresh(artist, state, 999L))
        assertFalse(canonicalArtistPublicTopIsFresh(artist, state.copy(fetchedAtMs = null),
            1_000L))
        // A successful empty upstream chart is still fresh evidence of no rank.
        assertTrue(canonicalArtistPublicTopIsFresh(artist,
            state.copy(rows = emptyList()), 1_000L + 100L))
    }

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

    @Test fun rankedSampleUsesRealListenerCountsAndOnlyRequestedMbids() {
        val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
        val sample = listOf(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        val rows = canonicalArtistSampledPopularityRanking(
            sample,
            listOf(
                ListenBrainzPopularityEvidence.Known(sample[0], 50, 5),
                ListenBrainzPopularityEvidence.Known(sample[1], 300, 30),
                ListenBrainzPopularityEvidence.Known(
                    "33333333-3333-4333-8333-333333333333", 999, 100),
            ),
        )
        assertEquals(listOf(sample[1], sample[0]), rows.map { it.recordingMbid })
        assertTrue(rows.all { it.listeners > 0L && it.listens >= it.listeners })
        assertTrue(canonicalArtistSampledPopularityRanking(sample,
            listOf(ListenBrainzPopularityEvidence.Unknown(sample[0]))).isEmpty())
    }

    @Test fun verifiedRankedRecordingRetainsUpstreamCaaCoverWithoutPlayableAsset() {
        val artwork = "https://coverartarchive.org/release/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/123-250.jpg"
        val verified = RecordingMetadataEnrichment(
            musicBrainzId = first,
            title = "Verified track",
            artistDisplay = "Artist",
            artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist")),
        )
        val rows = canonicalVerifiedArtistTopRows(
            artist,
            listOf(ListenBrainzArtistTopRecording(first, 120, 15, artworkUri = artwork)),
            mapOf(first to verified),
        )
        assertEquals(artwork, rows.single().artworkUri)
        assertFalse(rows.single().hasCatalogedAsset)
    }

}

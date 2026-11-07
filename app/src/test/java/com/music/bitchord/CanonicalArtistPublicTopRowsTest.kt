package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopRecording
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.ui.canonicalArtistSitewideAllTimeRanking
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistCredit
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.ui.canonicalVerifiedArtistTopRows
import com.music.bitchord.ui.canonicalArtistTopPlaybackDecoration
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
    private val officialRelease = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

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
                officialAudioReleaseMbid = officialRelease,
                artistCredits = listOf(
                    MusicBrainzArtistCredit(artist, "Nine Inch Nails"),
                    MusicBrainzArtistCredit(otherArtist, "Guest"),
                ),
            ),
            second to RecordingMetadataEnrichment(
                musicBrainzId = second, title = "Other artist track", artistDisplay = "Nine Inch Nails",
                officialAudioReleaseMbid = officialRelease,
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
        assertTrue(rows.all { row -> row.listeners?.let { n -> n > 0L && row.listens >= n } == true })
        assertTrue(canonicalArtistSampledPopularityRanking(sample,
            listOf(ListenBrainzPopularityEvidence.Unknown(sample[0]))).isEmpty())
    }

    @Test fun verifiedRankedRecordingRetainsUpstreamCaaCoverWithoutPlayableAsset() {
        val artwork = "https://coverartarchive.org/release/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/123-250.jpg"
        val verified = RecordingMetadataEnrichment(
            musicBrainzId = first,
            title = "Verified track",
            artistDisplay = "Artist", officialAudioReleaseMbid = officialRelease,
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


    @Test fun chartCannotDisplayUnknownEditionOrLiveVersionEvenWithRealListenerCounts() {
        val ranked = listOf(
            ListenBrainzArtistTopRecording(first, 400, 24),
            ListenBrainzArtistTopRecording(second, 300, 20),
        )
        val notOfficial = RecordingMetadataEnrichment(
            musicBrainzId = first,
            title = "Official title, but no edition evidence",
            artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist")),
        )
        val live = RecordingMetadataEnrichment(
            musicBrainzId = second,
            title = "Burning Up (FIRE) - Live",
            officialAudioReleaseMbid = officialRelease,
            artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist")),
        )
        assertTrue(canonicalVerifiedArtistTopRows(
            artist, ranked, mapOf(first to notOfficial, second to live),
        ).isEmpty())
        assertEquals(listOf(first), canonicalVerifiedArtistTopRows(
            artist, ranked, mapOf(first to notOfficial.copy(
                officialAudioReleaseMbid = officialRelease,
            ), second to live),
        ).map { it.musicBrainzId })
    }


    @Test fun artistChartGetsPlayableRowOnlyFromExactCanonicalAssetMbid() {
        val metadata = RecordingMetadataEnrichment(
            musicBrainzId = first,
            title = "Real studio track",
            officialAudioReleaseMbid = officialRelease,
            artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist")),
        )
        val ranked = canonicalVerifiedArtistTopRows(
            artist, listOf(ListenBrainzArtistTopRecording(first, 100, 20)),
            mapOf(first to metadata),
        )
        assertFalse(ranked.single().hasCatalogedAsset)
        assertTrue(canonicalArtistTopPlaybackDecoration(
            ranked, setOf(first),
        ).single().hasCatalogedAsset)
        assertFalse(canonicalArtistTopPlaybackDecoration(
            ranked, setOf(second),
        ).single().hasCatalogedAsset)
    }


    @Test fun sitewideAllTimeChartPrecedesArbitrarySearchSamplingAndChecksExactArtist() {
        val candidates = listOf(
            ListenBrainzSitewideRecording(first, 220, listOf(artist)),
            ListenBrainzSitewideRecording(second, 550, listOf(otherArtist)),
            ListenBrainzSitewideRecording(
                "33333333-3333-4333-8333-333333333333", 320, listOf(artist)),
            ListenBrainzSitewideRecording(
                "44444444-4444-4444-8444-444444444444", 9000),
        )
        val ranked = canonicalArtistSitewideAllTimeRanking(artist, candidates)
        assertEquals(listOf("33333333-3333-4333-8333-333333333333", first),
            ranked.map { it.recordingMbid })
        assertEquals(listOf(320L, 220L), ranked.map { it.listens })
        assertTrue(ranked.all { it.listeners == null })
        val studio = RecordingMetadataEnrichment(
            musicBrainzId = first, title = "Famous studio song",
            officialAudioReleaseMbid = officialRelease,
            artistCredits = listOf(MusicBrainzArtistCredit(artist, "Artist")),
        )
        assertEquals(listOf(first), canonicalVerifiedArtistTopRows(
            artist, ranked, mapOf(first to studio),
        ).map { it.musicBrainzId })
        assertTrue(canonicalArtistSitewideAllTimeRanking(otherArtist,
            candidates.filter { it.artistMbids.isEmpty() }).isEmpty())
    }


    @Test fun sampledPopularityScansWiderArtistCorpusAndOrdersByActualListenCounts() {
        val candidates = (1..75).map { n ->
            n.toString(16).padStart(8, '0') + "-1111-4111-8111-111111111111"
        }
        val popularity = candidates.mapIndexed { index, id ->
            ListenBrainzPopularityEvidence.Known(
                id, listens = if (index == 60) 10_000L else (index + 100).toLong(),
                listeners = if (index == 60) 1L else (index + 10).toLong(),
            )
        }
        val ranked = canonicalArtistSampledPopularityRanking(candidates, popularity)
        assertEquals(candidates[60], ranked.first().recordingMbid)
        assertEquals(10_000L, ranked.first().listens)
        assertEquals(8, ranked.size)
    }

}

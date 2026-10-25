package com.music.bitchord

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.canonicalCuratedHomeShelves
import com.music.bitchord.ui.canonicalLocalPickCardId
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical #178: YT-off feeds must use real data, old Home cards and no fake charts. */
class CanonicalCuratedHomeFeedTest {
    private val release = CanonicalCatalogSearchRow(
        "00000000-0000-0000-0000-000000000001", CanonicalSearchKind.RELEASE_GROUP,
        "SUPER REAL ME", "ILLIT", fromOfflineCache = true,
        releaseDate = "2024-03-25", releaseKind = "EP",
    )
    private val local = Song("local-123", "Magnetic", "ILLIT", null, localUri = "content://media/audio/123")
    private val playable = CanonicalCatalogSearchRow(
        "00000000-0000-0000-0000-000000000002", CanonicalSearchKind.RECORDING,
        "Magnetic", "ILLIT", hasCatalogedAsset = true, fromOfflineCache = true,
    )
    private fun feed(metadata: List<CanonicalCatalogSearchRow>, picks: List<Song> = emptyList(),
                     trending: List<String> = emptyList()) = canonicalCuratedHomeShelves(
        savedReleases = emptyList(), metadata = metadata,
        localPicks = picks, trendingRecordingMbids = trending,
        releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
        trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
    )

    @Test fun showsNativeReleasePickAndEvidenceBasedTrendingShelvesInEditorialOrder() {
        val shelves = feed(listOf(release, playable), listOf(local), listOf(playable.musicBrainzId))
        assertEquals(listOf("Lançamentos", "Escolha a dedo", "Em alta"), shelves.map { it.title })
        assertEquals("catalog:RELEASE_GROUP:${release.musicBrainzId}", shelves[0].items.single().browseId)
        assertEquals(null, shelves[0].items.single().videoId)
        assertEquals(canonicalLocalPickCardId(local), shelves[1].items.single().browseId)
        assertEquals(null, shelves[1].items.single().videoId)
        assertEquals("catalog:RECORDING:${playable.musicBrainzId}", shelves[2].items.single().browseId)
    }

    @Test fun cannotClaimChartsWithoutListeningEvidenceOrAudioAsset() {
        val shelves = feed(listOf(release, playable.copy(hasCatalogedAsset = false)),
            listOf(local.copy(localUri = null)), listOf(playable.musicBrainzId))
        assertEquals(listOf("Lançamentos"), shelves.map { it.title })
        assertFalse(shelves.any { it.title == "Em alta" })
    }

    @Test fun knownFutureEditionsAreNotPromotedAndReleaseGroupTakesPrecedence() {
        val future = release.copy(musicBrainzId = "future", title = "Album 2027", releaseDate = "2027-01-01")
        val older = release.copy(musicBrainzId = "older", title = "Older", releaseDate = "2020-01-01")
        val edition = release.copy(kind = CanonicalSearchKind.RELEASE, musicBrainzId = "edition")
        val items = feed(listOf(older, future, edition, release)).first().items
        assertEquals(listOf("SUPER REAL ME", "Older"), items.map { it.title })
    }

    @Test fun onlyTrustedLocalUrisCanEnterPickCarousel() {
        val candidates = listOf(
            local,
            local.copy(videoId = "remote", localUri = "https://invalid/path"),
            local.copy(videoId = "legacy-yt", localUri = null),
            local.copy(videoId = "another", localUri = "file:///Music/another.mp3"),
        )
        val shelf = feed(emptyList(), candidates).single()
        assertEquals("Escolha a dedo", shelf.title)
        assertEquals(2, shelf.items.size)
        assertTrue(shelf.items.all { it.videoId == null && it.browseId!!.startsWith("local:pick:") })
    }
}

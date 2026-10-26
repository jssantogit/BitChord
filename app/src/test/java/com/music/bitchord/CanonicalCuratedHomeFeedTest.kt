package com.music.bitchord

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.canonicalCuratedHomeShelves
import com.music.bitchord.ui.canonicalCoverLookupTarget
import com.music.bitchord.ui.canonicalCoverLookupCandidates
import com.music.bitchord.ui.canonicalLocalPickCardId
import com.music.bitchord.ui.canonicalRemotePickCardId
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.ui.screens.CanonicalServerSearchRow
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

    @Test fun existingReleasedEditionIsVisibleWhenEveryCachedGroupIsFuture() {
        val futureGroup = release.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000009",
            title = "Future release", releaseDate = "2027-01-01",
        )
        val releasedEdition = release.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000010",
            kind = CanonicalSearchKind.RELEASE, title = "Existing EP",
            releaseDate = "2024-03-25",
        )
        val shelves = feed(listOf(futureGroup, releasedEdition))
        assertEquals(listOf("Lançamentos"), shelves.map { it.title })
        assertEquals("catalog:RELEASE:${releasedEdition.musicBrainzId}",
            shelves.single().items.single().browseId)
    }

    @Test fun locallyRepeatedSongsCanTrendWithoutAnyMusicBrainzMatch() {
        val shelves = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = emptyList(),
            localPicks = listOf(local), trendingRecordingMbids = emptyList(),
            trendingLocalPicks = listOf(local),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
        )
        assertEquals(listOf("Escolha a dedo", "Em alta"), shelves.map { it.title })
        assertEquals(canonicalLocalPickCardId(local), shelves.last().items.single().browseId)
    }

    @Test fun distinctFilesWithTheSameLegacySongIdKeepDistinctPrivateCardKeys() {
        val second = local.copy(
            title = "Magnetic (Live)", localUri = "content://media/audio/456",
        )
        val firstCardId = canonicalLocalPickCardId(local)
        val secondCardId = canonicalLocalPickCardId(second)
        assertFalse(firstCardId == secondCardId)
        assertTrue(firstCardId.startsWith("local:pick:"))
        assertFalse(firstCardId.contains("content://"))
        assertFalse(firstCardId.contains(local.videoId))
        val items = feed(emptyList(), listOf(local, second)).single().items
        assertEquals(2, items.size)
        assertEquals(2, items.map { it.browseId }.distinct().size)
    }

    @Test fun picksIncludeVerifiedServerAssetsWhenDeviceHasNoLocalFiles() {
        val remote = CanonicalServerSearchRow(
            recordingId = "recording-nav-1", mediaAssetId = "asset-nav-1",
            providerKey = "opensubsonic:test-account", title = "Magnetic", artistDisplay = "ILLIT",
            artworkUri = "https://example.com/cover.png",
        )
        val result = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = emptyList(), localPicks = emptyList(),
            trendingRecordingMbids = emptyList(),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
            remotePicks = listOf(remote),
        )
        assertEquals(listOf("Escolha a dedo"), result.map { it.title })
        val card = result.single().items.single()
        assertEquals("Magnetic", card.title)
        assertEquals(remote.artworkUri, card.thumbnailUrl)
        assertEquals(canonicalRemotePickCardId(remote), card.browseId)
        assertEquals(null, card.videoId)
        assertFalse(card.browseId!!.contains(remote.mediaAssetId))
    }

    @Test fun twoServerAssetsForSameRecordingRetainIndependentNavigationKeys() {
        val one = CanonicalServerSearchRow(
            recordingId = "recording-1", mediaAssetId = "asset-1",
            providerKey = "opensubsonic:account", title = "OMG", artistDisplay = "NewJeans",
        )
        val two = one.copy(mediaAssetId = "asset-2")
        assertFalse(canonicalRemotePickCardId(one) == canonicalRemotePickCardId(two))
        val cards = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = emptyList(), localPicks = emptyList(),
            trendingRecordingMbids = emptyList(), remotePicks = listOf(one, two),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
        ).single().items
        assertEquals(2, cards.map { it.browseId }.distinct().size)
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

    @Test fun artworkEnrichmentOnlyRequestsIdentitySafeReleaseOrGroupCovers() {
        val edition = release.copy(kind = CanonicalSearchKind.RELEASE,
            musicBrainzId = "00000000-0000-0000-0000-000000000003")
        val artist = release.copy(kind = CanonicalSearchKind.ARTIST,
            musicBrainzId = "00000000-0000-0000-0000-000000000004")
        val recording = release.copy(kind = CanonicalSearchKind.RECORDING,
            musicBrainzId = "00000000-0000-0000-0000-000000000005")
        assertEquals(null to release.musicBrainzId, canonicalCoverLookupTarget(release))
        assertEquals(edition.musicBrainzId to null, canonicalCoverLookupTarget(edition))
        assertEquals(null, canonicalCoverLookupTarget(artist))
        assertEquals(null, canonicalCoverLookupTarget(recording))
        val candidates = canonicalCoverLookupCandidates(
            listOf(artist, release, release, recording, edition),
            limit = 2,
        )
        assertEquals(listOf(release, edition), candidates)
    }
}

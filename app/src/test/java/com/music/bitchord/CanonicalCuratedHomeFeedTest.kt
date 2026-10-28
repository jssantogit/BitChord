package com.music.bitchord

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.canonicalCuratedHomeShelves
import com.music.bitchord.ui.canonicalCoverLookupTarget
import com.music.bitchord.ui.canonicalCoverLookupCandidates
import com.music.bitchord.ui.canonicalArtistArtworkCandidate
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

    @Test fun unclassifiedReleaseGroupsAndUntypedEditionsCannotPretendToBeNewAlbums() {
        val unknownGroup = release.copy(
            musicBrainzId = "right-now-other",
            title = "Right Now", releaseKind = "Other",
        )
        val unknownEdition = release.copy(
            musicBrainzId = "right-now-edition",
            kind = CanonicalSearchKind.RELEASE, releaseKind = null,
        )
        val recording = playable.copy(title = "Hype Boy")
        val knownSingle = release.copy(
            musicBrainzId = "certified-single", title = "Super Shy", releaseKind = "Single", releaseDate = "2025-01-01",
        )
        val shelves = feed(listOf(unknownGroup, unknownEdition, recording, release, knownSingle))
        assertEquals(listOf("Super Shy", "SUPER REAL ME"), shelves.single().items.map { it.title })
        assertTrue(shelves.single().items.all { it.browseId!!.contains("RELEASE_GROUP") })
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

    @Test fun editionCannotBorrowPotentiallyWrongGroupArtworkWhenItsOwnFrontIsMissing() {
        val edition = release.copy(
            kind = CanonicalSearchKind.RELEASE,
            musicBrainzId = "33333333-3333-4333-8333-333333333333",
            coverGroupMbid = "44444444-4444-4444-8444-444444444444",
        )
        // The group's CAA Front may be a video poster from a different
        // edition; a specific Release must show its own sleeve or none.
        assertEquals(
            edition.musicBrainzId to null,
            canonicalCoverLookupTarget(edition),
        )
        assertEquals(
            edition.musicBrainzId to null,
            canonicalCoverLookupTarget(edition.copy(coverGroupMbid = null)),
        )
    }

    @Test fun onlyOneExactArtistGetsDecorativeSearchPortrait() {
        val first = release.copy(kind = CanonicalSearchKind.ARTIST,
            musicBrainzId = "artist-main", title = "ILLIT", artworkUri = null)
        val namesake = first.copy(musicBrainzId = "artist-homonym")
        val unrelated = first.copy(musicBrainzId = "other", title = "ILLIT Remix")
        assertEquals(first, canonicalArtistArtworkCandidate(
            listOf(unrelated, first, namesake), "illit",
        ))
        assertEquals(null, canonicalArtistArtworkCandidate(
            listOf(unrelated), "illit",
        ))
        assertEquals(null, canonicalArtistArtworkCandidate(
            listOf(first.copy(artworkUri = "https://example.com/artist.jpg")), "illit",
        ))
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
        // A recording is permitted a cover only from a MusicBrainz Release
        // explicitly associated with it, never a title-only assumption.
        val linkedRelease = "22222222-2222-4222-8222-222222222222"
        assertEquals(linkedRelease to null, canonicalCoverLookupTarget(
            recording.copy(coverReleaseMbid = linkedRelease),
        ))
        val candidates = canonicalCoverLookupCandidates(
            listOf(artist, release, release, recording, edition),
            limit = 2,
        )
        // Never fan out to MB for unverified recording editions: this could
        // fetch performance-video stills and stall every thumbnail.
        assertEquals(listOf(release, edition), candidates)
        assertEquals(listOf(recording.copy(coverReleaseMbid = linkedRelease)),
            canonicalCoverLookupCandidates(
                listOf(recording.copy(coverReleaseMbid = linkedRelease)), limit = 2))

    }
    @Test fun legacyCaaFront250WithoutJpgIsStillRecognizedAsSmallArtwork() {
        assertTrue(com.music.bitchord.ui.canonicalNeedsCoverRefresh(
            "https://coverartarchive.org/release/00000000-0000-0000-0000-000000000001/front-250"))
        assertTrue(com.music.bitchord.ui.canonicalNeedsCoverRefresh(
            "https://archive.org/download/cover/front-250.jpg"))
        assertFalse(com.music.bitchord.ui.canonicalNeedsCoverRefresh(
            "https://coverartarchive.org/release/00000000-0000-0000-0000-000000000001/front-1200"))
    }


    @Test fun artistAlbumThumb250CanUpgradeWithoutBlankingOrDowngradingVerifiedCover() {
        val id = release.musicBrainzId
        val low = "https://coverartarchive.org/release/$id/front-250"
        val high = "https://coverartarchive.org/release/$id/front-500"
        val artistShelves = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST,
            listOf(release.copy(artworkUri = low)),
            heading = "EPs",
        )
        val upgraded = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            artistShelves, mapOf(id to (high to "SUPER REAL ME")),
        )
        assertEquals(high, upgraded.single().items.single().thumbnailUrl)
        assertEquals(artistShelves.single().items.single().browseId,
            upgraded.single().items.single().browseId)
        val discardedDowngrade = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            upgraded, mapOf(id to (low to "SUPER REAL ME")),
        )
        assertTrue("Already displayed 500px art must retain its shelf object",
            upgraded === discardedDowngrade)
        val missing = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST,
            listOf(release.copy(artworkUri = null)),
            heading = "EPs",
        )
        val temporary = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            missing, mapOf(id to (low to "SUPER REAL ME")),
        )
        assertEquals(low, temporary.single().items.single().thumbnailUrl)
    }

    @Test fun artistArtworkRefreshEligibilityRequiresAnActualImageUpgrade() {
        val id = release.musicBrainzId
        val low = "https://coverartarchive.org/release/$id/front-250"
        val high = "https://coverartarchive.org/release/$id/front-500"
        assertTrue(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(null, low))
        assertTrue(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(low, high))
        assertFalse(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(high, low))
        assertFalse(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(low, low))
        assertFalse(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(high, high))
    }

    @Test fun laterValidatedOfficialFrontCanCorrectStaleEqualResolutionArtistSleeve() {
        val id = release.musicBrainzId
        val stale = "https://archive.org/earlier-incorrect-front-500.jpg"
        val verified = "https://archive.org/current-official-front-500.jpg"
        val shelves = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST,
            listOf(release.copy(artworkUri = stale)),
            heading = "EPs",
        )
        val corrected = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            shelves, mapOf(id to (verified to "SUPER REAL ME")),
        )
        assertEquals(verified, corrected.single().items.single().thumbnailUrl)
        assertTrue(corrected === com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            corrected, mapOf(id to (verified to "SUPER REAL ME")),
        ))
        val low = "https://coverartarchive.org/release/$id/front-250"
        assertTrue(corrected === com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            corrected, mapOf(id to (low to "SUPER REAL ME")),
        ))
    }

    @Test fun artistWarmCacheRevalidatesFullSizeGroupSleevesWithoutPaintingOtherOrForeignGroups() {
        val stale500 = "https://archive.org/older-edition-front-500.jpg"
        val official500 = "https://archive.org/verified-official-front-500.jpg"
        val otherId = "00000000-0000-0000-0000-000000000013"
        val foreignId = "00000000-0000-0000-0000-000000000014"
        val rows = listOf(
            release.copy(artworkUri = stale500),
            release.copy(musicBrainzId = otherId, title = "Video release",
                releaseKind = "Other", artworkUri = stale500),
        )
        assertEquals(listOf(release.musicBrainzId),
            com.music.bitchord.ui.canonicalWarmGroupArtworkCandidateMbids(rows, 400))
        val updates = com.music.bitchord.ui.canonicalArtistVerifiedGroupUpdates(
            rows,
            mapOf(release.musicBrainzId to official500,
                otherId to "https://archive.org/video-front-500.jpg",
                foreignId to "https://archive.org/foreign-front-500.jpg"),
        )
        assertEquals(mapOf(release.musicBrainzId to (official500 to release.title)), updates)
        val corrected = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            rows, updates.mapValues { it.value.first },
        )
        assertEquals(official500, corrected[0].artworkUri)
        assertEquals(stale500, corrected[1].artworkUri)
        assertEquals(corrected, com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            corrected, updates.mapValues { it.value.first },
        ))
        val shelves = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST, rows, heading = "EPs",
        )
        val painted = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            shelves, updates,
        )
        assertEquals(official500, painted.single().items.single().thumbnailUrl)
    }
}

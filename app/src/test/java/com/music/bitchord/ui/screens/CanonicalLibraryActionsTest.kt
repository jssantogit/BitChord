package com.music.bitchord.ui.screens

import com.music.bitchord.data.listening.CanonicalSmartFilterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryActionsTest {
    @Test fun albumDetailPlaylistActionUsesEntireCanonicalReleaseNotVisibleSongFilter() {
        val selection = canonicalAlbumDetailPlaylistSelection(
            CanonicalSmartFilterContext.ALBUMS, "canonical-release-id",
        )
        assertEquals(listOf(
            CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, "canonical-release-id"),
        ), selection)
        // This action must not turn a search's currently visible tracks into
        // a partial album or manufacture one RecordingId per visual row.
    }

    @Test fun albumDetailPlaylistActionCannotLeakToOtherCategoriesOrBlankIdentity() {
        assertEquals(null, canonicalAlbumDetailPlaylistSelection(
            CanonicalSmartFilterContext.SONGS, "canonical-release-id",
        ))
        assertEquals(null, canonicalAlbumDetailPlaylistSelection(
            CanonicalSmartFilterContext.ARTISTS, "canonical-release-id",
        ))
        assertEquals(null, canonicalAlbumDetailPlaylistSelection(
            CanonicalSmartFilterContext.ALBUMS, null,
        ))
        assertEquals(null, canonicalAlbumDetailPlaylistSelection(
            CanonicalSmartFilterContext.ALBUMS, "  ",
        ))
    }

    @Test fun selectedAlbumAndArtistCountResolvedRecordingsRatherThanCards() {
        val summary = canonicalLibrarySelectionSummary(
            selectedEntities = 2,
            result = CanonicalLibraryResolvedSelection(
                recordingIds = listOf("rec-1", "rec-2", "rec-3"),
                unavailableEntities = 0,
            ),
        )
        assertEquals("3 faixas · 2 itens", summary)
        assertEquals(
            "Calculando faixas…",
            canonicalLibrarySelectionSummary(selectedEntities = 2, result = null),
        )
        assertEquals(
            "1 faixa · 1 item indisponível",
            canonicalLibrarySelectionSummary(
                selectedEntities = 2,
                result = CanonicalLibraryResolvedSelection(
                    recordingIds = listOf("rec-1"), unavailableEntities = 1,
                ),
            ),
        )
    }

    @Test fun changingTargetPlaylistSourceDuringValidationFailsClosed() {
        assertTrue(canonicalPlaylistTargetSourceUnchanged("opensubsonic:a", "opensubsonic:a"))
        assertTrue(canonicalPlaylistTargetSourceUnchanged(null, null))
        assertFalse(canonicalPlaylistTargetSourceUnchanged(null, "opensubsonic:b"))
        assertFalse(canonicalPlaylistTargetSourceUnchanged("opensubsonic:a", "opensubsonic:b"))
    }

    @Test fun preservingOrderAndSourceEligibilityWhenAddingExistingMembers() {
        val plan = planCanonicalPlaylistAppend(
            current = listOf("old", "duplicate"),
            requested = listOf("new", "duplicate", "new", "remote", "another"),
            eligibleRecordingIds = setOf("old", "duplicate", "new", "another"),
        )
        assertEquals(listOf("old", "duplicate", "new", "another"), plan.updatedIds)
        assertEquals(listOf("new", "another"), plan.addedIds)
        assertEquals(1, plan.unavailableCount)
    }

    @Test fun untrustedSelectionAndWrongSourcesNeverEnterAPersistedPlaylist() {
        val plan = planCanonicalPlaylistAppend(
            current = listOf("old"),
            requested = listOf("remote-only", "deleted"),
            eligibleRecordingIds = setOf("old"),
        )
        assertEquals(listOf("old"), plan.updatedIds)
        assertEquals(0, plan.addedIds.size)
        assertEquals(2, plan.unavailableCount)
    }

    @Test fun selectingArtistAndAlbumDeduplicatesByRecordingNotByTitle() {
        val ids = canonicalLibraryBatchRecordingIds(
            listOf(listOf("a", "b"), listOf("b", "c"), listOf("a", "d")),
        )
        assertEquals(listOf("a", "b", "c", "d"), ids)
    }

    @Test fun selectionKeepsTypedCanonicalIdentityAndTogglesWithoutDuplicates() {
        val artist = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "artist-id")
        val album = CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, "artist-id")
        val selected = toggleCanonicalLibrarySelection(emptyList(), artist)
        assertEquals(listOf(artist, album), toggleCanonicalLibrarySelection(selected, album))
        assertTrue(toggleCanonicalLibrarySelection(selected, artist).isEmpty())
    }

    @Test fun artistRatingsAreTypedAndNeverImpersonateRecordingRatings() {
        val artist = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "verified-artist-id")
        val album = CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, "verified-release-id")
        val song = CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, "verified-recording-id")
        assertEquals("ARTIST", canonicalEntityRatingTarget(artist)?.entityType)
        assertEquals("ALBUM", canonicalEntityRatingTarget(album)?.entityType)
        assertEquals(null, canonicalEntityRatingTarget(song))
        // Display-name group is a *separate* rating identity, not a
        // fabricated canonical ArtistId or a RecordingId.
        val group = canonicalEntityRatingTarget(
            CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST,
                playlistDisplayArtistGroupId("aespa")),
        )
        assertEquals("DISPLAY_ARTIST", group?.entityType)
        assertEquals(playlistDisplayArtistGroupId("aespa"), group?.entityId)
    }

    @Test fun releasePaletteUsesOnlyLocallyReadableCoverUrisNotAuthenticatedProviderRefs() {
        assertEquals(
            "content://media/album-art/123",
            canonicalNativeReleasePaletteUri(
                "content://media/album-art/123", "https://restricted.example/covers/123",
            ),
        )
        assertEquals(
            "file:///music/cover.png",
            canonicalNativeReleasePaletteUri(
                null, "file:///music/cover.png",
            ),
        )
        assertEquals(null, canonicalNativeReleasePaletteUri(
            "opensubsonic:secret-art-id", "https://provider/private-art",
        ))
    }

    @Test fun creationWizardInheritsExactSelectedRecordingsButRejectsPartialSelection() {
        assertEquals(listOf("a", "b"), canonicalLibraryWizardSeed(
            CanonicalLibraryResolvedSelection(
                recordingIds = listOf("a", "b", "a"), unavailableEntities = 0,
            ),
        ))
        assertEquals(null, canonicalLibraryWizardSeed(
            CanonicalLibraryResolvedSelection(
                recordingIds = listOf("a"), unavailableEntities = 1,
            ),
        ))
        assertEquals(null, canonicalLibraryWizardSeed(
            CanonicalLibraryResolvedSelection(
                recordingIds = emptyList(), unavailableEntities = 0,
            ),
        ))
    }

    @Test fun embeddedNativeWizardMustRecheckSourceBeforeSaving() {
        assertTrue(canonicalNativeWizardSourceEligible(
            listOf("a", "b", "a"), setOf("a", "b"),
        ))
        assertFalse(canonicalNativeWizardSourceEligible(
            listOf("a", "b"), setOf("a"),
        ))
        assertFalse(canonicalNativeWizardSourceEligible(
            listOf("a"), emptySet(),
        ))
    }


    @Test fun dragGestureUsesExactVisibleKeysAndNeverSelectsAnEmptyGridCell() {
        val a = CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, "recording-1")
        val b = CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, "recording-2")
        val c = CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, "recording-3")
        val list = canonicalLibraryDragRows(listOf(a, b, c), grid = false)
        assertEquals(a, canonicalLibraryDragHit(list, "recording-1", 100f, 400f, grid = false))
        assertEquals(null, canonicalLibraryDragHit(list, "native-library-header", 100f, 400f, grid = false))

        val grid = canonicalLibraryDragRows(listOf(a, b, c), grid = true)
        assertEquals(a, canonicalLibraryDragHit(grid, "grid-0-recording-1", 100f, 400f, grid = true))
        assertEquals(b, canonicalLibraryDragHit(grid, "grid-0-recording-1", 300f, 400f, grid = true))
        assertEquals(c, canonicalLibraryDragHit(grid, "grid-1-recording-3", 100f, 400f, grid = true))
        assertEquals(null, canonicalLibraryDragHit(grid, "grid-1-recording-3", 300f, 400f, grid = true))
        assertEquals(null, canonicalLibraryDragHit(grid, "grid-0-recording-1", -1f, 400f, grid = true))
    }

    @Test fun draggingAcrossRowsSelectsEveryCrossedCanonicalIdWithoutRemovingEarlierSelection() {
        val first = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "artist-1")
        val second = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "artist-2")
        val third = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "artist-3")
        val fourth = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "artist-4")
        val ordered = listOf(first, second, third, fourth)
        assertEquals(listOf(first, second, third), canonicalLibraryDragSpan(ordered, 0, 2))
        assertEquals(listOf(second, third, fourth), canonicalLibraryDragSpan(ordered, 3, 1))
        assertTrue(canonicalLibraryDragSpan(ordered, -1, 2).isEmpty())
        assertTrue(canonicalLibraryDragSpan(ordered, 0, 10).isEmpty())
        assertEquals(listOf(first, second, third, fourth), (
            listOf(first) + canonicalLibraryDragSpan(ordered, 1, 3)
        ).distinct())
    }

    @Test fun longPressSelectionIsOnlyAvailableOnTopLevelLibraryCategories() {
        for (category in CanonicalSmartFilterContext.entries) {
            assertTrue(canSelectCanonicalLibraryByLongPress(category, detailOpen = false))
            assertFalse(canSelectCanonicalLibraryByLongPress(category, detailOpen = true))
        }
    }

    @Test fun displayArtistRatingMustRemainDistinctFromCanonicalArtistWithSameName() {
        val real = canonicalEntityRatingTarget(CanonicalLibrarySelection(
            CanonicalLibraryEntityType.ARTIST, "mbid-artist",
        ))
        val display = canonicalEntityRatingTarget(CanonicalLibrarySelection(
            CanonicalLibraryEntityType.ARTIST, playlistDisplayArtistGroupId("AtHeart"),
        ))
        assertEquals("ARTIST", real?.entityType)
        assertEquals("DISPLAY_ARTIST", display?.entityType)
        assertFalse(real == display)
    }

    @Test fun entityRatingsAreFiveStarHalfStepAndRequireRealIdentity() {
        assertTrue(isValidCanonicalEntityRating(1.0))
        assertTrue(isValidCanonicalEntityRating(4.5))
        assertTrue(isValidCanonicalEntityRating(5.0))
        assertFalse(isValidCanonicalEntityRating(0.5))
        assertFalse(isValidCanonicalEntityRating(3.3))
        assertFalse(isValidCanonicalEntityRating(Double.NaN))
    }

    @Test fun menuAvailabilityIsTypedAndNeverOffersArtistRatingForDisplayOnlyName() {
        val album = canonicalLibraryAvailableActions(CanonicalLibraryEntityType.ALBUM, true)
        assertTrue(CanonicalLibraryAction.ADD_TO_PLAYLIST in album)
        assertTrue(CanonicalLibraryAction.ADD_NEXT in album)
        assertTrue(CanonicalLibraryAction.EDIT_RATING in album)
        val artistWithoutId = canonicalLibraryAvailableActions(CanonicalLibraryEntityType.ARTIST, false)
        assertFalse(CanonicalLibraryAction.EDIT_RATING in artistWithoutId)
        val track = canonicalLibraryAvailableActions(CanonicalLibraryEntityType.TRACK, true)
        assertTrue(CanonicalLibraryAction.EDIT_RATING in track)
    }
}

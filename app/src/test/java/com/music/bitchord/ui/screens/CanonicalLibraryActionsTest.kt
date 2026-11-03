package com.music.bitchord.ui.screens

import com.music.bitchord.data.listening.CanonicalSmartFilterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryActionsTest {
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

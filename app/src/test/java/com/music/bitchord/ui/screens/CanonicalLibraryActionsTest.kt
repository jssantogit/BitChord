package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryActionsTest {
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
        assertEquals(null, canonicalEntityRatingTarget(
            CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST,
                playlistDisplayArtistGroupId("aespa")),
        ))
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
        assertFalse(CanonicalLibraryAction.EDIT_RATING in album)
        val artistWithoutId = canonicalLibraryAvailableActions(CanonicalLibraryEntityType.ARTIST, false)
        assertFalse(CanonicalLibraryAction.EDIT_RATING in artistWithoutId)
        val track = canonicalLibraryAvailableActions(CanonicalLibraryEntityType.TRACK, true)
        assertTrue(CanonicalLibraryAction.EDIT_RATING in track)
    }
}

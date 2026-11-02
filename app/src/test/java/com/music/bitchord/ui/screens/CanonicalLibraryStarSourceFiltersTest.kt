package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryStarSourceFiltersTest {
    private val local = CanonicalBrowseMediaAsset("local-a", "local-mediastore")
    private val currentRemote = CanonicalBrowseMediaAsset("remote-a", "opensubsonic:current")
    private val otherRemote = CanonicalBrowseMediaAsset("remote-b", "opensubsonic:other")
    private fun row(vararg assets: CanonicalBrowseMediaAsset) =
        CanonicalBrowseTrack("recording-a", "Song", "Artist", 130_000L, null, assets.toList())

    @Test fun matchingRemoteFiveStarsIsNotInferredFromStarredOrAnotherAsset() {
        val ratings = CanonicalRatingDisplaySnapshot(
            byMediaAssetId = mapOf("remote-a" to RatingDisplay(remoteValue = 5)),
        )
        assertEquals(1, filterCanonicalLibraryTracks(
            listOf(row(local, currentRemote)), "opensubsonic:current", 5, ratings,
        ).size)
        assertTrue(filterCanonicalLibraryTracks(
            listOf(row(local, currentRemote)), "local-mediastore", 5, ratings,
        ).isEmpty())
        assertTrue(filterCanonicalLibraryTracks(
            listOf(row(otherRemote)), "opensubsonic:other", 5, ratings,
        ).isEmpty())
        assertTrue(filterCanonicalLibraryTracks(
            listOf(row(currentRemote)), "opensubsonic:current", 5,
            CanonicalRatingDisplaySnapshot(),
        ).isEmpty())
    }

    @Test fun sourceFilterRestrictsAssetsWithoutDuplicatingRecording() {
        val selected = filterCanonicalLibraryTracks(
            listOf(row(local, currentRemote)), "opensubsonic:current", null,
            CanonicalRatingDisplaySnapshot(),
        )
        assertEquals(listOf("remote-a"), selected.single().availableAssets.map { it.mediaAssetId })
        assertTrue(filterCanonicalLibraryTracks(
            listOf(row(local)), "opensubsonic:current", null,
            CanonicalRatingDisplaySnapshot(),
        ).isEmpty())
    }

    @Test fun localRatingStillAppliesToCanonicalRecordingAcrossItsAvailableSources() {
        val ratings = CanonicalRatingDisplaySnapshot(
            byRecordingId = mapOf("recording-a" to RatingDisplay(localValue = 4.0)),
        )
        assertEquals(1, filterCanonicalLibraryTracks(
            listOf(row(local)), null, 4, ratings,
        ).size)
        assertEquals(1, filterCanonicalLibraryTracks(
            listOf(row(currentRemote)), "opensubsonic:current", 4, ratings,
        ).size)
        assertTrue(filterCanonicalLibraryTracks(
            listOf(row(currentRemote)), null, 5, ratings,
        ).isEmpty())
    }

    @Test fun albumOrArtistIsEligibleOnlyIfItContainsVisibleProvenMember() {
        val visible = setOf("song-1", "song-2")
        assertTrue(canonicalLibraryEntityHasMatchingTracks(
            listOf("song-3", "song-2"), visible))
        assertFalse(canonicalLibraryEntityHasMatchingTracks(
            listOf("song-3"), visible))
        assertFalse(canonicalLibraryEntityHasMatchingTracks(
            emptyList(), visible))
    }
}

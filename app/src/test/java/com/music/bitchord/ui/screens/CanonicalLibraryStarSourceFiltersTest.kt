package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalPlaylistAlbumCoverRow
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.visibleStarValues
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

    @Test fun visibleStarsRemainScopedToEligibleAssetsAndConflicts() {
        val snapshot = CanonicalRatingDisplaySnapshot(
            byRecordingId = mapOf(
                "recording-a" to RatingDisplay(localValue = 4.0),
            ),
            byMediaAssetId = mapOf(
                "remote-a" to RatingDisplay(localValue = 4.0, remoteValue = 5),
                "remote-b" to RatingDisplay(remoteValue = 3),
            ),
        )
        assertEquals(listOf(4.0, 5.0),
            canonicalLibraryVisibleRatings(row(local, currentRemote), snapshot)
                .single().visibleStarValues())
        val scoped = canonicalLibraryVisibleRatings(row(local), snapshot)
        assertEquals(listOf(4.0), scoped.single().visibleStarValues())
        val divergent = canonicalLibraryVisibleRatings(row(currentRemote, otherRemote), snapshot)
        assertEquals(3, divergent.size)
        assertEquals(listOf(4.0, 5.0, 3.0),
            divergent.flatMap { it.visibleStarValues() })
    }

    @Test fun browseOnlyArtistGroupsCoverFullVisibleLibraryWithoutHundredResultCap() {
        val tracks = (1..151).map { index ->
            CanonicalBrowseTrack(
                "recording-$index", "Track $index", "Uncredited $index",
                100_000L, null, listOf(local),
            )
        }
        val names = canonicalLibraryDisplayArtistNames(tracks, emptySet())
        assertEquals(151, names.size)
        assertTrue("Uncredited 151" in names)
        assertFalse("Uncredited 1" in canonicalLibraryDisplayArtistNames(
            tracks, setOf("uncredited 1"),
        ))
    }

    @Test fun selectingSourceUsesItsOwnArtworkAndDatesWithoutAnotherSourceLeaking() {
        val remoteArt = CanonicalBrowseMediaAsset(
            "server-art", "opensubsonic:active",
            dateAddedSeconds = 90,
            artworkUri = "opaque-server-art",
        )
        val localArt = CanonicalBrowseMediaAsset(
            "local-art", "local-mediastore",
            dateAddedSeconds = 11,
            artworkUri = "content://media/local-cover",
        )
        val recording = CanonicalBrowseTrack(
            recordingId = "recording-art",
            title = "Song",
            artistDisplay = "Artist",
            durationMs = 120_000L,
            artworkUri = "opaque-server-art",
            availableAssets = listOf(remoteArt, localArt),
            dateAddedSeconds = 90,
        )
        val localFiltered = filterCanonicalLibraryTracks(
            listOf(recording), "local-mediastore", null,
            CanonicalRatingDisplaySnapshot(),
        ).single()
        assertEquals("content://media/local-cover", localFiltered.artworkUri)
        assertEquals(11L, localFiltered.dateAddedSeconds)

        val remoteFiltered = filterCanonicalLibraryTracks(
            listOf(recording), "opensubsonic:active", null,
            CanonicalRatingDisplaySnapshot(),
        ).single()
        assertEquals("opaque-server-art", remoteFiltered.artworkUri)
        assertEquals(90L, remoteFiltered.dateAddedSeconds)
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
    @Test fun albumAndArtistArtworkRequiresAnEligibleLinkedRecordingId() {
        val first = CanonicalBrowseTrack(
            "recording-visible", "Song", "Collaborating Artist", 120_000L,
            "content://media/verified", listOf(local),
        )
        val another = CanonicalBrowseTrack(
            "recording-other", "Song", "Collaborating Artist", 120_000L,
            "content://media/unavailable", listOf(otherRemote),
        )
        val mapped = canonicalLibraryEntityArtworkById(
            listOf(
                CanonicalPlaylistAlbumCoverRow("album-a", "recording-visible"),
                CanonicalPlaylistAlbumCoverRow("artist-a", "recording-visible"),
                CanonicalPlaylistAlbumCoverRow("album-other", "recording-other"),
            ),
            listOf(first),
        )
        assertEquals(setOf("album-a", "artist-a"), mapped.keys)
        assertEquals("recording-visible", mapped["artist-a"]?.recordingId)
        assertFalse(mapped.containsKey("album-other"))
        // Artist display text alone never creates an artwork membership claim.
        assertEquals(null, canonicalLibraryEntityArtworkById(emptyList(), listOf(first, another))["artist-a"])
    }

}

package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Match the exact branches the canonical artwork painter can actually use. */
class NativeAlbumTrackArtworkEvidenceTest {
    private fun track(revision: String?) = CanonicalBrowseTrack(
        recordingId = "recording-id",
        title = "Track",
        artistDisplay = "Artist",
        durationMs = null,
        artworkUri = revision,
        availableAssets = emptyList(),
    )

    @Test fun aContentUriWithoutLocalMediaAssetIsNotEnough() {
        assertFalse(nativeAlbumTrackHasLoadableArtwork(
            track("content://media/external/audio/albumart/5"),
        ))
        assertFalse(nativeAlbumTrackHasLoadableArtwork(
            track("file:///storage/emulated/0/album.jpg"),
        ))
    }

    @Test fun emptyOrRemoteRevisionWithoutMediaAssetCannotOverrideReleaseArtwork() {
        assertFalse(nativeAlbumTrackHasLoadableArtwork(track(null)))
        assertFalse(nativeAlbumTrackHasLoadableArtwork(track("")))
        assertFalse(nativeAlbumTrackHasLoadableArtwork(track("https://example.com/cover.jpg")))
    }
}

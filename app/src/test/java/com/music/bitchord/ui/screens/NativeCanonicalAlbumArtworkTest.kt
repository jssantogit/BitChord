package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The album cover that was visible in Library must not disappear when the
 * same verified release opens without a downloadable/playable track.
 */
class NativeCanonicalAlbumArtworkTest {
    @Test fun aLocalAlbumUriIsThePrimaryArtworkEvenWithTrackFallback() {
        assertEquals(
            NativeAlbumHeroSource.DIRECT_URI,
            nativeAlbumHeroSource("content://media/external/audio/albumart/42", hasTrackArtwork = true),
        )
        assertEquals(
            NativeAlbumHeroSource.DIRECT_URI,
            nativeAlbumHeroSource("file:///storage/emulated/0/Music/cover.jpg", hasTrackArtwork = false),
        )
    }

    @Test fun verifiedTrackArtworkOwnsAuthenticatedOrUnknownReleaseUri() {
        assertEquals(
            NativeAlbumHeroSource.TRACK_LOADER,
            nativeAlbumHeroSource("https://music.example/cover.png", hasTrackArtwork = true),
        )
        assertEquals(
            NativeAlbumHeroSource.TRACK_LOADER,
            nativeAlbumHeroSource("opensubsonic:provider-album-1", hasTrackArtwork = true),
        )
    }

    @Test fun remoteReleaseArtworkRemainsAvailableWhenNoTrackHasArtwork() {
        assertEquals(
            NativeAlbumHeroSource.DIRECT_URI,
            nativeAlbumHeroSource("https://images.example.com/release.jpg", hasTrackArtwork = false),
        )
        assertEquals(
            NativeAlbumHeroSource.DIRECT_URI,
            nativeAlbumHeroSource("http://local-music-server/cover.jpg", hasTrackArtwork = false),
        )
    }

    @Test fun unsupportedOrMissingSchemesCannotBecomeDirectImageRequests() {
        listOf(null, "", "  ", "javascript:bad", "data:image/png;base64,AAAA",
            "opensubsonic:missing-track").forEach { candidate ->
            assertEquals(
                NativeAlbumHeroSource.PLACEHOLDER,
                nativeAlbumHeroSource(candidate, hasTrackArtwork = false),
            )
        }
    }
}

package com.music.bitchord

import com.music.bitchord.data.catalog.SpotifyCanonicalAssetCandidate
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.spotify.selectSpotifyCanonicalAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCanonicalAssetMatcherTest {
    private fun spotify(title: String = "Supernova", artist: String = "aespa",
                        duration: Int = 180000) =
        SpotifyTrack("spotify-track", title, artist, "Album", duration, null)

    private fun asset(
        recording: String = "rec-1",
        title: String = "Supernova",
        artist: String = "aespa",
        duration: Long? = 180000,
        provider: String = "local-mediastore",
        uri: String = "content://media/external/audio/media/15",
    ) = SpotifyCanonicalAssetCandidate(
        recordingId = recording, mediaAssetId = "asset-${recording}-${provider}",
        providerKey = provider, title = title, artistDisplay = artist,
        durationMs = duration, uri = uri, path = null, artworkUri = null,
    )

    @Test fun exactDurationAndArtistProduceRealLocalAsset() {
        assertEquals("rec-1", selectSpotifyCanonicalAsset(
            spotify(), listOf(asset(duration = 181000)),
        )?.recordingId)
    }

    @Test fun titleOnlyOrArtistOnlyMatchesAreNeverPlayable() {
        assertNull(selectSpotifyCanonicalAsset(spotify(), listOf(asset(artist = "other artist"))))
        assertNull(selectSpotifyCanonicalAsset(spotify(), listOf(asset(title = "Supernova remix"))))
    }

    @Test fun ambiguousRecordingsRemainUnmatched() {
        assertNull(selectSpotifyCanonicalAsset(spotify(), listOf(
            asset(recording = "rec-1"),
            asset(recording = "rec-2", uri = "content://media/external/audio/media/22"),
        )))
    }

    @Test fun unknownOrDifferentDurationNeverAutoMatches() {
        assertNull(selectSpotifyCanonicalAsset(spotify(), listOf(asset(duration = null))))
        assertNull(selectSpotifyCanonicalAsset(spotify(), listOf(asset(duration = 195000))))
        assertNull(selectSpotifyCanonicalAsset(spotify(duration = 0), listOf(asset())))
    }

    @Test fun rejectsYoutubeAndUntrustedRemoteAudio() {
        assertNull(selectSpotifyCanonicalAsset(spotify(), listOf(
            asset(provider = "youtube", uri = "https://youtube.invalid/watch"),
        )))
        assertNull(selectSpotifyCanonicalAsset(spotify(), listOf(
            asset(provider = "local-mediastore", uri = "https://unverified.invalid/stream"),
        )))
    }

    @Test fun localWinsOverServerForSameVerifiedRecording() {
        val remote = asset(provider = "opensubsonic:server1", uri = "https://server.invalid/play")
        val local = asset()
        assertEquals("local-mediastore",
            selectSpotifyCanonicalAsset(spotify(), listOf(remote, local))?.providerKey)
    }

    @Test fun compatibleRemoteAssetIsEligibleWithoutExposingSourceKey() {
        val server = asset(provider = "opensubsonic:server1", uri = "https://server.invalid/play")
        assertEquals("rec-1", selectSpotifyCanonicalAsset(spotify(), listOf(server))?.recordingId)
    }

    @Test fun cappedSearchIsTreatedAsAmbiguousRatherThanFirstHit() {
        assertNull(selectSpotifyCanonicalAsset(spotify(), List(50) { asset() }))
    }
}

package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackResolverTest {

    @Test
    fun `local legacy song resolves to canonical recording identity`() = runBlocking {
        val uri = "content://media/external/audio/media/42"
        val resolver = CanonicalPlaybackResolver(
            PlaybackCatalog {
                listOf(
                    PlayableMediaAsset(
                        recordingId = "recording-uuid",
                        mediaAssetId = "asset-uuid",
                        uri = uri,
                        path = "/storage/emulated/0/Music/track.flac",
                    ),
                )
            },
        )
        val song = Song(
            videoId = "youtube-compatibility-alias",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = uri,
        )

        val identity = resolver.identify(song)

        assertEquals(CanonicalQueueIdentity("recording-uuid"), identity)
        assertNotEquals(song.videoId, identity?.recordingId)
    }

    @Test
    fun `canonical identity resolves back to playable local asset`() = runBlocking {
        val expected = PlayableMediaAsset(
            recordingId = "recording-uuid",
            mediaAssetId = "asset-uuid",
            uri = "content://media/external/audio/media/42",
            path = "/storage/emulated/0/Music/track.flac",
        )
        val resolver = CanonicalPlaybackResolver(PlaybackCatalog { listOf(expected) })

        assertEquals(expected, resolver.resolve(CanonicalQueueIdentity("recording-uuid")))
    }

    @Test
    fun `resolver never falls back to provider id when canonical asset is absent`() = runBlocking {
        val resolver = CanonicalPlaybackResolver(PlaybackCatalog { emptyList() })
        val song = Song(
            videoId = "youtube-id",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "content://media/external/audio/media/42",
        )

        assertNull(resolver.identify(song))
        assertNull(resolver.resolve(CanonicalQueueIdentity("missing-recording")))
    }
}

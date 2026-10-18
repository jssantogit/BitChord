package com.music.bitchord

import com.music.bitchord.ui.player.CanonicalPlaybackArtworkIdentity
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalPlaybackArtworkContractTest {
    @Test
    fun `artwork key is canonical recording plus selected media asset only`() {
        val first = CanonicalPlaybackArtworkIdentity(
            recordingId = "recording-1",
            mediaAssetId = "asset-a",
            providerKey = "opensubsonic:home",
        )
        val sameRenditionDifferentRouting = first.copy(providerKey = "provider-routing-must-not-be-identity")
        val anotherRendition = first.copy(mediaAssetId = "asset-b")

        assertEquals(first.key, sameRenditionDifferentRouting.key)
        assertNotEquals(first.key, anotherRendition.key)
        assertEquals("recording-1", first.key.recordingId)
        assertEquals("asset-a", first.key.mediaAssetId)
    }

    @Test
    fun `selected playback URI supplies artwork while local and legacy stay outside this path`() {
        val selected = CanonicalRemotePlaybackRequest(
            recordingId = "recording-1",
            mediaAssetId = "asset-b",
            providerKey = "opensubsonic:home",
        )
        val remote = Song(
            videoId = "recording-1", title = "Remote", artist = "Artist",
            thumbnailUrl = null, fromAutoplay = false,
            localUri = CanonicalRemotePlaybackUri.encode(selected),
        )
        val identity = CanonicalPlaybackArtworkIdentity.fromSong(remote)
        assertEquals("recording-1", identity?.key?.recordingId)
        assertEquals("asset-b", identity?.key?.mediaAssetId)
        assertNull(CanonicalPlaybackArtworkIdentity.fromSong(
            remote.copy(localUri = "content://media/audio/1"),
        ))
        val restoredQueueAdapter = remote.copy(videoId = remote.localUri.orEmpty())
        assertEquals(identity?.key, CanonicalPlaybackArtworkIdentity.fromSong(restoredQueueAdapter)?.key)
        // The legacy Song videoId is never trusted as artwork identity.
        assertEquals(identity?.key, CanonicalPlaybackArtworkIdentity.fromSong(
            remote.copy(videoId = "compatibility-queue-id"),
        )?.key)
        assertNull(CanonicalPlaybackArtworkIdentity.fromSong(
            remote.copy(localUri = null),
        ))
        val otherSelection = remote.copy(
            localUri = CanonicalRemotePlaybackUri.encode(
                selected.copy(mediaAssetId = "asset-c"),
            ),
        )
        assertNotEquals(identity?.key, CanonicalPlaybackArtworkIdentity.fromSong(otherSelection)?.key)
    }

    @Test
    fun `mixed queue keeps artwork selection per remote entry while local stays legacy`() {
        val first = CanonicalRemotePlaybackRequest(
            recordingId = "recording-hero",
            mediaAssetId = "selected-hero",
            providerKey = "opensubsonic:home",
        )
        val second = CanonicalRemotePlaybackRequest(
            recordingId = "recording-other",
            mediaAssetId = "selected-other",
            providerKey = "opensubsonic:home",
        )
        fun queued(request: CanonicalRemotePlaybackRequest) = Song(
            // Restored queue entries may use a compatibility rather than recording ID.
            videoId = "compatibility-${request.recordingId}",
            title = "Remote", artist = "Artist",
            thumbnailUrl = null, fromAutoplay = false,
            localUri = CanonicalRemotePlaybackUri.encode(request),
        )
        val remoteFirst = queued(first)
        val remoteSecond = queued(second)
        val local = Song(
            videoId = "local-track", title = "Local", artist = "Artist",
            thumbnailUrl = "content://media/audio/albumart/1", fromAutoplay = false,
            localUri = "content://media/external/audio/media/1",
        )

        assertEquals(
            "selected-hero",
            CanonicalPlaybackArtworkIdentity.fromSong(remoteFirst)?.key?.mediaAssetId,
        )
        assertEquals(
            "selected-other",
            CanonicalPlaybackArtworkIdentity.fromSong(remoteSecond)?.key?.mediaAssetId,
        )
        assertNotEquals(
            CanonicalPlaybackArtworkIdentity.fromSong(remoteFirst)?.key,
            CanonicalPlaybackArtworkIdentity.fromSong(remoteSecond)?.key,
        )
        assertNull(CanonicalPlaybackArtworkIdentity.fromSong(local))
        assertEquals("content://media/audio/albumart/1", local.thumbnailUrl)
    }

    @Test
    fun `identity does not accept provider native identifiers as artwork key material`() {
        val identity = CanonicalPlaybackArtworkIdentity(
            recordingId = "recording-canonical",
            mediaAssetId = "asset-canonical",
            providerKey = "opensubsonic:home",
        )

        val publicKey = identity.key.toString()
        assertEquals("recording-canonical", identity.key.recordingId)
        assertEquals("asset-canonical", identity.key.mediaAssetId)
        assertNull(CanonicalPlaybackArtworkIdentity::class.java.declaredFields.firstOrNull { it.name == "coverArtId" })
        assertNull(CanonicalPlaybackArtworkIdentity::class.java.declaredFields.firstOrNull { it.name == "sourceKey" })
        assertNull(CanonicalPlaybackArtworkIdentity::class.java.declaredFields.firstOrNull { it.name == "providerSongId" })
        assertEquals(false, publicKey.contains("cover"))
    }
}

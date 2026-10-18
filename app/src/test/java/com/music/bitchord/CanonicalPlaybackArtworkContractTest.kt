package com.music.bitchord

import com.music.bitchord.ui.player.CanonicalPlaybackArtworkIdentity
import com.music.bitchord.ui.player.preloadedMenuArtworkFor
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import com.music.bitchord.ui.player.preferredPlaybackArtworkModel
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
    fun `three dot menu uses already loaded player cover for same selected asset only`() {
        val selection = CanonicalRemotePlaybackRequest(
            recordingId = "recording-push-back",
            mediaAssetId = "asset-a",
            providerKey = "opensubsonic:home",
        )
        fun song(request: CanonicalRemotePlaybackRequest) = Song(
            videoId = request.recordingId,
            title = "Push Back",
            artist = "AtHeart",
            thumbnailUrl = null,
            fromAutoplay = false,
            localUri = CanonicalRemotePlaybackUri.encode(request),
        )
        val playingSong = song(selection)
        val identity = CanonicalPlaybackArtworkIdentity.fromSong(playingSong)
        val alreadyRendered = Any()
        assertEquals(
            alreadyRendered,
            preloadedMenuArtworkFor(playingSong.copy(albumName = "Album"), identity, alreadyRendered),
        )
        assertNull(preloadedMenuArtworkFor(
            song(selection.copy(mediaAssetId = "asset-b")), identity, alreadyRendered,
        ))
        assertNull(preloadedMenuArtworkFor(
            song(selection.copy(recordingId = "recording-other")), identity, alreadyRendered,
        ))
        assertNull(preloadedMenuArtworkFor(
            song(selection.copy(providerKey = "opensubsonic:other")), identity, alreadyRendered,
        ))
        assertNull(preloadedMenuArtworkFor(
            playingSong.copy(localUri = "content://media/external/audio/media/1"),
            identity, alreadyRendered,
        ))
        assertNull(preloadedMenuArtworkFor(playingSong, identity, null))
        assertNull(preloadedMenuArtworkFor(playingSong, null, alreadyRendered))
    }

    @Test
    fun `all song artwork surfaces prefer canonical model and retain local fallback`() {
        val canonicalDrawable = Any()
        val legacyLocalThumbnail = "content://media/audio/albumart/1"
        val legacyRemoteThumbnail = "https://example.test/legacy.jpg"
        assertEquals(canonicalDrawable, preferredPlaybackArtworkModel(canonicalDrawable, null))
        assertEquals(canonicalDrawable, preferredPlaybackArtworkModel(canonicalDrawable, legacyRemoteThumbnail))
        assertEquals(legacyLocalThumbnail, preferredPlaybackArtworkModel(null, legacyLocalThumbnail))
        assertEquals(legacyRemoteThumbnail, preferredPlaybackArtworkModel(null, legacyRemoteThumbnail))
        assertNull(preferredPlaybackArtworkModel(null, null))
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

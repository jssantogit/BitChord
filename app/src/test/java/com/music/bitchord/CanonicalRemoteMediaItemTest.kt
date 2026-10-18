package com.music.bitchord

import com.music.bitchord.playback.CanonicalRemotePlaybackMetadata
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import com.music.bitchord.playback.canonicalRemoteMediaItem
import com.music.bitchord.playback.isCanonicalRemotePlayback
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalRemoteMediaItemTest {

    @Test
    fun `logical media item carries only canonical identity and safe metadata`() {
        val request = CanonicalRemotePlaybackRequest(
            recordingId = "recording-1",
            mediaAssetId = "asset-1",
            providerKey = "opensubsonic:home",
        )
        val item = canonicalRemoteMediaItem(
            request = request,
            metadata = CanonicalRemotePlaybackMetadata(
                title = "Paranoid Android",
                artistDisplay = "Radiohead",
            ),
        )

        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        assertEquals("recording-1", item.mediaId)
        assertEquals("Paranoid Android", item.mediaMetadata.title?.toString())
        assertEquals("Radiohead", item.mediaMetadata.artist?.toString())
        assertEquals(CanonicalRemotePlaybackUri.encode(request), uri)
        assertTrue(item.isCanonicalRemotePlayback())
        assertFalse(uri.contains("sourceKey", ignoreCase = true))
        assertFalse(uri.contains("server-track-native"))
        assertFalse(uri.contains("apiKey", ignoreCase = true))
        assertFalse(uri.contains("password", ignoreCase = true))
        assertFalse(uri.contains("token", ignoreCase = true))
    }

    @Test
    fun `queue round trip persists logical uri without provider locator or credentials`() {
        val request = CanonicalRemotePlaybackRequest(
            recordingId = "recording-1",
            mediaAssetId = "asset-1",
            providerKey = "opensubsonic:home",
        )
        val logicalUri = CanonicalRemotePlaybackUri.encode(request)
        val original = canonicalRemoteMediaItem(
            request,
            CanonicalRemotePlaybackMetadata("Paranoid Android", "Radiohead"),
        )

        val storedSong = original.toSong()
        val restored = storedSong.toMediaItem()
        val restoredUri = restored.localConfiguration?.uri?.toString().orEmpty()

        assertEquals(logicalUri, storedSong.localUri)
        assertEquals(logicalUri, restoredUri)
        assertTrue(restored.isCanonicalRemotePlayback())
        assertFalse(restoredUri.contains("server-track-native"))
        assertFalse(restoredUri.contains("apiKey", ignoreCase = true))
        assertFalse(restoredUri.contains("password", ignoreCase = true))
    }
}

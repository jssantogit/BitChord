package com.music.bitchord

import com.music.bitchord.playback.CanonicalRemotePlaybackMetadata
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import com.music.bitchord.playback.canonicalRemoteMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CanonicalRemoteMediaItemTest {

    @Test
    fun `media item identity and visible metadata stay canonical`() {
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

        // Local JVM tests use stub android.net.Uri/Bundle implementations, so
        // URI/extras behavior is covered by the pure logical contract below and
        // by Android-real validation rather than asserting framework stubs.
        assertEquals("recording-1", item.mediaId)
        assertEquals("Paranoid Android", item.mediaMetadata.title?.toString())
        assertEquals("Radiohead", item.mediaMetadata.artist?.toString())
    }

    @Test
    fun `session and queue request model contains only approved canonical fields`() {
        val requestFields = CanonicalRemotePlaybackRequest::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()

        assertEquals(
            setOf("recordingId", "mediaAssetId", "providerKey"),
            requestFields,
        )

        val logicalUri = CanonicalRemotePlaybackUri.encode(
            CanonicalRemotePlaybackRequest(
                recordingId = "recording-1",
                mediaAssetId = "asset-1",
                providerKey = "opensubsonic:home",
            ),
        )
        assertFalse(logicalUri.contains("sourceKey", ignoreCase = true))
        assertFalse(logicalUri.contains("server-track-native"))
        assertFalse(logicalUri.contains("apiKey", ignoreCase = true))
        assertFalse(logicalUri.contains("password", ignoreCase = true))
        assertFalse(logicalUri.contains("token", ignoreCase = true))
    }
}

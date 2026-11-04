package com.music.bitchord.playback

import com.music.bitchord.data.catalog.CanonicalBrowseAssetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalAutoBrowseTracksTest {
    private fun row(
        recordingId: String = "rec-1",
        mediaAssetId: String = "asset-1",
        provider: String = "opensubsonic:home",
    ) = CanonicalBrowseAssetRow(
        recordingId = recordingId,
        mediaAssetId = mediaAssetId,
        providerKey = provider,
        title = "Supernova",
        artistDisplay = "aespa",
        durationMs = 180000,
        artworkUri = null,
    )

    @Test fun serverRowCreatesCanonicalLogicalPlaybackIdentity() {
        val song = canonicalAutoSong(row())!!
        val request = CanonicalRemotePlaybackUri.decode(song.localUri.orEmpty())
        assertEquals("rec-1", request?.recordingId)
        assertEquals("asset-1", request?.mediaAssetId)
        assertEquals("opensubsonic:home", request?.providerKey)
        assertEquals("Supernova", song.title)
        assertEquals("aespa", song.artist)
        assertFalse(song.videoId.contains("stream", ignoreCase = true))
    }

    @Test fun noOpaqueOrUnsupportedSourceCanEnterAndroidAutoBrowse() {
        assertNull(canonicalAutoSong(row(provider = "youtube")))
        assertNull(canonicalAutoSong(row(provider = "jellyfin:one")))
        assertNull(canonicalAutoSong(row(recordingId = "")))
        assertNull(canonicalAutoSong(row(mediaAssetId = "")))
    }

    @Test fun duplicatesFromOneRecordingCreateOneBrowsableSong() {
        val songs = canonicalAutoSongs(listOf(
            row("rec-1", "asset-2", "opensubsonic:two"),
            row("rec-1", "asset-1", "opensubsonic:one"),
            row("rec-2", "asset-3", "opensubsonic:one"),
            row("rec-3", "asset-4", "jellyfin:one"),
        ))
        assertEquals(2, songs.size)
        assertEquals("rec-1", CanonicalRemotePlaybackUri.decode(songs.first().localUri.orEmpty())?.recordingId)
        assertEquals("asset-1", CanonicalRemotePlaybackUri.decode(songs.first().localUri.orEmpty())?.mediaAssetId)
    }
}

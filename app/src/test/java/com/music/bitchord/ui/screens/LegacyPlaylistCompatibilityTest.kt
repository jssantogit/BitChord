package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPlaylistCompatibilityTest {
    private fun song(id: String, uri: String? = null) =
        Song(videoId = id, title = "Old track", artist = "Artist", thumbnailUrl = null, localUri = uri)

    @Test fun legacyYoutubeIdRemainsVisibleButNotPlayable() {
        assertNull(legacyPlaylistPlayableSong(song("youtube-legacy-key")))
    }

    @Test fun realMediaStoreUriIsPlayableWithoutYoutubeFallback() {
        val uri = "content://media/external/audio/media/42"
        assertEquals(uri, legacyPlaylistPlayableSong(song("old-key", uri))?.localUri)
    }

    @Test fun downloadedLegacyTrackUsesExistingVerifiedFileWithoutChangingIdentity() {
        val downloaded = "file:///storage/emulated/0/Music/old.flac"
        val available = legacyPlaylistPlayableSong(song("historical-id"), downloaded)
        assertEquals("historical-id", available?.videoId)
        assertEquals(downloaded, available?.localUri)
    }

    @Test fun untrustedHttpUrlNeverBecomesOfflinePlayback() {
        assertNull(legacyPlaylistPlayableSong(song("historical-id", "https://remote.invalid/audio")))
        assertNull(legacyPlaylistPlayableSong(song("historical-id"), "https://remote.invalid/audio"))
    }

    @Test fun aRawHistoricalVideoIdIsNeverMistakenForACanonicalUri() {
        assertNull(legacyPlaylistPlayableSong(song("bitchord://watch?v=old")))
    }
}

package com.music.bitchord.playback

import com.music.bitchord.data.catalog.CanonicalBrowseAssetRow
import com.music.bitchord.data.model.Song
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

    @Test fun autoSearchUsesTrackAndArtistMetadataWithoutProviderLookup() {
        val songs = listOf(
            Song(
                videoId = "content://media/one", title = "Supernova", artist = "aespa",
                thumbnailUrl = null, localUri = "content://media/one",
            ),
            Song(
                videoId = "content://media/two", title = "Magnetic", artist = "ILLIT",
                thumbnailUrl = null, localUri = "content://media/two",
            ),
        )
        assertEquals(listOf("Supernova"), canonicalAutoSearch(songs, "super").map { it.title })
        assertEquals(listOf("Supernova"), canonicalAutoSearch(songs, "aespa").map { it.title })
        assertEquals(emptyList<Song>(), canonicalAutoSearch(songs, "not found"))
    }

    @Test fun quickPicksKeepRemoteAudioWhenLocalLibraryIsEmptyOrInaccessible() {
        val remote = canonicalAutoSong(row())!!
        assertEquals(
            listOf(remote),
            canonicalAutoQuickPicks(
                local = emptyList(),
                downloads = emptyList(),
                remote = listOf(remote),
                excludedIds = emptySet(),
            ),
        )
    }

    @Test fun quickPicksDeduplicateSourcesAndExcludeAlreadyRecentSongs() {
        val local = Song(
            videoId = "content://media/1", title = "Local", artist = "Artist",
            thumbnailUrl = null, localUri = "content://media/1",
        )
        val remote = canonicalAutoSong(row())!!
        assertEquals(
            listOf(local.videoId),
            canonicalAutoQuickPicks(
                local = listOf(local),
                downloads = listOf(local),
                remote = listOf(remote),
                excludedIds = setOf(remote.videoId),
            ).map { it.videoId },
        )
    }

    @Test fun autoRecentsContainOnlyAlreadyVisitedVerifiedAudio() {
        val historical = Song(videoId = "yt-legacy", title = "Old", artist = "Artist", thumbnailUrl = null)
        val local = Song(
            videoId = "local-1", title = "Local", artist = "Artist", thumbnailUrl = null,
            localUri = "content://media/external/audio/media/9",
        )
        val remote = canonicalAutoSong(row())!!
        val future = Song(
            videoId = "local-future", title = "Future", artist = "Artist", thumbnailUrl = null,
            localUri = "content://media/external/audio/media/10",
        )
        val results = canonicalAutoRecentSongs(
            songs = listOf(historical, local, remote, future),
            currentIndex = 2,
            verifiedDownloadUri = { null },
        )
        assertEquals(listOf(remote.videoId, local.videoId), results.map { it.videoId })
    }

    @Test fun autoRecentsCanKeepDownloadedHistoricalIdentityButNotUnverifiedRemoteUrl() {
        val historical = Song(videoId = "yt-legacy", title = "Saved", artist = "Artist", thumbnailUrl = null)
        val unsafe = Song(
            videoId = "https://example.invalid/stream",
            title = "Unsafe", artist = "Artist", thumbnailUrl = null,
        )
        val results = canonicalAutoRecentSongs(
            songs = listOf(historical, unsafe),
            currentIndex = 1,
            verifiedDownloadUri = { id ->
                if (id == historical.videoId) "file:///music/saved.flac" else null
            },
        )
        assertEquals(listOf("yt-legacy"), results.map { it.videoId })
        assertEquals("file:///music/saved.flac", results.first().localUri)
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

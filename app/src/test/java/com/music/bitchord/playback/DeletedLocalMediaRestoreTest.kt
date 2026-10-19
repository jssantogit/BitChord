package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedLocalMediaRestoreTest {
    private val deletedUri = "content://media/external/audio/media/42"

    @Test
    fun `deleted last played local track is not restored in mini player and remaining queue stays intact`() {
        val deleted = local("deleted", deletedUri, "deleted-entry")
        val next = local("next", "content://media/external/audio/media/43", "next-entry")
        val remote = remote("remote", "remote-entry")
        val snapshot = LastPlayed.Snapshot(listOf(deleted, next, remote), 0, 88_000L)
        val checked = mutableListOf<String>()

        val result = requireNotNull(DeletedLocalMediaRestore.filter(snapshot) { song ->
            checked += song.videoId
            song.localUri == deletedUri
        })

        assertEquals(listOf("next", "remote"), result.songs.map { it.videoId })
        assertEquals(listOf("next-entry", "remote-entry"), result.songs.map { it.queueEntryId })
        assertEquals(0, result.index)
        assertEquals(0L, result.positionMs)
        assertEquals("next", result.songs[result.index].videoId)
        assertEquals(listOf("deleted", "next"), checked)
        assertFalse(checked.contains("remote"))
    }

    @Test
    fun `deleting current tail selects nearest surviving predecessor without restoring deleted song`() {
        val previous = local("previous", "content://media/external/audio/media/7", "previous-entry")
        val deleted = local("deleted", deletedUri, "deleted-entry")
        val result = requireNotNull(DeletedLocalMediaRestore.filter(
            LastPlayed.Snapshot(listOf(previous, deleted), 1, 99_000L),
        ) { it.localUri == deletedUri })

        assertEquals(listOf("previous"), result.songs.map { it.videoId })
        assertEquals(0, result.index)
        assertEquals(0L, result.positionMs)
    }

    @Test
    fun `a surviving current item retains position when deleted history is removed`() {
        val deleted = local("deleted", deletedUri, "deleted-entry")
        val playing = local("playing", "content://media/external/audio/media/8", "playing-entry")
        val result = requireNotNull(DeletedLocalMediaRestore.filter(
            LastPlayed.Snapshot(listOf(deleted, playing), 1, 31_000L),
        ) { it.localUri == deletedUri })

        assertEquals(listOf("playing"), result.songs.map { it.videoId })
        assertEquals(0, result.index)
        assertEquals(31_000L, result.positionMs)
    }

    @Test
    fun `all deleted locals leave an empty player instead of phantom last played`() {
        val result = DeletedLocalMediaRestore.filter(
            LastPlayed.Snapshot(listOf(local("deleted", deletedUri, "gone")), 0, 2_100L),
        ) { true }

        assertNull(result)
    }

    @Test
    fun `remote canonical and legacy entries remain untouched and never probe local existence`() {
        val remote = remote("remote", "remote-entry")
        val legacy = Song(
            videoId = "legacy-provider", title = "Legacy", artist = "Artist",
            thumbnailUrl = null, queueTier = QueueTier.USER_QUEUE,
            queueEntryId = "legacy-entry",
        )
        val result = requireNotNull(DeletedLocalMediaRestore.filter(
            LastPlayed.Snapshot(listOf(remote, legacy), 1, 23_000L),
        ) { error("Remote/legacy must never be checked as local files") })

        assertEquals(listOf("remote", "legacy-provider"), result.songs.map { it.videoId })
        assertEquals(1, result.index)
        assertEquals(23_000L, result.positionMs)
    }

    @Test
    fun `a path only local row is validated without assuming remote file availability`() {
        val fileSong = local("path-only", null, "path-entry").copy(localPath = "/music/no-longer-there.flac")
        val remote = remote("remote", "remote-entry")
        var checks = 0

        val result = DeletedLocalMediaRestore.filter(
            LastPlayed.Snapshot(listOf(fileSong, remote), 0, 1_000L),
        ) {
            checks++
            it.localPath == fileSong.localPath
        }

        assertEquals(1, checks)
        assertEquals("remote", result?.songs?.single()?.videoId)
        assertEquals(0L, result?.positionMs)
    }

    @Test
    fun `only an exact MediaStore audio row is safe to declare deleted`() {
        assertTrue(DeletedLocalMediaAvailability.isMediaStoreAudioItemUri(deletedUri))
        assertTrue(DeletedLocalMediaAvailability.isMediaStoreAudioItemUri(
            "content://media/external_primary/audio/media/74",
        ))
        assertFalse(DeletedLocalMediaAvailability.isMediaStoreAudioItemUri(
            "content://media/external/audio/albumart/42",
        ))
        assertFalse(DeletedLocalMediaAvailability.isMediaStoreAudioItemUri(
            "content://media/external/audio/media/42?apiKey=do-not-read",
        ))
        assertFalse(DeletedLocalMediaAvailability.isMediaStoreAudioItemUri(
            "content://other.provider/external/audio/media/42",
        ))
        assertFalse(DeletedLocalMediaAvailability.isMediaStoreAudioItemUri(
            "content://media/external/audio/media/not-a-row",
        ))
    }

    @Test
    fun `a missing file under a still readable folder is definite deletion`() {
        val directory = java.nio.file.Files.createTempDirectory("bitchord-b2-").toFile()
        val file = java.io.File(directory, "song.flac")
        try {
            assertTrue(file.createNewFile())
            assertFalse(DeletedLocalMediaAvailability.missingReadableFile(file))
            assertTrue(file.delete())
            assertTrue(DeletedLocalMediaAvailability.missingReadableFile(file))
        } finally {
            file.delete()
            directory.delete()
        }
    }

    private fun local(id: String, uri: String?, entryId: String) = Song(
        videoId = id, title = id, artist = "Artist", thumbnailUrl = null,
        localUri = uri, queueEntryId = entryId, queueTier = QueueTier.CONTEXT,
    )

    private fun remote(id: String, entryId: String) = Song(
        videoId = id, title = "Remote", artist = "Artist", thumbnailUrl = null,
        localUri = CanonicalRemotePlaybackUri.encode(
            CanonicalRemotePlaybackRequest(
                recordingId = "recording-remote",
                mediaAssetId = "asset-remote",
                providerKey = "opensubsonic:home",
            ),
        ),
        queueEntryId = entryId,
        queueTier = QueueTier.USER_QUEUE,
    )
}

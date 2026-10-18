package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalQueuePersistenceV2Test {

    @Test
    fun `canonical snapshot uses v2 and never persists physical identity fields`() {
        val source = QueueSource("Mixed queue", PlaybackSourceType.BROWSE, "mixed")
        val local = Song(
            videoId = "legacy-local-video-id",
            title = "Local",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "content://media/external/audio/media/42",
            localPath = "/storage/emulated/0/Music/Local.flac",
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "local-entry",
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        )
        val context = ContextSnapshot(
            contextToken = "context-token",
            sourceKey = "forbidden-source-key",
            browseId = source.id,
            songs = listOf(local.copy(queueEntryId = null)),
            version = 1L,
            continuationToken = null,
            continuationState = ContinuationState.EXHAUSTED,
        )
        val state = BoundedContextPersistence.State(
            context = context,
            source = source,
            window = QueueWindow(1).startAt(0).snapshot(),
            entries = listOf(BoundedContextPersistence.Entry(local, canonicalIndex = 0, cycleId = 0L)),
            currentIndex = 0,
            positionMs = 12_345L,
        )

        val raw = requireNotNull(BoundedContextPersistence.encode(state))

        assertTrue(raw.contains("\"schemaVersion\":2"))
        assertFalse(raw.contains("forbidden-source-key"))
        assertFalse(raw.contains("legacy-local-video-id"))
        assertFalse(raw.contains("content://media/external/audio/media/42"))
        assertFalse(raw.contains("/storage/emulated/0/Music/Local.flac"))
    }
}

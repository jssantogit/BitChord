package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CanonicalQueuePersistenceTest {

    @Test
    fun `bounded canonical queue round trips without provider video id`() {
        val source = QueueSource("Local album", PlaybackSourceType.BROWSE, "local-album")
        val identity = CanonicalQueueIdentity("recording-uuid")
        val song = Song(
            videoId = "",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "content://media/external/audio/media/42",
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "queue-entry",
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        )
        val context = ContextSnapshot(
            contextToken = "context-token",
            sourceKey = "${source.type.name}:${source.id}",
            browseId = source.id,
            songs = listOf(song.copy(queueEntryId = null)),
            version = 1L,
            continuationToken = null,
            continuationState = ContinuationState.EXHAUSTED,
        )
        val window = QueueWindow(1).startAt(0).snapshot()
        val state = BoundedContextPersistence.State(
            context = context,
            source = source,
            window = window,
            entries = listOf(
                BoundedContextPersistence.Entry(
                    song = song,
                    canonicalIndex = 0,
                    cycleId = 0L,
                    canonicalIdentity = identity,
                ),
            ),
            currentIndex = 0,
            positionMs = 12_345L,
            contextIdentities = listOf(identity),
        )

        val raw = BoundedContextPersistence.encode(state)
        assertNotNull(raw)
        val restored = requireNotNull(BoundedContextPersistence.decode(requireNotNull(raw)))
        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(restored))

        assertEquals(listOf(identity), restored.contextIdentities)
        assertEquals(identity, projected.entries.single().canonicalIdentity)
        assertEquals("", projected.entries.single().song.videoId)
        assertEquals(12_345L, projected.positionMs)
    }
}

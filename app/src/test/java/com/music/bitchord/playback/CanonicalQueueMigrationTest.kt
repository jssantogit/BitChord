package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalQueueMigrationTest {

    @Test
    fun `batched identification keeps anchored and unanchored results aligned`() = runBlocking {
        val source = QueueSource("Mixed", PlaybackSourceType.BROWSE, "mixed")
        val contextSong = song("context", source, QueueTier.CONTEXT, "context-entry")
        val userSong = song("user", source, QueueTier.USER_QUEUE, "user-entry")
        val contextIdentity = CanonicalQueueIdentity(
            recordingId = "recording-context",
            mediaAssetId = "asset-context",
            providerKey = RoomCanonicalQueueResolver.LOCAL_PROVIDER,
        )
        val userIdentity = CanonicalQueueIdentity(
            recordingId = "recording-user",
            mediaAssetId = "asset-user",
            providerKey = RoomCanonicalQueueResolver.LOCAL_PROVIDER,
        )
        val state = BoundedContextPersistence.State(
            context = ContextSnapshot(
                contextToken = "context-token",
                sourceKey = "${source.type.name}:${source.id}",
                browseId = source.id,
                songs = listOf(contextSong.copy(queueEntryId = null)),
                version = 0L,
                continuationToken = null,
                continuationState = ContinuationState.EXHAUSTED,
            ),
            source = source,
            window = QueueWindow(1).startAt(0).snapshot(),
            entries = listOf(
                BoundedContextPersistence.Entry(contextSong, canonicalIndex = 0, cycleId = 0L),
                BoundedContextPersistence.Entry(userSong),
            ),
            currentIndex = 0,
            positionMs = 0L,
        )
        var calls = 0
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> {
                calls += 1
                assertEquals(listOf("context", "context", "user"), songs.map { it.videoId })
                return listOf(contextIdentity, contextIdentity, userIdentity)
            }

            override suspend fun resolveAll(
                identities: List<CanonicalQueueIdentity?>,
            ): List<CanonicalPlayableAsset?> = error("not part of canonicalize")
        }

        val canonicalized = CanonicalQueueMigration.canonicalize(state, resolver)

        assertEquals(1, calls)
        assertEquals(listOf(contextIdentity), canonicalized.contextIdentities)
        assertEquals(contextIdentity, canonicalized.entries[0].canonicalIdentity)
        assertEquals(userIdentity, canonicalized.entries[1].canonicalIdentity)
    }

    private fun song(
        id: String,
        source: QueueSource,
        tier: QueueTier,
        entryId: String,
    ) = Song(
        videoId = id,
        title = "Track $id",
        artist = "Artist",
        thumbnailUrl = null,
        queueTier = tier,
        queueEntryId = entryId,
        playbackSource = source.title,
        playbackSourceType = source.type,
        playbackSourceId = source.id,
    )
}

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
        val state = state(source, contextSong, userSong)
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

    @Test
    fun `anchored entry identity seeds context without rediscovery`() = runBlocking {
        val source = QueueSource("Remote", PlaybackSourceType.BROWSE, "remote")
        val contextSong = song("legacy-envelope", source, QueueTier.CONTEXT, "context-entry")
        val identity = CanonicalQueueIdentity(
            recordingId = "recording-remote",
            mediaAssetId = "asset-remote",
            providerKey = "opensubsonic:account",
        )
        val base = state(source, contextSong, null).copy(
            entries = listOf(
                BoundedContextPersistence.Entry(
                    song = contextSong,
                    canonicalIndex = 0,
                    cycleId = 0L,
                    canonicalIdentity = identity,
                ),
            ),
        )
        var identified = 0
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> {
                identified += songs.size
                return songs.map { null }
            }

            override suspend fun resolveAll(
                identities: List<CanonicalQueueIdentity?>,
            ): List<CanonicalPlayableAsset?> = error("not part of canonicalize")
        }

        val canonicalized = CanonicalQueueMigration.canonicalize(base, resolver)

        assertEquals(0, identified)
        assertEquals(listOf(identity), canonicalized.contextIdentities)
        assertEquals(identity, canonicalized.entries.single().canonicalIdentity)
    }

    private fun state(
        source: QueueSource,
        contextSong: Song,
        userSong: Song?,
    ) = BoundedContextPersistence.State(
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
        entries = buildList {
            add(BoundedContextPersistence.Entry(contextSong, canonicalIndex = 0, cycleId = 0L))
            userSong?.let { add(BoundedContextPersistence.Entry(it)) }
        },
        currentIndex = 0,
        positionMs = 0L,
    )

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

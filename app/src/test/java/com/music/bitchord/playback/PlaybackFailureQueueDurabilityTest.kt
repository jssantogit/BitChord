package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureQueueDurabilityTest {

    @Test
    fun `failed item remains in queue and failure cascade does not rewrite snapshot`() {
        val state = canonicalState()
        val before = requireNotNull(BoundedContextPersistence.encode(state))
        val cascade = PlaybackFailureCascade()
        val runtimeQueue = failureEntries(state)

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(runtimeQueue[0], runtimeQueue, FailureRepeatMode.OFF),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Advance(2),
            cascade.onFailure(runtimeQueue[1], runtimeQueue, FailureRepeatMode.OFF),
        )

        val after = requireNotNull(BoundedContextPersistence.encode(state))
        assertEquals(before, after)

        val decoded = requireNotNull(BoundedContextPersistence.decode(after))
        assertEquals(3, decoded.entries.size)
        assertEquals(
            listOf("recording-a", "recording-b", "recording-c"),
            decoded.entries.map { it.canonicalIdentity?.recordingId },
        )
        assertEquals("entry-a", decoded.entries[0].song.queueEntryId)
        assertEquals("entry-b", decoded.entries[1].song.queueEntryId)
        assertEquals("entry-c", decoded.entries[2].song.queueEntryId)
    }

    @Test
    fun `restart after runtime failures restores original queue intent`() {
        val original = canonicalState()
        val persistedBeforeFailure = requireNotNull(BoundedContextPersistence.encode(original))
        val cascade = PlaybackFailureCascade()
        val runtimeQueue = failureEntries(original)

        cascade.onFailure(runtimeQueue[0], runtimeQueue, FailureRepeatMode.ALL)
        cascade.onFailure(runtimeQueue[1], runtimeQueue, FailureRepeatMode.ALL)
        assertTrue(cascade.isActive)

        // Process death discards only transient failure state. Durable state is
        // decoded from the snapshot written before the failures occurred.
        val restoredAfterRestart = BoundedContextPersistence.decode(persistedBeforeFailure)
        assertNotNull(restoredAfterRestart)
        val restored = requireNotNull(restoredAfterRestart)

        assertEquals(original.currentIndex, restored.currentIndex)
        assertEquals(original.positionMs, restored.positionMs)
        assertEquals(
            original.entries.map { it.canonicalIdentity },
            restored.entries.map { it.canonicalIdentity },
        )
        assertEquals(
            listOf("entry-a", "entry-b", "entry-c"),
            restored.entries.map { it.song.queueEntryId },
        )

        val freshProcessCascade = PlaybackFailureCascade()
        assertTrue(freshProcessCascade.failedKeys.isEmpty())
    }

    private fun failureEntries(state: BoundedContextPersistence.State): List<FailureQueueEntry> =
        state.entries.mapIndexed { index, entry ->
            FailureQueueEntry(
                key = failureEntryKey(
                    tier = entry.song.queueTier,
                    canonicalIndex = entry.canonicalIndex,
                    queueEntryId = entry.song.queueEntryId,
                    physicalIndex = index,
                ),
                index = index,
            )
        }

    private fun canonicalState(): BoundedContextPersistence.State {
        val source = QueueSource("Failure durability", PlaybackSourceType.BROWSE, "failure-durability")
        val songs = listOf(
            song("a", "entry-a", source),
            song("b", "entry-b", source),
            song("c", "entry-c", source),
        )
        val identities = listOf(
            identity("a"),
            identity("b"),
            identity("c"),
        )
        return BoundedContextPersistence.State(
            context = ContextSnapshot(
                contextToken = "failure-durability-token",
                sourceKey = "${source.type.name}:${source.id}",
                browseId = source.id,
                songs = songs.map { it.copy(queueEntryId = null) },
                version = 1L,
                continuationToken = null,
                continuationState = ContinuationState.EXHAUSTED,
            ),
            source = source,
            window = QueueWindow(songs.size).startAt(0).snapshot(),
            entries = songs.mapIndexed { index, song ->
                BoundedContextPersistence.Entry(
                    song = song,
                    canonicalIndex = index,
                    cycleId = 0L,
                    canonicalIdentity = identities[index],
                )
            },
            currentIndex = 0,
            positionMs = 4_321L,
            contextIdentities = identities,
        )
    }

    private fun identity(suffix: String) = CanonicalQueueIdentity(
        recordingId = "recording-$suffix",
        mediaAssetId = "asset-$suffix",
        providerKey = "opensubsonic:account",
    )

    private fun song(
        suffix: String,
        entryId: String,
        source: QueueSource,
    ) = Song(
        videoId = "legacy-$suffix",
        title = "Track $suffix",
        artist = "Artist",
        thumbnailUrl = null,
        localUri = CanonicalRemotePlaybackUri.encode(
            CanonicalRemotePlaybackRequest(
                recordingId = "recording-$suffix",
                mediaAssetId = "asset-$suffix",
                providerKey = "opensubsonic:account",
            ),
        ),
        queueTier = QueueTier.CONTEXT,
        queueEntryId = entryId,
        playbackSource = source.title,
        playbackSourceType = source.type,
        playbackSourceId = source.id,
    )
}

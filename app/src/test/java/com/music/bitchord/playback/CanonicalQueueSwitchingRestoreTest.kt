package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.CanonicalPlaybackArtworkIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3: exercise real canonical v2 codec, resolver hand-off and restored bounded
 * projection without contacting remote account state, network or player audio.
 */
class CanonicalQueueSwitchingRestoreTest {
    private val remoteA = CanonicalQueueIdentity("recording-a", "asset-a", "opensubsonic:home")
    private val remoteB = CanonicalQueueIdentity("recording-b", "asset-b", "opensubsonic:home")
    private val local = CanonicalQueueIdentity("recording-local", "asset-local", RoomCanonicalQueueResolver.LOCAL_PROVIDER)
    private val source = QueueSource("Mixed", PlaybackSourceType.BROWSE, "mixed-provider-queue")

    @Test
    fun `late A result must never be installed in selected B queue slot`() = runBlocking {
        val decoded = persistedQueue(listOf(remoteA, remoteB), selectedIndex = 1)
        val resolver = Resolver { identities ->
            // Simulate a provider returning the previous A result in B's slot.
            identities.map { requested ->
                when (requested) {
                    remoteA -> asset(remoteA)
                    remoteB -> asset(remoteA)
                    else -> null
                }
            }
        }
        assertNull(CanonicalQueueMigration.rehydrate(decoded, resolver))
    }

    @Test
    fun `same recording but previously selected media asset cannot leak through resolver`() = runBlocking {
        val previous = CanonicalQueueIdentity("one-recording", "asset-old", "opensubsonic:home")
        val selected = previous.copy(mediaAssetId = "asset-new")
        val decoded = persistedQueue(listOf(previous, selected), selectedIndex = 1)
        val resolver = Resolver { ids -> ids.map { item ->
            when (item) {
                previous -> asset(previous)
                selected -> asset(selected).copy(uri = remoteUri(previous))
                else -> null
            }
        } }
        assertNull(CanonicalQueueMigration.rehydrate(decoded, resolver))
    }

    @Test
    fun `switch A B local A restores exact order selected rendition current and seek without network`() = runBlocking {
        val decoded = persistedQueue(
            listOf(remoteA, remoteB, local, remoteA),
            selectedIndex = 3,
            positionMs = 42_314L,
        )
        var calls = 0
        val resolver = Resolver { identities ->
            calls++
            // Remote request is reconstructed strictly from canonical identities,
            // even when the account is offline. Only local URI is catalog-backed.
            identities.map(::asset)
        }
        val restored = requireNotNull(CanonicalQueueMigration.rehydrate(decoded, resolver))
        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(restored))

        assertEquals(1, calls)
        assertEquals(listOf(remoteA, remoteB, local, remoteA), projected.entries.map { it.canonicalIdentity })
        assertEquals(listOf(remoteUri(remoteA), remoteUri(remoteB),
            "content://media/external/audio/media/77", remoteUri(remoteA)),
            projected.entries.map { it.song.localUri })
        assertEquals(listOf("entry-0", "entry-1", "entry-2", "entry-3"),
            projected.entries.map { it.song.queueEntryId })
        assertEquals(3, projected.currentIndex)
        assertEquals(42_314L, projected.positionMs)

        val artwork = projected.entries.map { CanonicalPlaybackArtworkIdentity.fromSong(it.song) }
        assertEquals(listOf("asset-a", "asset-b", null, "asset-a"),
            artwork.map { it?.mediaAssetId })
        assertTrue(projected.entries[2].song.localUri!!.startsWith("content://media/"))
        assertFalse(CanonicalRemotePlaybackUri.isCanonical(projected.entries[2].song.localUri!!))
    }

    @Test
    fun `wrong remote URI routing or rendition rejects entire canonical restore rather than legacy fallback`() = runBlocking {
        val selected = persistedQueue(listOf(remoteB), selectedIndex = 0)
        val forged = CanonicalRemotePlaybackUri.encode(
            CanonicalRemotePlaybackRequest("recording-b", "asset-b", "opensubsonic:other"),
        )
        val badResolver = Resolver { ids -> ids.map { CanonicalPlayableAsset(it!!, forged) } }
        val outcome = BoundedContextPersistence.restoreOutcome(selected, badResolver)
        assertTrue(outcome is BoundedContextPersistence.RestoreOutcome.CanonicalUnavailable)
        assertFalse(outcome.mayFallbackToLastPlayed)
    }

    private fun asset(identity: CanonicalQueueIdentity): CanonicalPlayableAsset {
        if (identity == local) return CanonicalPlayableAsset(
            identity,
            uri = "content://media/external/audio/media/77",
            path = "/music/current-local.flac",
        )
        return CanonicalPlayableAsset(identity, remoteUri(identity))
    }

    private fun remoteUri(identity: CanonicalQueueIdentity): String =
        CanonicalRemotePlaybackUri.encode(CanonicalRemotePlaybackRequest(
            identity.recordingId,
            requireNotNull(identity.mediaAssetId),
            requireNotNull(identity.providerKey),
        ))

    private fun persistedQueue(
        order: List<CanonicalQueueIdentity>,
        selectedIndex: Int,
        positionMs: Long = 12_345L,
    ): BoundedContextPersistence.State {
        // Repeat A uses a USER_QUEUE entry to retain repeated canonical identity
        // without forging an extra logical context song.
        val contextIds = order.distinct()
        val context = contextIds.mapIndexed { index, identity -> song(identity, "context-$index") }
        val windowStart = contextIds.indexOf(order.first())
        val entries = order.mapIndexed { index, identity ->
            val contextIndex = contextIds.indexOf(identity)
            val isRepeat = index > order.indexOf(identity)
            BoundedContextPersistence.Entry(
                song = song(identity, "entry-$index").copy(
                    queueTier = if (isRepeat) QueueTier.USER_QUEUE else QueueTier.CONTEXT,
                ),
                canonicalIndex = if (isRepeat) null else contextIndex,
                cycleId = if (isRepeat) null else 0L,
                canonicalIdentity = identity,
            )
        }
        val state = BoundedContextPersistence.State(
            context = ContextSnapshot(
                contextToken = "b3-queue",
                sourceKey = "${source.type.name}:${source.id}",
                browseId = source.id,
                songs = context,
                version = 0L,
                continuationToken = null,
                continuationState = ContinuationState.EXHAUSTED,
            ),
            source = source,
            window = QueueWindow(context.size).startAt(windowStart).snapshot(),
            entries = entries,
            currentIndex = selectedIndex,
            positionMs = positionMs,
            contextIdentities = contextIds,
        )
        return requireNotNull(BoundedContextPersistence.decode(
            requireNotNull(BoundedContextPersistence.encode(state)),
        ))
    }

    private fun song(id: CanonicalQueueIdentity, entryId: String) = Song(
        videoId = "compatibility-$entryId",
        title = entryId,
        artist = "Artist",
        thumbnailUrl = null,
        queueTier = QueueTier.CONTEXT,
        queueEntryId = if (entryId.startsWith("context-")) null else entryId,
        playbackSource = source.title,
        playbackSourceType = source.type,
        playbackSourceId = source.id,
        localUri = if (id == local) "content://media/external/audio/media/77" else remoteUri(id),
    )

    private class Resolver(
        private val callback: (List<CanonicalQueueIdentity?>) -> List<CanonicalPlayableAsset?>,
    ) : CanonicalQueueResolver {
        override suspend fun identifyAll(songs: List<Song>) =
            error("Must not rediscover identities from the legacy Song envelope")
        override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>) = callback(identities)
    }
}

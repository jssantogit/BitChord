package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalQueueRestorePolicyTest {

    @Test
    fun `unavailable canonical snapshot must not fall back to LastPlayed`() = runBlocking {
        val identity = CanonicalQueueIdentity(
            recordingId = "recording-local",
            mediaAssetId = "missing-local-asset",
            providerKey = RoomCanonicalQueueResolver.LOCAL_PROVIDER,
        )
        val state = canonicalState(identity)
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>) = error("restore must not identify")
            override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>) =
                identities.map { null }
        }

        val outcome = BoundedContextPersistence.restoreOutcome(state, resolver)

        assertTrue(outcome is BoundedContextPersistence.RestoreOutcome.CanonicalUnavailable)
        assertFalse(outcome.mayFallbackToLastPlayed)
    }

    @Test
    fun `decoded canonical queue that cannot project must never resurrect LastPlayed after service recreation`() {
        val selected = CanonicalQueueIdentity(
            recordingId = "rec-selected",
            mediaAssetId = "asset-selected",
            providerKey = "opensubsonic:home",
        )
        val stored = canonicalState(selected)
        val reloaded = requireNotNull(BoundedContextPersistence.decode(
            requireNotNull(BoundedContextPersistence.encode(stored)),
        ))

        assertFalse(PlaybackLifecycleRestoreGate.mayFallBackAfterFailedProjection(reloaded))
        // Canonical snapshot must remain authoritative despite corrupt
        // projection, absent artwork, a temporarily offline server, or a
        // stopped service; it is not evidence to resume legacy playback.
    }

    @Test
    fun `legacy-only projection failure preserves compatibility restoration gate`() {
        val prior = canonicalState(CanonicalQueueIdentity("rec", "asset", "opensubsonic:home"))
        val legacy = prior.copy(
            contextIdentities = emptyList(),
            entries = prior.entries.map { it.copy(canonicalIdentity = null) },
        )
        assertTrue(PlaybackLifecycleRestoreGate.mayFallBackAfterFailedProjection(legacy))
    }

    @Test
    fun `absent or invalid bounded snapshot may still use LastPlayed compatibility`() {
        val outcome = BoundedContextPersistence.RestoreOutcome.AbsentOrInvalid

        assertTrue(outcome.mayFallbackToLastPlayed)
    }

    private fun canonicalState(identity: CanonicalQueueIdentity): BoundedContextPersistence.State {
        val source = QueueSource("Local", PlaybackSourceType.BROWSE, "local")
        val song = Song(
            videoId = "canonical-placeholder",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "entry",
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        )
        return BoundedContextPersistence.State(
            context = ContextSnapshot(
                contextToken = "context-token",
                sourceKey = "${source.type.name}:${source.id}",
                browseId = source.id,
                songs = listOf(song.copy(queueEntryId = null)),
                version = 0L,
                continuationToken = null,
                continuationState = ContinuationState.EXHAUSTED,
            ),
            source = source,
            window = QueueWindow(1).startAt(0).snapshot(),
            entries = listOf(
                BoundedContextPersistence.Entry(song, 0, 0L, identity),
            ),
            currentIndex = 0,
            positionMs = 123L,
            contextIdentities = listOf(identity),
        )
    }
}

package com.music.bitchord.playback

import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CanonicalCollectionIntentPlannerTest {
    private fun track(id: String, vararg assets: Pair<String, String>) = CanonicalBrowseTrack(
        recordingId = id,
        title = "Track $id",
        artistDisplay = "Artist",
        durationMs = 1000L,
        artworkUri = null,
        availableAssets = assets.map { CanonicalBrowseMediaAsset(it.first, it.second) },
    )

    private class Resolver : CanonicalQueueResolver {
        var resolveCalls = 0
        var resultOverride: List<CanonicalPlayableAsset?>? = null
        override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> =
            error("Preview must never discover identities from legacy Song")
        override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>): List<CanonicalPlayableAsset?> {
            resolveCalls++
            return resultOverride ?: identities.map { id ->
                id?.let { CanonicalPlayableAsset(it, if (it.providerKey == "local-mediastore") "content://media/42" else
                    CanonicalRemotePlaybackUri.encode(CanonicalRemotePlaybackRequest(it.recordingId, it.mediaAssetId!!, it.providerKey!!))) }
            }
        }
    }

    @Test fun `preview preserves requested order and deduplicates by recording only`() = runBlocking {
        val resolver = Resolver()
        val library = CanonicalBrowseLibrary(listOf(track("rec-a", "asset-a" to "local-mediastore"),
            track("rec-b", "asset-b" to "opensubsonic:home")))
        val plan = CanonicalCollectionIntentPlanner(resolver).preview(
            listOf("rec-b", "rec-a", "rec-b"), CanonicalCollectionIntent.ADD_NEXT, library,
        )
        assertEquals(listOf("rec-b", "rec-a"), plan.selections.map { it.recordingId })
        assertEquals(listOf(2), plan.rejected.map { it.inputIndex })
        assertEquals(CollectionIntentRejection.DUPLICATE, plan.rejected.single().reason)
        assertEquals(CanonicalCollectionIntent.ADD_NEXT, plan.intent)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test fun `unavailable or ambiguous assets fail closed without choosing a provider`() = runBlocking {
        val plan = CanonicalCollectionIntentPlanner(Resolver()).preview(
            listOf("rec-missing", "rec-empty", "rec-ambiguous"), CanonicalCollectionIntent.PLAY_NOW,
            CanonicalBrowseLibrary(listOf(
                track("rec-empty"), track("rec-ambiguous",
                    "one" to "local-mediastore", "two" to "opensubsonic:home"),
            )),
        )
        assertTrue(plan.selections.isEmpty())
        assertEquals(listOf(CollectionIntentRejection.RECORDING_UNAVAILABLE,
            CollectionIntentRejection.NO_ASSET, CollectionIntentRejection.AMBIGUOUS_ASSET),
            plan.rejected.map { it.reason })
    }

    @Test fun `malformed recording ids and duplicate catalog rows are rejected`() = runBlocking {
        val plan = CanonicalCollectionIntentPlanner(Resolver()).preview(
            listOf(" ", "rec\nunsafe", "rec-a"), CanonicalCollectionIntent.ADD_TO_QUEUE,
            CanonicalBrowseLibrary(listOf(track("rec-a", "one" to "local-mediastore"),
                track("rec-a", "other" to "opensubsonic:home"))),
        )
        assertTrue(plan.selections.isEmpty())
        assertEquals(listOf(CollectionIntentRejection.INVALID_RECORDING,
            CollectionIntentRejection.INVALID_RECORDING, CollectionIntentRejection.AMBIGUOUS_RECORDING),
            plan.rejected.map { it.reason })
    }

    @Test fun `limit applies before any lookup or resolver work`() = runBlocking {
        val resolver = Resolver()
        val ids = (1..102).map { "rec-$it" }
        val plan = CanonicalCollectionIntentPlanner(resolver).preview(
            ids, CanonicalCollectionIntent.ADD_TO_QUEUE, CanonicalBrowseLibrary.EMPTY,
        )
        assertEquals(CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE, 100)
        assertEquals(CollectionIntentRejection.LIMIT_EXCEEDED, plan.rejected[100].reason)
        assertEquals(0, resolver.resolveCalls)
    }

    @Test fun `mismatched canonical resolution is rejected rather than played`() = runBlocking {
        val resolver = Resolver().apply {
            resultOverride = listOf(CanonicalPlayableAsset(
                CanonicalQueueIdentity("other", "asset-x", "opensubsonic:home"),
                "bitchord://canonical?recordingId=other&mediaAssetId=asset-x&providerKey=opensubsonic%3Ahome"))
        }
        val plan = CanonicalCollectionIntentPlanner(resolver).preview(
            listOf("rec-a"), CanonicalCollectionIntent.PLAY_NOW,
            CanonicalBrowseLibrary(listOf(track("rec-a", "asset-a" to "opensubsonic:home"))),
        )
        assertTrue(plan.selections.isEmpty())
        assertEquals(CollectionIntentRejection.RESOLUTION_MISMATCH, plan.rejected.single().reason)
    }

    @Test fun `empty request has no playback or resolver side effects`() = runBlocking {
        val resolver = Resolver()
        val plan = CanonicalCollectionIntentPlanner(resolver).preview(
            emptyList(), CanonicalCollectionIntent.PLAY_NOW, CanonicalBrowseLibrary.EMPTY)
        assertTrue(plan.selections.isEmpty())
        assertTrue(plan.rejected.isEmpty())
        assertEquals(0, resolver.resolveCalls)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates and never produces an executable partial plan`() = runBlocking {
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> = error("unused")
            override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>): List<CanonicalPlayableAsset?> =
                throw CancellationException("UI navigated away")
        }
        CanonicalCollectionIntentPlanner(resolver).preview(
            listOf("rec-a"), CanonicalCollectionIntent.PLAY_NOW,
            CanonicalBrowseLibrary(listOf(track("rec-a", "asset-a" to "local-mediastore"))))
    }
}

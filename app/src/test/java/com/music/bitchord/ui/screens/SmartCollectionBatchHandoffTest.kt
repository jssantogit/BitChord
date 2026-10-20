package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.listening.AdaptiveCollectionKind
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlan
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.playback.CanonicalPlayableAsset
import com.music.bitchord.playback.CanonicalQueueIdentity
import com.music.bitchord.playback.CanonicalQueueResolver
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A read-only fake of B9's resolver, never legacy-title identity inference. */
class SmartCollectionBatchHandoffTest {
    private class Resolver : CanonicalQueueResolver {
        var calls = 0
        override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> =
            error("L13 must not infer recording identity from Song")
        override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>): List<CanonicalPlayableAsset?> {
            calls++
            return identities.map { identity ->
                identity?.let {
                    CanonicalPlayableAsset(it, CanonicalRemotePlaybackUri.encode(
                        CanonicalRemotePlaybackRequest(it.recordingId, it.mediaAssetId!!, it.providerKey!!)))
                }
            }
        }
    }

    private fun track(id: String, assetCount: Int = 1) = CanonicalBrowseTrack(
        recordingId = id, title = "Title $id", artistDisplay = "Artist",
        durationMs = 150_000L, artworkUri = null,
        availableAssets = (1..assetCount).map {
            CanonicalBrowseMediaAsset("asset-$id-$it", "opensubsonic:test")
        },
    )

    @Test fun freshAdaptiveSnapshotPreservesOrderAndOnlyUserPlayIntentCanRequestPlayback() = runBlocking {
        var ids = listOf("rec-old")
        val submitted = mutableListOf<CanonicalCollectionIntentPlan>()
        val resolver = Resolver()
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { ids },
            browse = { CanonicalBrowseLibrary(listOf(track("rec-new"), track("rec-old"))) },
            planner = CanonicalCollectionIntentPlanner(resolver),
            submit = { submitted += it; true },
        )
        ids = listOf("rec-new", "rec-old")
        assertEquals(SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.Adaptive(
                AdaptiveCollectionKind.EXPLICIT_POSITIVES), CanonicalCollectionIntent.PLAY_NOW))
        assertEquals(listOf("rec-new", "rec-old"), submitted.single().selections.map { it.recordingId })
        assertEquals(CanonicalCollectionIntent.PLAY_NOW, submitted.single().intent)
        assertEquals(1, resolver.calls)
    }

    @Test fun explicitQueueActionDeduplicatesRecordingAndNeverSwitchesToPlay() = runBlocking {
        val submitted = mutableListOf<CanonicalCollectionIntentPlan>()
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("rec-a", "rec-a", "rec-b") },
            browse = { CanonicalBrowseLibrary(listOf(track("rec-a"), track("rec-b"))) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { submitted += it; true },
        )
        assertEquals(SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.SavedDefinition("rule-1"),
                CanonicalCollectionIntent.ADD_TO_QUEUE))
        assertEquals(listOf("rec-a", "rec-b"), submitted.single().selections.map { it.recordingId })
        assertEquals(CanonicalCollectionIntent.ADD_TO_QUEUE, submitted.single().intent)
    }

    @Test fun staleOrAmbiguousAssetFailsClosedBeforeSubmittingAnyQueueCommand() = runBlocking {
        var dispatches = 0
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("rec-a", "rec-b") },
            browse = { CanonicalBrowseLibrary(listOf(track("rec-a"), track("rec-b", 2))) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { dispatches++; true },
        )
        assertTrue(handoff.execute(SmartCollectionActionSource.SavedDefinition("rule"),
            CanonicalCollectionIntent.PLAY_NOW) is SmartCollectionBatchResult.Rejected)
        assertEquals(0, dispatches)
    }

    @Test fun emptyMissingInvalidAndExcessiveSnapshotsAreNotDispatched() = runBlocking {
        var available: List<String>? = null
        var dispatches = 0
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { available },
            browse = { CanonicalBrowseLibrary.EMPTY },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { dispatches++; true },
        )
        val source = SmartCollectionActionSource.SavedDefinition("rule")
        assertEquals(SmartCollectionBatchResult.Unavailable,
            handoff.execute(source, CanonicalCollectionIntent.ADD_NEXT))
        available = emptyList()
        assertEquals(SmartCollectionBatchResult.Empty,
            handoff.execute(source, CanonicalCollectionIntent.ADD_TO_QUEUE))
        available = (0..100).map { "rec-$it" }
        assertEquals(SmartCollectionBatchResult.LimitExceeded,
            handoff.execute(source, CanonicalCollectionIntent.PLAY_NOW))
        assertEquals(0, dispatches)
    }

    @Test fun failedExecutionIsNotReportedAsQueuedAndCancellationPropagates() = runBlocking {
        val resolver = Resolver()
        val source = SmartCollectionActionSource.SavedDefinition("rule")
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("rec-a") },
            browse = { CanonicalBrowseLibrary(listOf(track("rec-a"))) },
            planner = CanonicalCollectionIntentPlanner(resolver),
            submit = { false },
        )
        assertEquals(SmartCollectionBatchResult.ExecutionFailed,
            handoff.execute(source, CanonicalCollectionIntent.ADD_TO_QUEUE))
        val cancelled = SmartCollectionBatchHandoff(
            refreshIds = { throw CancellationException("navigated away") },
            browse = { error("should not browse") },
            planner = CanonicalCollectionIntentPlanner(resolver),
            submit = { error("should not submit") },
        )
        assertThrows(CancellationException::class.java) {
            runBlocking { cancelled.execute(source, CanonicalCollectionIntent.PLAY_NOW) }
        }
    }
}

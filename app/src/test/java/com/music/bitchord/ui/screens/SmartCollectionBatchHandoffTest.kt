package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.listening.AdaptiveCollectionKind
import com.music.bitchord.data.listening.RuleBasedSmartPlaylistQuery
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
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
        available = (0..500).map { "rec-$it" }
        assertEquals(SmartCollectionBatchResult.LimitExceeded,
            handoff.execute(source, CanonicalCollectionIntent.PLAY_NOW))
        assertEquals(0, dispatches)
    }

    @Test fun failedExecutionIsNotReportedAsQueuedAndCancellationPropagates(): Unit = runBlocking {
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

    @Test fun unsavedFiveStarPreviewIsRevalidatedOnEveryExplicitQueueAction() = runBlocking {
        val captured = mutableListOf<CanonicalCollectionIntentPlan>()
        val traces = mutableListOf<Map<String, String>>()
        val query = RuleBasedSmartPlaylistQuery(minimumExplicitRating = 5.0, limit = 10)
        var currentEligibleIds = listOf("rec-five")
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { source ->
                assertEquals(SmartCollectionActionSource.PreviewQuery(query), source)
                currentEligibleIds
            },
            browse = { CanonicalBrowseLibrary(listOf(track("rec-five"), track("rec-new"))) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { captured += it; true },
            trace = { traces += it },
        )
        // An outdated preview is never used as a static queue authorization.
        currentEligibleIds = listOf("rec-new")
        assertEquals(
            SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.PreviewQuery(query), CanonicalCollectionIntent.ADD_TO_QUEUE),
        )
        assertEquals(listOf("rec-new"), captured.single().selections.map { it.recordingId })
        assertEquals(CanonicalCollectionIntent.ADD_TO_QUEUE, captured.single().intent)
        assertTrue(traces.any { it["stage"] == "revalidated" && it["candidateCount"] == "1" })
        assertTrue(traces.any { it["stage"] == "planned" && it["selectionCount"] == "1" })
        assertTrue(traces.any { it["stage"] == "media3" && it["result"] == "command_accepted" })
        assertEquals(traces.first()["attempt"], captured.single().traceRef)
        assertFalse(traces.toString().contains("rec-new"))
    }

    @Test fun accountRevokeOrSavedDefinitionRemovalNeverReusesOldPreview() = runBlocking {
        var current: List<String>? = listOf("five")
        var calls = 0
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { current },
            browse = { CanonicalBrowseLibrary(listOf(track("five"))) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { calls++; true },
        )
        current = null
        assertEquals(
            SmartCollectionBatchResult.Unavailable,
            handoff.execute(SmartCollectionActionSource.SavedDefinition("removed"), CanonicalCollectionIntent.PLAY_NOW),
        )
        current = emptyList()
        assertEquals(
            SmartCollectionBatchResult.Empty,
            handoff.execute(SmartCollectionActionSource.SavedDefinition("removed"), CanonicalCollectionIntent.ADD_TO_QUEUE),
        )
        assertEquals(0, calls)
    }

    @Test fun remoteOnlyMultipleAssetsAreNotSilentlyChosen() = runBlocking {
        var calls = 0
        val trace = mutableListOf<Map<String, String>>()
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("rec-five") },
            browse = { CanonicalBrowseLibrary(listOf(track("rec-five", 2))) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { calls++; true },
            trace = { trace += it },
        )
        assertTrue(
            handoff.execute(SmartCollectionActionSource.SavedDefinition("five"), CanonicalCollectionIntent.PLAY_NOW)
                is SmartCollectionBatchResult.Rejected,
        )
        assertEquals(0, calls)
        assertTrue(trace.any {
            it["stage"] == "planned" && it["rejectionCodes"] == "AMBIGUOUS_ASSET:1"
        })
    }

    @Test fun uniqueCurrentAccountRatedRemoteAssetResolvesRemoteOnlyMultiAssetRecording() = runBlocking {
        val submitted = mutableListOf<CanonicalCollectionIntentPlan>()
        val candidates = listOf(
            CanonicalBrowseMediaAsset("unrated-copy", "opensubsonic:account"),
            CanonicalBrowseMediaAsset("rated-copy", "opensubsonic:account"),
        )
        val resolver = Resolver()
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("rated-recording") },
            browse = {
                CanonicalBrowseLibrary(listOf(CanonicalBrowseTrack(
                    recordingId = "rated-recording", title = "Track", artistDisplay = "Artist",
                    durationMs = 180_000L, artworkUri = null, availableAssets = candidates,
                )))
            },
            planner = CanonicalCollectionIntentPlanner(resolver),
            submit = { submitted += it; true },
            verifiedRemoteAssets = {
                mapOf("rated-recording" to candidates.last()) // scoped, unique 5/5 evidence
            },
        )
        assertEquals(
            SmartCollectionBatchResult.Submitted,
            handoff.execute(
                SmartCollectionActionSource.SavedDefinition("minimum-five"),
                CanonicalCollectionIntent.PLAY_NOW,
            ),
        )
        assertEquals("rated-copy", submitted.single().selections.single().identity.mediaAssetId)
    }

    @Test fun unknownOrUnscopedRatedAssetCannotBreakAmbiguity() = runBlocking {
        val actual = listOf(
            CanonicalBrowseMediaAsset("asset-one", "opensubsonic:active"),
            CanonicalBrowseMediaAsset("asset-two", "opensubsonic:active"),
        )
        var calls = 0
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("rated-recording") },
            browse = {
                CanonicalBrowseLibrary(listOf(CanonicalBrowseTrack(
                    recordingId = "rated-recording", title = "Track", artistDisplay = "Artist",
                    durationMs = 180_000L, artworkUri = null, availableAssets = actual,
                )))
            },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { calls++; true },
            verifiedRemoteAssets = { mapOf(
                "rated-recording" to CanonicalBrowseMediaAsset("foreign-account", "opensubsonic:old"),
            ) },
        )
        assertTrue(
            handoff.execute(
                SmartCollectionActionSource.SavedDefinition("minimum-five"),
                CanonicalCollectionIntent.PLAY_NOW,
            ) is SmartCollectionBatchResult.Rejected,
        )
        assertEquals(0, calls)
    }

    @Test fun twoLocalCopiesRemainAmbiguousEvenWithSingleRemoteHint() = runBlocking {
        var calls = 0
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("rated-recording") },
            browse = {
                CanonicalBrowseLibrary(listOf(CanonicalBrowseTrack(
                    recordingId = "rated-recording", title = "Track", artistDisplay = "Artist",
                    durationMs = 180_000L, artworkUri = null,
                    availableAssets = listOf(
                        CanonicalBrowseMediaAsset("local-one", "local-mediastore"),
                        CanonicalBrowseMediaAsset("local-two", "local-mediastore"),
                        CanonicalBrowseMediaAsset("rated-remote", "opensubsonic:active"),
                    ),
                )))
            },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { calls++; true },
            // A uniquely rated remote rendition is safe even with ambiguous
            // local copies; it is not permission to choose one arbitrary local.
            verifiedRemoteAssets = { mapOf(
                "rated-recording" to CanonicalBrowseMediaAsset("rated-remote", "opensubsonic:active"),
            ) },
        )
        assertEquals(
            SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.SavedDefinition("rule"), CanonicalCollectionIntent.PLAY_NOW),
        )
        assertEquals(1, calls)
    }
    @Test fun v2PreviewAndSavedSourceKeepQueueIntentAfterFreshRevalidation() = runBlocking {
        val previewQuery = SmartPlaylistV2Query(
            SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                1.0,
            ),
        )
        val submitted = mutableListOf<CanonicalCollectionIntentPlan>()
        var currentIds = listOf("rec:old")
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { currentIds },
            browse = { CanonicalBrowseLibrary(listOf(track("rec:old"), track("rec:new"))) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { submitted += it; true },
        )
        currentIds = listOf("rec:new")
        assertEquals(SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.PreviewV2Query(previewQuery),
                CanonicalCollectionIntent.ADD_TO_QUEUE))
        assertEquals("rec:new", submitted.single().selections.single().recordingId)
        assertEquals(CanonicalCollectionIntent.ADD_TO_QUEUE, submitted.single().intent)
        assertEquals(SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.SavedV2Definition("v2"),
                CanonicalCollectionIntent.PLAY_NOW))
        assertEquals(CanonicalCollectionIntent.PLAY_NOW, submitted.last().intent)
    }

    @Test fun v2VerifiedRatingHintRequiresAProvableGlobalMinimum() {
        fun rated(min: Double) = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.EXPLICIT_RATING,
            SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
            value = min,
        )
        fun duration() = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DURATION_MS,
            SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
            value = 180_000.0,
        )
        val direct = SmartPlaylistV2Query(rated(4.0))
        assertEquals(4.0, direct.guaranteedMinimumRatingForAssetHints())
        val and = SmartPlaylistV2Query(SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ALL, listOf(duration(), rated(3.0), rated(5.0)),
        ))
        assertEquals(5.0, and.guaranteedMinimumRatingForAssetHints())
        val validOr = SmartPlaylistV2Query(SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ANY, listOf(rated(4.0), rated(5.0)),
        ))
        assertEquals(4.0, validOr.guaranteedMinimumRatingForAssetHints())
        val unsafeOr = SmartPlaylistV2Query(SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ANY, listOf(duration(), rated(5.0)),
        ))
        assertNull(unsafeOr.guaranteedMinimumRatingForAssetHints())
        val negation = SmartPlaylistV2Query(SmartPlaylistRule.Not(rated(4.0)))
        assertNull(negation.guaranteedMinimumRatingForAssetHints())
        assertNull(SmartPlaylistV2Query(duration()).guaranteedMinimumRatingForAssetHints())
    }

    @Test fun largeSavedV2QueueSubmitsOneAtomic500ItemPlanAndNeverAutoplays() = runBlocking {
        val ids = (1..500).map { "rec-$it" }
        val submitted = mutableListOf<CanonicalCollectionIntentPlan>()
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { ids },
            browse = { CanonicalBrowseLibrary(ids.map(::track)) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { plan -> submitted += plan; true },
        )
        assertEquals(SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.SavedV2Definition("large"),
                CanonicalCollectionIntent.ADD_TO_QUEUE))
        assertEquals(1, submitted.size)
        assertEquals(500, submitted.single().selections.size)
        assertEquals(ids, submitted.single().selections.map { it.recordingId })
        assertEquals(CanonicalCollectionIntent.ADD_TO_QUEUE, submitted.single().intent)
    }

    @Test fun largeV2PreviewWithOneAmbiguousItemRejectsWholeOperationBeforeSubmit() = runBlocking {
        val ids = (1..251).map { "rec-$it" }
        var submissions = 0
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { ids },
            browse = { CanonicalBrowseLibrary(ids.map {
                if (it == "rec-251") track(it, 2) else track(it)
            }) },
            planner = CanonicalCollectionIntentPlanner(Resolver()),
            submit = { submissions++; true },
        )
        assertTrue(handoff.execute(
            SmartCollectionActionSource.SavedV2Definition("ambiguous"),
            CanonicalCollectionIntent.ADD_TO_QUEUE,
        ) is SmartCollectionBatchResult.Rejected)
        assertEquals(0, submissions)
    }

}

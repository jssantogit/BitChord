package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingReader
import com.music.bitchord.observability.SessionTraceReferenceEncoder
import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRecommendationTracingTest {
    @Test
    fun `library recommendation trace explains personal score session movement and final choice without raw ids`() = runTest {
        val sink = RecordingTraceSink()
        val tracer = StructuredTracer(
            mode = TraceMode.STANDARD,
            sink = sink,
            traceIdFactory = { "recommendation-run" },
            referenceEncoder = SessionTraceReferenceEncoder("diagnostic-session"),
        )
        val pipeline = pipeline(
            availableIds = listOf("recording-a", "recording-b", "recording-c"),
            ratings = mapOf(
                "recording-a" to 5.0,
                "recording-b" to 4.5,
                "recording-c" to 4.0,
            ),
            sessionEvents = listOf(
                listeningEvent(
                    id = "early-a",
                    recordingId = "recording-a",
                    type = ListeningEventType.PLAYBACK_SKIPPED_EARLY,
                ),
            ),
            traceReporter = StructuredRecommendationTraceReporter(
                tracer = tracer,
                policy = RecommendationTracePolicy(
                    standardTopResults = 2,
                    standardLargestMovers = 2,
                ),
            ),
        )

        val ranked = pipeline.rank(limit = 2)

        assertEquals(listOf("recording-b", "recording-a"), ranked.map { it.base.base.recordingId })
        assertTrue(sink.points.isNotEmpty())
        assertTrue(sink.points.all { it.traceId == "recommendation-run" })

        val details = sink.points.filter { it.stage == "candidate_explanation" }
        assertTrue(sink.points.any { it.stage == RecommendationTraceStage.ARTIST_DIVERSITY.key })
        assertTrue(details.isNotEmpty())
        val favorite = details.single { it.attributes["baseRank"] == "1" }
        assertEquals("2", favorite.attributes["contextualRank"])
        assertEquals("-1", favorite.attributes["rankDelta"])
        assertEquals("60.0", favorite.attributes["explicitPreference"])
        assertEquals("EXPLICIT_FAVORITE", favorite.attributes["personalReasons"])
        assertEquals("SESSION_EARLY_SKIP", favorite.attributes["sessionReasons"])
        assertTrue(favorite.attributes.getValue("candidateRef").startsWith("ref_"))

        val renderedAttributes = sink.points.flatMap { it.attributes.entries }
            .joinToString(separator = " ") { "${it.key}=${it.value}" }
        assertFalse(renderedAttributes.contains("recording-a"))
        assertFalse(renderedAttributes.contains("recording-b"))
        assertFalse(renderedAttributes.contains("recording-c"))
    }

    @Test
    fun `artist diversity and recommendation tracing yield the same bounded ranking without leaking artist identity`() = runTest {
        val ids = listOf("recording-a1", "recording-a2", "recording-b1")
        val ratings = mapOf("recording-a1" to 5.0, "recording-a2" to 4.9, "recording-b1" to 4.8)
        val grouping = mapOf(
            "recording-a1" to listOf("private-artist-a"),
            "recording-a2" to listOf("private-artist-a"),
            "recording-b1" to listOf("private-artist-b"),
        )
        val sink = RecordingTraceSink()
        val tracer = StructuredTracer(
            mode = TraceMode.STANDARD,
            sink = sink,
            traceIdFactory = { "diversity-run" },
            referenceEncoder = SessionTraceReferenceEncoder("diagnostic-session"),
        )
        val traced = pipeline(
            availableIds = ids,
            ratings = ratings,
            sessionEvents = emptyList(),
            traceReporter = StructuredRecommendationTraceReporter(tracer),
            artistGrouping = grouping,
        ).rank(limit = 2)
        val plain = pipeline(
            availableIds = ids,
            ratings = ratings,
            sessionEvents = emptyList(),
            traceReporter = RecommendationTraceReporter.NO_OP,
            artistGrouping = grouping,
        ).rank(limit = 2)

        assertEquals(plain, traced)
        assertEquals(2, traced.size)
        assertTrue(sink.points.all { it.traceId == "diversity-run" })
        val diversityStages = sink.points.filter { it.stage == RecommendationTraceStage.ARTIST_DIVERSITY.key }
        assertTrue(diversityStages.isNotEmpty())
        val finalPoint = sink.points.single { it.stage == "final_selection" }
        assertEquals("3", finalPoint.attributes["diversifiedCount"])
        val detail = sink.points.filter { it.stage == "candidate_explanation" }
        assertTrue(detail.isNotEmpty())
        assertTrue(detail.all { it.attributes["diversityRank"] != null })
        val rendered = sink.points.toString()
        assertFalse(rendered.contains("recording-a1"))
        assertFalse(rendered.contains("private-artist-a"))
        assertFalse(rendered.contains("private-artist-b"))
    }

    @Test
    fun `standard recommendation detail remains bounded for large candidate sets`() {
        val sink = RecordingTraceSink()
        val tracer = StructuredTracer(
            mode = TraceMode.STANDARD,
            sink = sink,
            traceIdFactory = { "bounded-run" },
            referenceEncoder = SessionTraceReferenceEncoder("diagnostic-session"),
        )
        val reporter = StructuredRecommendationTraceReporter(
            tracer = tracer,
            policy = RecommendationTracePolicy(
                standardTopResults = 3,
                standardLargestMovers = 2,
                verboseMaxCandidates = 10,
            ),
        )
        val run = reporter.startLibraryRank(limit = null)
        val base = (0 until 30).map { index ->
            ranked("recording-$index", score = (30 - index).toDouble())
        }
        val contextual = base.reversed().map { candidate ->
            SessionContextRankedCandidate(
                base = candidate,
                sessionAdjustment = 0.0,
                finalScore = candidate.score,
                sessionReasons = emptySet(),
            )
        }

        val diversified = contextual.map { item ->
            DiversityRankedCandidate(
                base = item,
                diversityAdjustment = 0.0,
                finalScore = item.finalScore,
                diversityReasons = emptySet(),
                overlappingArtistIds = emptySet(),
            )
        }
        run.complete(
            baseRanking = base,
            contextualRanking = contextual,
            diversifiedRanking = diversified,
            finalOutput = diversified,
        )

        val details = sink.points.filter { it.stage == "candidate_explanation" }
        assertTrue(details.size <= 5)
        assertTrue(details.all { it.attributes.getValue("candidateRef").startsWith("ref_") })
    }

    private fun pipeline(
        availableIds: List<String>,
        ratings: Map<String, Double>,
        sessionEvents: List<ListeningEvent>,
        traceReporter: RecommendationTraceReporter,
        artistGrouping: Map<String, List<String>> = emptyMap(),
    ): LibraryRecommendationPipeline {
        val source = LibraryRecommendationCandidateSource(FakeAvailabilityReader(availableIds))
        val ranker = PersonalRanker(
            behaviorReader = NeutralBehaviorReader,
            ratingReader = FakeRatingReader(ratings),
            nowEpochMs = { NOW },
        )
        val reranker = SessionContextReranker(
            contextReader = FakeSessionReader(sessionEvents),
            nowEpochMs = { NOW },
        )
        return LibraryRecommendationPipeline(
            candidateSource = source,
            personalRanker = ranker,
            sessionContextReranker = reranker,
            artistAwareDiversityReranker = ArtistAwareDiversityReranker(
                object : CanonicalRecordingArtistGroupingReader {
                    override suspend fun artistIdsByRecording(recordingIds: List<String>): Map<String, List<String>> =
                        recordingIds.associateWith { artistGrouping[it].orEmpty() }
                },
            ),
            traceReporter = traceReporter,
        )
    }

    private class FakeAvailabilityReader(
        private val ids: List<String>,
    ) : CanonicalLibraryAvailabilityReader {
        override suspend fun availableRecordingIds(): List<String> = ids
    }

    private class FakeSessionReader(
        private val events: List<ListeningEvent>,
    ) : ListeningSessionContextReader {
        override suspend fun currentSession(atEpochMs: Long): ListeningSession = ListeningSession(
            id = "session-current",
            startedAtEpochMs = 0L,
            lastActivityAtEpochMs = NOW,
            endedAtEpochMs = null,
        )

        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = events
    }

    private object NeutralBehaviorReader : ListeningBehaviorReader {
        override suspend fun session(sessionId: String): ListeningSession? = null
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = emptyList()
        override suspend fun recentEventsForRecording(recordingId: String, limit: Int): List<ListeningEvent> = emptyList()
        override suspend fun behaviorSummary(recordingId: String) = RecordingBehaviorSummary(
            recordingId = recordingId,
            starts = 0,
            completions = 0,
            earlySkips = 0,
            lateSkips = 0,
            interruptions = 0,
            totalListenedMs = 0,
            lastEventAtEpochMs = null,
        )
    }

    private class FakeRatingReader(
        private val ratings: Map<String, Double>,
    ) : ExplicitRecordingRatingReader {
        override suspend fun currentRating(recordingId: String): RecordingRating? =
            ratings[recordingId]?.let { RecordingRating(recordingId, it, ratedAtEpochMs = 0L) }

        override fun observeRating(recordingId: String): Flow<RecordingRating?> =
            flowOf(ratings[recordingId]?.let { RecordingRating(recordingId, it, ratedAtEpochMs = 0L) })

        override suspend fun ratedRecordings(limit: Int): List<RecordingRating> =
            ratings.entries.sortedBy { it.key }.take(limit)
                .map { (id, value) -> RecordingRating(id, value, ratedAtEpochMs = 0L) }

        override suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal {
            val value = ratings[recordingId]
            return ExplicitRecordingRatingSignal(
                recordingId = recordingId,
                currentRating = value,
                currentRatedAtEpochMs = value?.let { 0L },
                lastChangedAtEpochMs = value?.let { 0L },
                lastChangeDirection = value?.let { RecordingRatingDirection.INITIAL_SET },
                hasExplicitFeedback = value != null,
            )
        }
    }

    private class RecordingTraceSink : StructuredTraceSink {
        val points = mutableListOf<StructuredTracePoint>()
        override fun record(point: StructuredTracePoint) {
            points += point
        }
    }

    private companion object {
        const val NOW = 30L * 24L * 60L * 60L * 1000L

        fun listeningEvent(
            id: String,
            recordingId: String,
            type: ListeningEventType,
        ) = ListeningEvent(
            id = id,
            sessionId = "session-current",
            recordingId = recordingId,
            mediaAssetId = null,
            assetProvider = null,
            occurredAtEpochMs = NOW,
            type = type,
            listenedDurationMs = 1_000L,
            recordingDurationMs = 180_000L,
            endCause = null,
            selectionIntent = null,
            selectionOrigin = null,
            recommendationOrigin = null,
        )

        fun ranked(recordingId: String, score: Double) = RankedRecordingCandidate(
            recordingId = recordingId,
            score = score,
            components = RecommendationScoreComponents(
                explicitPreference = score,
                completionEvidence = 0.0,
                earlySkipPenalty = 0.0,
                lateSkipPenalty = 0.0,
                manualSelectionEvidence = 0.0,
                replayEvidence = 0.0,
                behavioralEvidence = 0.0,
                recentListeningCooldown = 0.0,
                recentRatingChange = 0.0,
                total = score,
            ),
            reasons = emptySet(),
            features = RecordingRecommendationFeatures(
                recordingId = recordingId,
                explicitRating = null,
                explicitRatedAtEpochMs = null,
                lastRatingChangedAtEpochMs = null,
                lastRatingChangeDirection = null,
                behaviorSummary = RecordingBehaviorSummary(
                    recordingId = recordingId,
                    starts = 0,
                    completions = 0,
                    earlySkips = 0,
                    lateSkips = 0,
                    interruptions = 0,
                    totalListenedMs = 0,
                    lastEventAtEpochMs = null,
                ),
                manualSelections = 0,
                replays = 0,
                lastAudibleListenAtEpochMs = null,
            ),
        )
    }
}
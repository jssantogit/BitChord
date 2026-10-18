package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRecommendationSessionContextPipelineTest {

    @Test
    fun `pipeline applies session context before diversity and limit so base outsider can become top result`() = runTest {
        val now = 30L * 24L * 60L * 60L * 1000L
        val source = LibraryRecommendationCandidateSource(
            CanonicalLibraryAvailabilityReader { listOf("recording-a", "recording-b", "recording-c") },
        )
        val ranker = PersonalRanker(
            behaviorReader = NeutralBehaviorReader,
            ratingReader = Ratings(mapOf("recording-a" to 5.0, "recording-b" to 5.0, "recording-c" to 5.0)),
            nowEpochMs = { now },
        )
        val contextReader = CountingContextReader(
            current = ListeningSession("session", now, now, null),
            events = listOf(
                terminal("a-skip", "recording-a", ListeningEventType.PLAYBACK_SKIPPED_EARLY, now, 5_000L),
                terminal("b-complete", "recording-b", ListeningEventType.PLAYBACK_COMPLETED, now, 180_000L),
            ),
        )
        val reranker = SessionContextReranker(contextReader, nowEpochMs = { now })
        val pipeline = LibraryRecommendationPipeline(
            source,
            ranker,
            reranker,
            ArtistAwareDiversityReranker(NoArtistGroupingReader),
        )

        val base = ranker.rank(source.candidates())
        val limited = pipeline.rank(limit = 1)

        assertEquals(listOf("recording-a", "recording-b", "recording-c"), base.map { it.recordingId })
        assertEquals(listOf("recording-c"), limited.map { it.base.base.recordingId })
        assertEquals(1, contextReader.currentReads)
        assertEquals(1, contextReader.eventReads)
        assertEquals(0.0, limited.single().diversityAdjustment, 0.000001)
    }

    @Test
    fun `pipeline rejects invalid limit before reading candidates session or diversity`() = runTest {
        var availabilityReads = 0
        var groupingReads = 0
        val source = LibraryRecommendationCandidateSource(
            CanonicalLibraryAvailabilityReader {
                availabilityReads += 1
                listOf("recording-a")
            },
        )
        val contextReader = CountingContextReader(current = null)
        val pipeline = LibraryRecommendationPipeline(
            source,
            PersonalRanker(NeutralBehaviorReader, Ratings(emptyMap()), nowEpochMs = { 1_000L }),
            SessionContextReranker(contextReader, nowEpochMs = { 1_000L }),
            ArtistAwareDiversityReranker(
                CanonicalRecordingArtistGroupingReader { ids ->
                    groupingReads += 1
                    ids.associateWith { emptyList() }
                },
            ),
        )

        val failure = runCatching { pipeline.rank(limit = 0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, availabilityReads)
        assertEquals(0, contextReader.currentReads)
        assertEquals(0, groupingReads)
    }

    private object NoArtistGroupingReader : CanonicalRecordingArtistGroupingReader {
        override suspend fun artistIdsByRecording(recordingIds: List<String>): Map<String, List<String>> =
            recordingIds.associateWith { emptyList() }
    }

    private object NeutralBehaviorReader : ListeningBehaviorReader {
        override suspend fun session(sessionId: String): ListeningSession? = null
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = emptyList()
        override suspend fun recentEventsForRecording(recordingId: String, limit: Int): List<ListeningEvent> = emptyList()
        override suspend fun behaviorSummary(recordingId: String) = RecordingBehaviorSummary(
            recordingId = recordingId,
            starts = 0L,
            completions = 0L,
            earlySkips = 0L,
            lateSkips = 0L,
            interruptions = 0L,
            totalListenedMs = 0L,
            lastEventAtEpochMs = null,
        )
    }

    private class Ratings(private val values: Map<String, Double>) : ExplicitRecordingRatingReader {
        override suspend fun currentRating(recordingId: String): RecordingRating? =
            values[recordingId]?.let { RecordingRating(recordingId, it, 0L) }
        override fun observeRating(recordingId: String): Flow<RecordingRating?> =
            flowOf(values[recordingId]?.let { RecordingRating(recordingId, it, 0L) })
        override suspend fun ratedRecordings(limit: Int): List<RecordingRating> =
            values.entries.sortedBy { it.key }.take(limit).map { RecordingRating(it.key, it.value, 0L) }
        override suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal {
            val value = values[recordingId]
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

    private class CountingContextReader(
        private val current: ListeningSession?,
        private val events: List<ListeningEvent> = emptyList(),
    ) : ListeningSessionContextReader {
        var currentReads = 0
            private set
        var eventReads = 0
            private set
        override suspend fun currentSession(atEpochMs: Long): ListeningSession? {
            currentReads += 1
            return current
        }
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> {
            eventReads += 1
            return events
        }
    }

    private fun terminal(
        id: String,
        recordingId: String,
        type: ListeningEventType,
        occurredAtEpochMs: Long,
        listenedDurationMs: Long,
    ) = ListeningEvent(
        id = id,
        sessionId = "session",
        recordingId = recordingId,
        mediaAssetId = null,
        assetProvider = null,
        occurredAtEpochMs = occurredAtEpochMs,
        type = type,
        listenedDurationMs = listenedDurationMs,
        recordingDurationMs = 180_000L,
        endCause = null,
        selectionIntent = null,
        selectionOrigin = null,
        recommendationOrigin = null,
    )
}

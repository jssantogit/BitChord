package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRecommendationDiversityPipelineTest {

    @Test
    fun `pipeline composes personal ranker then session context then diversity`() = runTest {
        val availability = CountingAvailabilityReader(listOf("a1", "a2", "b1"))
        val candidateSource = LibraryRecommendationCandidateSource(availability)
        val ratings = Ratings(mapOf("a1" to 5.0, "a2" to 4.9, "b1" to 4.8))
        val behavior = NeutralBehaviorReader()
        val personalRanker = PersonalRanker(behavior, ratings, nowEpochMs = { NOW })
        val sessionReader = CountingNoSessionReader()
        val sessionReranker = SessionContextReranker(sessionReader, nowEpochMs = { NOW })
        val grouping = CountingGroupingReader(
            mapOf("a1" to listOf("artist:a"), "a2" to listOf("artist:a"), "b1" to listOf("artist:b")),
        )
        val diversity = ArtistAwareDiversityReranker(grouping)
        val pipeline = LibraryRecommendationPipeline(candidateSource, personalRanker, sessionReranker, diversity)

        val result = pipeline.rank()

        assertEquals(listOf("a1", "b1", "a2"), result.map { it.base.base.recordingId })
        assertEquals(1, availability.reads)
        assertTrue(ratings.signalReads > 0)
        assertTrue(behavior.summaryReads > 0)
        assertEquals(1, sessionReader.currentReads)
        assertEquals(1, grouping.reads)
        assertEquals(listOf("a1", "a2", "b1"), grouping.lastRequestedIds)
    }

    @Test
    fun `limit is applied only after diversity so upstream outsider enters final top N`() = runTest {
        val candidateSource = LibraryRecommendationCandidateSource(
            CanonicalLibraryAvailabilityReader { listOf("a1", "a2", "b1") },
        )
        val personalRanker = PersonalRanker(
            NeutralBehaviorReader(),
            Ratings(mapOf("a1" to 5.0, "a2" to 4.9, "b1" to 4.8)),
            nowEpochMs = { NOW },
        )
        val sessionReranker = SessionContextReranker(NoSessionReader, nowEpochMs = { NOW })
        val diversity = ArtistAwareDiversityReranker(
            CanonicalRecordingArtistGroupingReader { ids ->
                ids.associateWith { id ->
                    when (id) {
                        "a1", "a2" -> listOf("artist:a")
                        else -> listOf("artist:b")
                    }
                }
            },
        )
        val pipeline = LibraryRecommendationPipeline(candidateSource, personalRanker, sessionReranker, diversity)

        val upstream = sessionReranker.rerank(personalRanker.rank(candidateSource.candidates()))
        val limited = pipeline.rank(limit = 2)

        assertEquals(listOf("a1", "a2"), upstream.take(2).map { it.base.recordingId })
        assertEquals(listOf("a1", "b1"), limited.map { it.base.base.recordingId })
    }

    @Test
    fun `candidate dedupe remains upstream and diversity sees each canonical recording once`() = runTest {
        val grouping = CountingGroupingReader(
            mapOf("a1" to listOf("artist:a"), "b1" to listOf("artist:b")),
        )
        val pipeline = LibraryRecommendationPipeline(
            candidateSource = LibraryRecommendationCandidateSource(
                CanonicalLibraryAvailabilityReader { listOf("b1", "a1", "a1", "b1") },
            ),
            personalRanker = PersonalRanker(NeutralBehaviorReader(), Ratings(emptyMap()), nowEpochMs = { NOW }),
            sessionContextReranker = SessionContextReranker(NoSessionReader, nowEpochMs = { NOW }),
            artistAwareDiversityReranker = ArtistAwareDiversityReranker(grouping),
        )

        val result = pipeline.rank()

        assertEquals(listOf("a1", "b1"), grouping.lastRequestedIds)
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.base.base.recordingId }.distinct().size)
    }

    @Test
    fun `invalid limit fails before candidate or diversity reads`() = runTest {
        val availability = CountingAvailabilityReader(listOf("a1"))
        val grouping = CountingGroupingReader(mapOf("a1" to listOf("artist:a")))
        val pipeline = LibraryRecommendationPipeline(
            LibraryRecommendationCandidateSource(availability),
            PersonalRanker(NeutralBehaviorReader(), Ratings(emptyMap()), nowEpochMs = { NOW }),
            SessionContextReranker(NoSessionReader, nowEpochMs = { NOW }),
            ArtistAwareDiversityReranker(grouping),
        )

        val failure = runCatching { pipeline.rank(limit = 0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, availability.reads)
        assertEquals(0, grouping.reads)
    }

    private class CountingAvailabilityReader(
        private val ids: List<String>,
    ) : CanonicalLibraryAvailabilityReader {
        var reads = 0
            private set
        override suspend fun availableRecordingIds(): List<String> {
            reads += 1
            return ids
        }
    }

    private class CountingGroupingReader(
        private val values: Map<String, List<String>>,
    ) : CanonicalRecordingArtistGroupingReader {
        var reads = 0
            private set
        var lastRequestedIds: List<String> = emptyList()
            private set
        override suspend fun artistIdsByRecording(recordingIds: List<String>): Map<String, List<String>> {
            reads += 1
            lastRequestedIds = recordingIds.toList()
            return recordingIds.associateWith { values[it].orEmpty() }
        }
    }

    private class CountingNoSessionReader : ListeningSessionContextReader {
        var currentReads = 0
            private set
        override suspend fun currentSession(atEpochMs: Long): ListeningSession? {
            currentReads += 1
            return null
        }
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = error("No session means no event read")
    }

    private object NoSessionReader : ListeningSessionContextReader {
        override suspend fun currentSession(atEpochMs: Long): ListeningSession? = null
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = emptyList()
    }

    private class NeutralBehaviorReader : ListeningBehaviorReader {
        var summaryReads = 0
            private set
        override suspend fun session(sessionId: String): ListeningSession? = null
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = emptyList()
        override suspend fun recentEventsForRecording(recordingId: String, limit: Int): List<ListeningEvent> = emptyList()
        override suspend fun behaviorSummary(recordingId: String): RecordingBehaviorSummary {
            summaryReads += 1
            return RecordingBehaviorSummary(
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
    }

    private class Ratings(
        private val values: Map<String, Double>,
    ) : ExplicitRecordingRatingReader {
        var signalReads = 0
            private set
        override suspend fun currentRating(recordingId: String): RecordingRating? =
            values[recordingId]?.let { RecordingRating(recordingId, it, 0L) }
        override fun observeRating(recordingId: String): Flow<RecordingRating?> =
            flowOf(values[recordingId]?.let { RecordingRating(recordingId, it, 0L) })
        override suspend fun ratedRecordings(limit: Int): List<RecordingRating> =
            values.entries.sortedBy { it.key }.take(limit).map { RecordingRating(it.key, it.value, 0L) }
        override suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal {
            signalReads += 1
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

    private companion object {
        const val NOW = 30L * 24L * 60L * 60L * 1000L
    }
}

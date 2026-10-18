package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRecommendationPipelineTest {

    @Test
    fun `candidate source maps only canonical recording ids with deterministic defensive deduplication`() = runTest {
        val source = LibraryRecommendationCandidateSource(
            availabilityReader = FakeAvailabilityReader(
                listOf("recording-z", "recording-a", "recording-z", "recording-m"),
            ),
        )

        assertEquals(
            listOf("recording-a", "recording-m", "recording-z"),
            source.candidates().map { it.recordingId },
        )
    }

    @Test
    fun `empty catalog availability produces no candidates`() = runTest {
        val source = LibraryRecommendationCandidateSource(FakeAvailabilityReader(emptyList()))

        assertTrue(source.candidates().isEmpty())
    }

    @Test
    fun `recommendation candidate public shape contains no provider-native identity`() {
        val fields = RecommendationCandidate::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()

        assertEquals(setOf("recordingId"), fields)
        assertFalse(fields.any { it in setOf("mediaAssetId", "providerKey", "sourceKey", "uri", "videoId") })
    }

    @Test
    fun `candidate source does not read ratings or listening behavior`() = runTest {
        val source = LibraryRecommendationCandidateSource(FakeAvailabilityReader(listOf("recording-a")))
        val behavior = ThrowingBehaviorReader()
        val ratings = ThrowingRatingReader()

        assertEquals(listOf("recording-a"), source.candidates().map { it.recordingId })
        assertEquals(0, behavior.readCount)
        assertEquals(0, ratings.readCount)
    }

    @Test
    fun `pipeline sends library candidates through existing PersonalRanker`() = runTest {
        val ratingReader = FakeRatingReader(mapOf("recording-favorite" to 5.0, "recording-neutral" to 3.0))
        val pipeline = pipeline(
            availableIds = listOf("recording-neutral", "recording-favorite"),
            ratingReader = ratingReader,
        )

        val ranked = pipeline.rank()

        assertEquals(listOf("recording-favorite", "recording-neutral"), ranked.map { it.base.recordingId })
        assertTrue(RecommendationReason.EXPLICIT_FAVORITE in ranked.first().base.reasons)
        assertTrue(ratingReader.explicitSignalReads > 0)
        assertTrue(ranked.all { it.sessionAdjustment == 0.0 && it.finalScore == it.base.score })
    }

    @Test
    fun `existing rating semantics alter final order without a second ranking algorithm`() = runTest {
        val ranked = pipeline(
            availableIds = listOf("recording-4", "recording-5", "recording-3"),
            ratingReader = FakeRatingReader(
                mapOf(
                    "recording-5" to 5.0,
                    "recording-4" to 4.0,
                    "recording-3" to 3.0,
                ),
            ),
        ).rank()

        assertEquals(listOf("recording-5", "recording-4", "recording-3"), ranked.map { it.base.recordingId })
    }

    @Test
    fun `final ties remain deterministic by recording id`() = runTest {
        val ranked = pipeline(
            availableIds = listOf("recording-z", "recording-a", "recording-m"),
            ratingReader = FakeRatingReader(emptyMap()),
        ).rank()

        assertEquals(listOf("recording-a", "recording-m", "recording-z"), ranked.map { it.base.recordingId })
        assertEquals(listOf(0.0, 0.0, 0.0), ranked.map { it.finalScore })
    }

    @Test
    fun `limit is applied after ranking so true top N is preserved without session context`() = runTest {
        val pipeline = pipeline(
            availableIds = listOf("recording-neutral", "recording-good", "recording-favorite"),
            ratingReader = FakeRatingReader(
                mapOf(
                    "recording-neutral" to 3.0,
                    "recording-good" to 4.0,
                    "recording-favorite" to 5.0,
                ),
            ),
        )

        val ranked = pipeline.rank(limit = 1)

        assertEquals(listOf("recording-favorite"), ranked.map { it.base.recordingId })
    }

    @Test
    fun `non-positive limit fails clearly`() = runTest {
        val pipeline = pipeline(
            availableIds = listOf("recording-a"),
            ratingReader = FakeRatingReader(emptyMap()),
        )

        val failure = runCatching { pipeline.rank(limit = 0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun pipeline(
        availableIds: List<String>,
        ratingReader: ExplicitRecordingRatingReader,
    ): LibraryRecommendationPipeline {
        val candidateSource = LibraryRecommendationCandidateSource(FakeAvailabilityReader(availableIds))
        val ranker = PersonalRanker(
            behaviorReader = NeutralBehaviorReader,
            ratingReader = ratingReader,
            nowEpochMs = { THIRTY_DAYS_MS },
        )
        val sessionReranker = SessionContextReranker(
            contextReader = NoSessionContextReader,
            nowEpochMs = { THIRTY_DAYS_MS },
        )
        return LibraryRecommendationPipeline(candidateSource, ranker, sessionReranker)
    }

    private class FakeAvailabilityReader(
        private val ids: List<String>,
    ) : CanonicalLibraryAvailabilityReader {
        override suspend fun availableRecordingIds(): List<String> = ids
    }

    private object NoSessionContextReader : ListeningSessionContextReader {
        override suspend fun currentSession(atEpochMs: Long): ListeningSession? = null
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = emptyList()
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
        var explicitSignalReads = 0
            private set

        override suspend fun currentRating(recordingId: String): RecordingRating? =
            ratings[recordingId]?.let { RecordingRating(recordingId, it, ratedAtEpochMs = 0L) }

        override fun observeRating(recordingId: String): Flow<RecordingRating?> =
            flowOf(ratings[recordingId]?.let { RecordingRating(recordingId, it, ratedAtEpochMs = 0L) })

        override suspend fun ratedRecordings(limit: Int): List<RecordingRating> =
            ratings.entries.sortedBy { it.key }.take(limit).map { (id, value) -> RecordingRating(id, value, 0L) }

        override suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal {
            explicitSignalReads += 1
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

    private class ThrowingBehaviorReader : ListeningBehaviorReader {
        var readCount = 0
            private set
        override suspend fun session(sessionId: String): ListeningSession? = fail()
        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = fail()
        override suspend fun recentEventsForRecording(recordingId: String, limit: Int): List<ListeningEvent> = fail()
        override suspend fun behaviorSummary(recordingId: String): RecordingBehaviorSummary = fail()
        private fun <T> fail(): T {
            readCount += 1
            error("candidate generation must not read behavior")
        }
    }

    private class ThrowingRatingReader : ExplicitRecordingRatingReader {
        var readCount = 0
            private set
        override suspend fun currentRating(recordingId: String): RecordingRating? = fail()
        override fun observeRating(recordingId: String): Flow<RecordingRating?> = fail()
        override suspend fun ratedRecordings(limit: Int): List<RecordingRating> = fail()
        override suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal = fail()
        private fun <T> fail(): T {
            readCount += 1
            error("candidate generation must not read ratings")
        }
    }

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PersonalRankerIntegrationTest {
    private lateinit var context: Context
    private var database: ListeningEventDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun realListeningAndRatingStoresDriveExpectedRanking() = runTest {
        database = Room.databaseBuilder(
            context,
            ListeningEventDatabase::class.java,
            TEST_DATABASE,
        ).allowMainThreadQueries().build()

        val clock = MutableClock(1_000L)
        val eventStore = RoomListeningEventStore(
            dao = requireNotNull(database).listeningEventDao(),
            clock = clock,
            idGenerator = SequenceIdGenerator(
                "session-1",
                "start-completed",
                "end-completed",
                "start-skipped",
                "end-skipped",
            ),
        )
        val ratingStore = RoomRecordingRatingStore(
            dao = requireNotNull(database).recordingRatingDao(),
            nowEpochMs = { clock.now },
        )

        ratingStore.setRecordingRating(COMPLETED_RECORDING, 4.0)
        ratingStore.setRecordingRating(SKIPPED_RECORDING, 4.0)

        eventStore.recordPlaybackStarted(
            PlaybackStart(
                recordingId = COMPLETED_RECORDING,
                selectionIntent = SelectionIntent.MANUAL,
                selectionOrigin = SelectionOrigin.LIBRARY,
            ),
        )
        clock.now = 2_000L
        eventStore.recordPlaybackEnded(
            PlaybackEnd(
                recordingId = COMPLETED_RECORDING,
                listenedDurationMs = 180_000L,
                recordingDurationMs = 180_000L,
                endCause = PlaybackEndCause.ENDED,
            ),
        )

        clock.now = 3_000L
        eventStore.recordPlaybackStarted(
            PlaybackStart(
                recordingId = SKIPPED_RECORDING,
                selectionIntent = SelectionIntent.AUTOMATIC,
                selectionOrigin = SelectionOrigin.QUEUE,
            ),
        )
        clock.now = 4_000L
        eventStore.recordPlaybackEnded(
            PlaybackEnd(
                recordingId = SKIPPED_RECORDING,
                listenedDurationMs = 10_000L,
                recordingDurationMs = 180_000L,
                endCause = PlaybackEndCause.NEXT,
            ),
        )

        clock.now = 8L * 24L * 60L * 60L * 1000L
        val ranker = PersonalRanker(
            behaviorReader = eventStore,
            ratingReader = ratingStore,
            nowEpochMs = { clock.now },
        )

        val ranked = ranker.rank(
            listOf(
                RecommendationCandidate(SKIPPED_RECORDING),
                RecommendationCandidate(COMPLETED_RECORDING),
            ),
        )

        assertEquals(
            listOf(COMPLETED_RECORDING, SKIPPED_RECORDING),
            ranked.map { it.recordingId },
        )
        assertTrue(ranked[0].components.behavioralEvidence > ranked[1].components.behavioralEvidence)
        assertTrue(RecommendationReason.COMPLETION_HEAVY in ranked[0].reasons)
        assertTrue(RecommendationReason.EARLY_SKIP_HEAVY in ranked[1].reasons)
    }

    private class MutableClock(var now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private class SequenceIdGenerator(vararg ids: String) : ListeningIdGenerator {
        private val values = ArrayDeque(ids.toList())

        override fun nextId(): String = values.removeFirst()
    }

    private companion object {
        const val TEST_DATABASE = "personal-ranker-integration-test.db"
        const val COMPLETED_RECORDING = "recording:integration:completed"
        const val SKIPPED_RECORDING = "recording:integration:skipped"
    }
}

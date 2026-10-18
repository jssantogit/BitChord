package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class ListeningSessionPolicyTest {
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
    fun consecutivePlaybackWithinThirtyMinutesReusesSessionWithoutPlaybackOwningSessionId() = runTest {
        database = openDatabase()
        val clock = MutableListeningClock(1_000L)
        val store = store(clock, "session-1", "start-1", "end-1", "start-2")

        val firstStart = store.recordPlaybackStarted(start("recording:a"))
        clock.now = 60_000L
        store.recordPlaybackEnded(end("recording:a", PlaybackEndCause.NEXT))
        clock.now = THIRTY_MINUTES_MS
        val secondStart = store.recordPlaybackStarted(start("recording:b"))

        assertEquals(firstStart.sessionId, secondStart.sessionId)
        assertEquals(3, store.eventsForSession(firstStart.sessionId).size)
    }

    @Test
    fun processRecreationInsideWindowDoesNotCreateListeningSession() = runTest {
        database = openDatabase()
        val clock = MutableListeningClock(1_000L)
        val firstStore = store(clock, "session-1", "start-1")
        val firstStart = firstStore.recordPlaybackStarted(start("recording:a"))

        clock.now = 1_000L + THIRTY_MINUTES_MS - 1L
        val recreatedStore = store(clock, "start-2")
        val secondStart = recreatedStore.recordPlaybackStarted(start("recording:b"))

        assertEquals(firstStart.sessionId, secondStart.sessionId)
        assertEquals(null, requireNotNull(recreatedStore.session(firstStart.sessionId)).endedAtEpochMs)
    }

    @Test
    fun exactlyThirtyMinutesOfInactivityRollsToNewSession() = runTest {
        database = openDatabase()
        val clock = MutableListeningClock(1_000L)
        val firstStore = store(clock, "session-1", "start-1")
        val firstStart = firstStore.recordPlaybackStarted(start("recording:a"))

        clock.now = 1_000L + THIRTY_MINUTES_MS
        val recreatedStore = store(clock, "session-2", "start-2")
        val secondStart = recreatedStore.recordPlaybackStarted(start("recording:b"))

        assertNotEquals(firstStart.sessionId, secondStart.sessionId)
        assertEquals(1_000L, requireNotNull(recreatedStore.session(firstStart.sessionId)).endedAtEpochMs)
        assertEquals(clock.now, requireNotNull(recreatedStore.session(secondStart.sessionId)).startedAtEpochMs)
    }

    @Test
    fun activityAfterMoreThanThirtyMinutesRollsToNewSessionAndSurvivesStoreRecreation() = runTest {
        database = openDatabase()
        val clock = MutableListeningClock(1_000L)
        val first = store(clock, "session-1", "start-1")
        val firstStart = first.recordPlaybackStarted(start("recording:a"))

        clock.now = 1_000L + THIRTY_MINUTES_MS + 1L
        val recreated = store(clock, "session-2", "start-2")
        val secondStart = recreated.recordPlaybackStarted(start("recording:b"))

        assertNotEquals(firstStart.sessionId, secondStart.sessionId)
        assertEquals(1_000L, requireNotNull(recreated.session(firstStart.sessionId)).endedAtEpochMs)
        assertEquals(clock.now, requireNotNull(recreated.session(secondStart.sessionId)).startedAtEpochMs)
    }

    @Test
    fun continuousAudioActivityKeepsLongPlaybackInOneSessionButResumeAtTimeoutRolls() = runTest {
        database = openDatabase()
        val clock = MutableListeningClock(1_000L)
        val store = store(clock, "session-1", "start-1", "session-2", "rollover-start")
        val firstStart = store.recordPlaybackStarted(start("recording:a"))

        clock.now = 20L * 60L * 1000L
        store.recordPlaybackActivity(activity("recording:a"))
        clock.now = 40L * 60L * 1000L
        store.recordPlaybackActivity(activity("recording:a"))
        assertEquals(1, store.eventsForSession(firstStart.sessionId).size)
        assertEquals(clock.now, requireNotNull(store.session(firstStart.sessionId)).lastActivityAtEpochMs)

        clock.now += THIRTY_MINUTES_MS
        store.recordPlaybackActivity(activity("recording:a"))
        val starts = store.recentEventsForRecording("recording:a", 10)
            .filter { it.type == ListeningEventType.PLAYBACK_STARTED }
        assertEquals(2, starts.size)
        assertNotEquals(starts[0].sessionId, starts[1].sessionId)
        assertEquals(40L * 60L * 1000L, requireNotNull(store.session(firstStart.sessionId)).endedAtEpochMs)
    }

    @Test
    fun queueChangeDoesNotCreateSessionAndExplicitStopClosesIt() = runTest {
        database = openDatabase()
        val clock = MutableListeningClock(1_000L)
        val store = store(clock, "session-1", "start-1", "end-1", "start-2", "end-2", "session-2", "start-3")

        val firstStart = store.recordPlaybackStarted(start("recording:a"))
        clock.now = 20_000L
        store.recordPlaybackEnded(end("recording:a", PlaybackEndCause.NEXT))
        clock.now = 20_100L
        val queueStart = store.recordPlaybackStarted(start("recording:b", SelectionOrigin.QUEUE))
        assertEquals(firstStart.sessionId, queueStart.sessionId)

        clock.now = 40_000L
        store.recordPlaybackEnded(end("recording:b", PlaybackEndCause.STOPPED))
        assertEquals(40_000L, requireNotNull(store.session(firstStart.sessionId)).endedAtEpochMs)

        clock.now = 40_100L
        val afterStop = store.recordPlaybackStarted(start("recording:c"))
        assertNotEquals(firstStart.sessionId, afterStop.sessionId)
    }

    private fun start(
        recordingId: String,
        origin: SelectionOrigin = SelectionOrigin.LIBRARY,
    ) = PlaybackStart(
        recordingId = recordingId,
        mediaAssetId = "asset:$recordingId",
        assetProvider = "local-mediastore",
        selectionIntent = SelectionIntent.MANUAL,
        selectionOrigin = origin,
    )

    private fun activity(recordingId: String) = PlaybackActivity(
        recordingId = recordingId,
        mediaAssetId = "asset:$recordingId",
        assetProvider = "local-mediastore",
    )

    private fun end(recordingId: String, cause: PlaybackEndCause) = PlaybackEnd(
        recordingId = recordingId,
        mediaAssetId = "asset:$recordingId",
        assetProvider = "local-mediastore",
        listenedDurationMs = 20_000L,
        recordingDurationMs = 200_000L,
        endCause = cause,
    )

    private fun store(clock: ListeningClock, vararg ids: String): RoomListeningEventStore =
        RoomListeningEventStore(
            dao = requireNotNull(database).listeningEventDao(),
            clock = clock,
            idGenerator = SequenceListeningIdGenerator(*ids),
        )

    private fun openDatabase(): ListeningEventDatabase =
        Room.databaseBuilder(context, ListeningEventDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()

    private class MutableListeningClock(var now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private class SequenceListeningIdGenerator(vararg ids: String) : ListeningIdGenerator {
        private val values = ArrayDeque(ids.toList())
        override fun nextId(): String = values.removeFirst()
    }

    private companion object {
        const val TEST_DATABASE = "listening-session-policy-test.db"
        const val THIRTY_MINUTES_MS = 30L * 60L * 1000L
    }
}

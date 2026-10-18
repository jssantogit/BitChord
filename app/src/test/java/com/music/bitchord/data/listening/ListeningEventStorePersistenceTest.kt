package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class ListeningEventStorePersistenceTest {
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
    fun persistsCanonicalBehaviorAcrossDatabaseReopen() = runTest {
        database = openDatabase()
        val clock = MutableListeningClock(1_000L)
        val writer = RoomListeningEventStore(
            dao = requireNotNull(database).listeningEventDao(),
            clock = clock,
            idGenerator = SequenceListeningIdGenerator("session-1", "event-start", "event-end"),
        )

        val started = writer.recordPlaybackStarted(
            PlaybackStart(
                recordingId = CANONICAL_RECORDING_ID,
                mediaAssetId = "asset:local:7",
                assetProvider = "local-mediastore",
                selectionIntent = SelectionIntent.MANUAL,
                selectionOrigin = SelectionOrigin.LIBRARY,
            ),
        )
        clock.now = 3_000L
        writer.recordPlaybackEnded(
            PlaybackEnd(
                recordingId = CANONICAL_RECORDING_ID,
                mediaAssetId = "asset:local:7",
                assetProvider = "local-mediastore",
                listenedDurationMs = 20_000L,
                recordingDurationMs = 200_000L,
                endCause = PlaybackEndCause.NEXT,
            ),
        )

        database?.close()
        database = openDatabase()
        val reader = RoomListeningEventStore(requireNotNull(database).listeningEventDao())

        val persistedSession = requireNotNull(reader.session(started.sessionId))
        assertEquals(1_000L, persistedSession.startedAtEpochMs)
        assertNull(persistedSession.endedAtEpochMs)

        val events = reader.eventsForSession(started.sessionId)
        assertEquals(2, events.size)

        val persistedStart = events[0]
        assertEquals(ListeningEventType.PLAYBACK_STARTED, persistedStart.type)
        assertEquals(CANONICAL_RECORDING_ID, persistedStart.recordingId)
        assertEquals("asset:local:7", persistedStart.mediaAssetId)
        assertEquals("local-mediastore", persistedStart.assetProvider)
        assertEquals(SelectionIntent.MANUAL, persistedStart.selectionIntent)
        assertEquals(SelectionOrigin.LIBRARY, persistedStart.selectionOrigin)
        assertNull(persistedStart.endCause)

        val ended = events[1]
        assertEquals(ListeningEventType.PLAYBACK_SKIPPED_EARLY, ended.type)
        assertEquals(PlaybackEndCause.NEXT, ended.endCause)
        assertEquals(20_000L, ended.listenedDurationMs)
        assertEquals(0.10, requireNotNull(ended.listenedRatio), 0.0001)

        val summary = reader.behaviorSummary(CANONICAL_RECORDING_ID)
        assertEquals(1L, summary.starts)
        assertEquals(0L, summary.completions)
        assertEquals(1L, summary.earlySkips)
        assertEquals(0L, summary.lateSkips)
        assertEquals(0L, summary.interruptions)
        assertEquals(20_000L, summary.totalListenedMs)
        assertEquals(3_000L, summary.lastEventAtEpochMs)

        val recent = reader.recentEventsForRecording(CANONICAL_RECORDING_ID, limit = 10)
        assertEquals(
            listOf(
                ListeningEventType.PLAYBACK_SKIPPED_EARLY,
                ListeningEventType.PLAYBACK_STARTED,
            ),
            recent.map { it.type },
        )
    }

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
        const val TEST_DATABASE = "listening-event-store-test.db"
        const val CANONICAL_RECORDING_ID = "recording:canonical:42"
    }
}

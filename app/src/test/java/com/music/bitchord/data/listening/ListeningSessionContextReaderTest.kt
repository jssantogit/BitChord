package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
class ListeningSessionContextReaderTest {
    private lateinit var context: Context
    private var database: ListeningEventDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun `no active session row means no current session`() = runTest {
        val store = store(now = 1_000L)

        assertNull(store.currentSession(atEpochMs = 1_000L))
    }

    @Test
    fun `active session inside inactivity boundary is current and read does not mutate it`() = runTest {
        val store = store(now = 1_000L, ids = arrayOf("session-1", "event-1"))
        val start = store.recordPlaybackStarted(PlaybackStart("recording:a"))
        val before = requireNotNull(store.session(start.sessionId))

        val current = store.currentSession(atEpochMs = 1_000L + THIRTY_MINUTES_MS - 1L)
        val after = requireNotNull(store.session(start.sessionId))

        assertEquals(before, current)
        assertEquals(before, after)
        assertNull(after.endedAtEpochMs)
    }

    @Test
    fun `exactly thirty minutes of inactivity is stale without closing persisted session`() = runTest {
        val store = store(now = 1_000L, ids = arrayOf("session-1", "event-1"))
        val start = store.recordPlaybackStarted(PlaybackStart("recording:a"))

        assertNull(store.currentSession(atEpochMs = 1_000L + THIRTY_MINUTES_MS))
        val persisted = requireNotNull(store.session(start.sessionId))
        assertNull(persisted.endedAtEpochMs)
        assertEquals(1_000L, persisted.lastActivityAtEpochMs)
    }

    @Test
    fun `more than thirty minutes of inactivity is stale`() = runTest {
        val store = store(now = 1_000L, ids = arrayOf("session-1", "event-1"))
        store.recordPlaybackStarted(PlaybackStart("recording:a"))

        assertNull(store.currentSession(atEpochMs = 1_000L + THIRTY_MINUTES_MS + 1L))
    }

    @Test
    fun `negative clock delta does not fabricate current session`() = runTest {
        val store = store(now = 10_000L, ids = arrayOf("session-1", "event-1"))
        store.recordPlaybackStarted(PlaybackStart("recording:a"))

        assertNull(store.currentSession(atEpochMs = 9_999L))
    }

    @Test
    fun `session context read introduces no Room migration`() {
        database = openDatabase()

        assertEquals(3, requireNotNull(database).openHelper.readableDatabase.version)
    }

    private fun store(now: Long, ids: Array<String> = emptyArray()): RoomListeningEventStore {
        database = openDatabase()
        return RoomListeningEventStore(
            dao = requireNotNull(database).listeningEventDao(),
            clock = ListeningClock { now },
            idGenerator = SequenceIdGenerator(ids.toList()),
        )
    }

    private fun openDatabase(): ListeningEventDatabase =
        Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private class SequenceIdGenerator(ids: List<String>) : ListeningIdGenerator {
        private val values = ArrayDeque(ids)
        override fun nextId(): String = values.removeFirst()
    }

    private companion object {
        const val THIRTY_MINUTES_MS = 30L * 60L * 1000L
    }
}

package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRatedLibraryFlowTest {
    @Test
    fun currentRatingLibraryUpdatesAfterSetAndClearWithoutHistoryLoss() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            var now = 100L
            val store = RoomRecordingRatingStore(db.recordingRatingDao()) { now }
            val initial = store.observeRatedRecordings(limit = 2).first()
            assertTrue(initial.items.isEmpty())
            assertFalse(initial.hasMore)

            val added = async(start = CoroutineStart.UNDISPATCHED) {
                store.observeRatedRecordings(limit = 2).first { it.items.size == 2 && it.hasMore }
            }
            store.setRecordingRating("recording:b", 4.0)
            now = 200L
            store.setRecordingRating("recording:a", 5.0)
            now = 300L
            store.setRecordingRating("recording:c", 2.0)

            val snapshot = added.await()
            assertEquals(listOf("recording:c", "recording:a"), snapshot.items.map { it.recordingId })
            assertTrue(snapshot.hasMore)

            val cleared = async(start = CoroutineStart.UNDISPATCHED) {
                store.observeRatedRecordings(limit = 2).first {
                    it.items.map { rating -> rating.recordingId } == listOf("recording:a", "recording:b")
                        && !it.hasMore
                }
            }
            store.clearRecordingRating("recording:c")
            val after = cleared.await()
            assertEquals(listOf("recording:a", "recording:b"), after.items.map { it.recordingId })
            assertEquals(listOf(2.0, null), store.ratingHistory("recording:c").map { it.newValue })
        } finally {
            db.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedOrEmptyLimit() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            RoomRecordingRatingStore(db.recordingRatingDao()).observeRatedRecordings(0)
        } finally {
            db.close()
        }
    }
}

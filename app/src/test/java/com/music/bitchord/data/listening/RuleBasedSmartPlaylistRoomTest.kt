package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.RecordingEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class RuleBasedSmartPlaylistRoomTest {
    @Test fun realRoomTerminalCompletionDrivesPreviewAndPreviewDoesNotWriteEvents() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:a", "A", "Artist", 180_000L))
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:b", "B", "Artist", 180_000L))
            val availability = object : CanonicalLibraryAvailabilityReader {
                override suspend fun availableRecordingIds() = listOf("rec:b", "rec:a", "rec:a")
            }
            var next = 0
            val playback = RoomListeningEventStore(
                listening.listeningEventDao(),
                clock = ListeningClock { 86_400_000L },
                idGenerator = ListeningIdGenerator { "id-${++next}" },
            )
            val ratingStore = RoomRecordingRatingStore(
                listening.recordingRatingDao(), nowEpochMs = { 100_000_000L },
            )
            ratingStore.setRecordingRating("rec:a", 5.0)
            ratingStore.setRecordingRating("rec:b", 2.0)
            val start = playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:a"))
            playback.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:a", listenedDurationMs = 180_000L,
                recordingDurationMs = 180_000L, endCause = PlaybackEndCause.ENDED,
            ))
            playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:b"))
            val before = playback.eventsForSession(start.sessionId)
            val ratingBefore = ratingStore.ratingHistory("rec:a")
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability, catalog.catalogDao(), ratingStore, playback,
                RuleBasedSmartPlaylistPlanner(nowEpochMs = { 40L * 86_400_000L }),
            )
            val items = preview.preview(RuleBasedSmartPlaylistQuery(
                minimumCompletions = 1, minimumDaysSinceAudible = 30, limit = 5,
            ))
            assertEquals(listOf("rec:a"), items.map { it.recordingId })
            assertTrue(items.single().reasons.contains(RuleBasedInclusionReason.AUDIBLE_ABSENCE))
            assertEquals(before, playback.eventsForSession(start.sessionId))
            assertEquals(ratingBefore, ratingStore.ratingHistory("rec:a"))
        } finally {
            catalog.close()
            listening.close()
        }
    }
}

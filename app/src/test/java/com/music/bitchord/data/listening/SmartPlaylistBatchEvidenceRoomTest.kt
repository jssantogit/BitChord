package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.RecordingEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class SmartPlaylistBatchEvidenceRoomTest {
    @Test fun realRoomBatchPreservesAudibleCompletionAndOneStarRating() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:low", "Low", "Artist", 250_000L))
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:unrated", "Unrated", "Artist", null))
            val clock = ListeningClock { 1_000_000L }
            var sequence = 0
            val store = RoomListeningEventStore(
                listening.listeningEventDao(), clock = clock,
                idGenerator = ListeningIdGenerator { "event-" + (++sequence) },
            )
            val ratings = RoomRecordingRatingStore(listening.recordingRatingDao())
            ratings.setRecordingRating("rec:low", 1.0)
            store.recordPlaybackStarted(PlaybackStart(recordingId = "rec:low"))
            store.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:low", listenedDurationMs = 250_000L,
                recordingDurationMs = 250_000L, endCause = PlaybackEndCause.ENDED,
            ))
            store.recordPlaybackStarted(PlaybackStart(recordingId = "rec:unrated"))
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                catalog.catalogDao(), listening.listeningEventDao(), listening.recordingRatingDao(),
            )
            val input = reader.read(listOf("rec:unrated", "rec:low", "rec:missing"))
            assertEquals(2, input.size)
            val low = input.single { it.recordingId == "rec:low" }
            assertEquals(1.0, low.explicitRating)
            assertEquals(1L, low.audibleCompletions)
            assertEquals(1_000_000L, low.lastAudibleAtEpochMs)
            assertEquals(250_000L, low.durationMs)
            val unrated = input.single { it.recordingId == "rec:unrated" }
            assertNull(unrated.explicitRating)
            assertEquals(0L, unrated.audibleCompletions)
            assertNull(unrated.lastAudibleAtEpochMs)
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun productionLikeBatchFeedsBothV1AndV2BeyondFiveThousandIds() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val ids = (0 until 5_001).map { "rec:" + it.toString().padStart(5, '0') }
            var batchCalls = 0
            val reader = SmartPlaylistBatchEvidenceReader { incoming ->
                batchCalls++
                assertTrue(incoming.size <= 400)
                incoming.map { id ->
                    RuleBasedRecordingCandidate(id, 1.0, 1L, 100L, 240_000L, true)
                }
            }
            val available = object : CanonicalLibraryAvailabilityReader {
                override suspend fun availableRecordingIds(): List<String> = ids
            }
            val preview = CanonicalRuleBasedSmartPlaylistPreview(
                availability = available,
                catalog = catalog.catalogDao(),
                ratings = RoomRecordingRatingStore(listening.recordingRatingDao()),
                listening = RoomListeningEventStore(listening.listeningEventDao()),
                planner = RuleBasedSmartPlaylistPlanner(nowEpochMs = { 500L }),
                batchReader = reader,
                v2Planner = SmartPlaylistV2RuleEngine(nowEpochMs = { 500L }),
            )
            val legacy = preview.preview(RuleBasedSmartPlaylistQuery(
                minimumDurationMs = 200_000L, limit = 100,
            ))
            assertEquals(100, legacy.size)
            assertEquals(13, batchCalls)
            val modern = preview.preview(SmartPlaylistV2Query(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0,
                ), limit = 200,
            ))
            assertEquals(200, modern.size)
            assertEquals(26, batchCalls)
        } finally {
            catalog.close()
            listening.close()
        }
    }
    @Test fun catalogBatchReadsActualRecordingTextWithoutInventingForMissingId() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(
                RecordingEntity("rec:one", "Super Shy", "NewJeans", 150_000L),
            )
            val batch = RoomSmartPlaylistBatchEvidenceReader(
                catalog.catalogDao(), listening.listeningEventDao(), listening.recordingRatingDao(),
            ).read(listOf("rec:missing", "rec:one"))
            assertEquals(1, batch.size)
            assertEquals("Super Shy", batch.single().title)
            assertEquals("NewJeans", batch.single().artistDisplay)
            val filter = SmartPlaylistV2Query(
                SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.ARTIST_DISPLAY,
                    SmartPlaylistRuleOperator.CONTAINS,
                    textValue = "newjeans",
                ),
            )
            assertEquals(listOf("rec:one"), SmartPlaylistV2RuleEngine().preview(
                batch, filter,
            ).map { it.recordingId })
        } finally {
            catalog.close()
            listening.close()
        }
    }

    @Test fun realRoomDistinguishesEarlySkipFromLateSkipAndInterruptions() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            catalog.catalogDao().upsertRecording(RecordingEntity("rec:skip", "Skip", "Artist", 200_000L))
            var index = 0
            val playback = RoomListeningEventStore(
                listening.listeningEventDao(),
                clock = ListeningClock { 1_000_000L },
                idGenerator = ListeningIdGenerator { "event-" + (++index) },
            )
            playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:skip"))
            playback.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:skip", listenedDurationMs = 10_000L,
                recordingDurationMs = 200_000L, endCause = PlaybackEndCause.NEXT,
            ))
            playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:skip"))
            playback.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:skip", listenedDurationMs = 100_000L,
                recordingDurationMs = 200_000L, endCause = PlaybackEndCause.MANUAL_CHANGE,
            ))
            playback.recordPlaybackStarted(PlaybackStart(recordingId = "rec:skip"))
            playback.recordPlaybackEnded(PlaybackEnd(
                recordingId = "rec:skip", listenedDurationMs = 10_000L,
                recordingDurationMs = 200_000L, endCause = PlaybackEndCause.ERROR,
            ))
            val row = RoomSmartPlaylistBatchEvidenceReader(
                catalog.catalogDao(), listening.listeningEventDao(), listening.recordingRatingDao(),
            ).read(listOf("rec:skip")).single()
            assertEquals(1L, row.earlySkips)
            assertEquals(1L, row.lateSkips)
            assertEquals(0L, row.audibleCompletions)
        } finally {
            catalog.close()
            listening.close()
        }
    }

}

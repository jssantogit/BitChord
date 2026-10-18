package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingRepository
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.RoomCanonicalLibraryAvailabilityStore
import com.music.bitchord.data.catalog.RoomCanonicalRecordingArtistGroupingStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class ControlledExplorationRecommendationIntegrationTest {
    private lateinit var context: Context
    private var catalogDatabase: CanonicalCatalogDatabase? = null
    private var listeningDatabase: ListeningEventDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        catalogDatabase?.close()
        listeningDatabase?.close()
    }

    @Test
    fun `real Room Library pipeline promotes only available underexposed recording without any writes`() = runTest {
        catalogDatabase = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listeningDatabase = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val catalogDao = requireNotNull(catalogDatabase).catalogDao()
        val recordings = listOf("a-top", "b-top", "c-top", "d-top", "e-target", "f-familiar", "z-novel")
        (recordings + "x-unavailable").forEach { id ->
            catalogDao.upsertRecording(
                RecordingEntity(id = id, title = id, artistDisplay = "Various", durationMs = 180_000L),
            )
        }
        recordings.forEach { id ->
            catalogDao.upsertMediaAsset(
                MediaAssetEntity(
                    id = "asset:" + id,
                    recordingId = id,
                    provider = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                    sourceKey = "content://media/external/audio/" + id,
                    uri = "content://media/external/audio/" + id,
                    path = null,
                    dateAddedSeconds = null,
                    dateModifiedSeconds = null,
                    artworkUri = null,
                ),
            )
        }

        val clock = MutableClock(1_000L)
        val ids = GuardedIds()
        val listeningStore = RoomListeningEventStore(
            dao = requireNotNull(listeningDatabase).listeningEventDao(),
            clock = clock,
            idGenerator = ids,
        )
        val ratings = RoomRecordingRatingStore(
            dao = requireNotNull(listeningDatabase).recordingRatingDao(),
            nowEpochMs = { clock.now },
        )
        mapOf(
            "a-top" to 5.0,
            "b-top" to 4.5,
            "c-top" to 4.0,
            "d-top" to 3.5,
            "e-target" to 3.0,
            "f-familiar" to 3.0,
            "x-unavailable" to 5.0,
        ).forEach { (id, rating) -> ratings.setRecordingRating(id, rating) }

        for (id in listOf("e-target", "f-familiar")) {
            listeningStore.recordPlaybackStarted(
                PlaybackStart(
                    recordingId = id,
                    selectionIntent = SelectionIntent.MANUAL,
                    selectionOrigin = SelectionOrigin.LIBRARY,
                ),
            )
            clock.now += 1_000L
            listeningStore.recordPlaybackEnded(
                PlaybackEnd(
                    recordingId = id,
                    listenedDurationMs = 180_000L,
                    recordingDurationMs = 180_000L,
                    endCause = PlaybackEndCause.ENDED,
                ),
            )
            clock.now += 1_000L
        }
        clock.now = 30L * 24L * 60L * 60L * 1_000L
        ids.allowWrites = false

        val availability = CanonicalLibraryAvailabilityRepository(
            RoomCanonicalLibraryAvailabilityStore(catalogDao),
            CanonicalLibraryProviderConfigurationReader { setOf(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER) },
        )
        val source = LibraryRecommendationCandidateSource(availability)
        val personal = PersonalRanker(listeningStore, ratings, nowEpochMs = { clock.now })
        val session = SessionContextReranker(listeningStore, nowEpochMs = { clock.now })
        val diversity = ArtistAwareDiversityReranker(
            CanonicalRecordingArtistGroupingRepository(
                RoomCanonicalRecordingArtistGroupingStore(catalogDao),
            ),
        )
        val pipeline = LibraryRecommendationPipeline(source, personal, session, diversity)

        val availableBefore = source.candidates()
        val personalBefore = personal.rank(availableBefore)
        val sessionBefore = session.rerank(personalBefore)
        val diversityBefore = diversity.rerank(sessionBefore)
        val ratingBefore = recordings.associateWith { ratings.currentRating(it) }
        val historyBefore = recordings.associateWith { ratings.ratingHistory(it) }
        val behaviorBefore = recordings.associateWith { listeningStore.behaviorSummary(it) }

        assertEquals(
            listOf("a-top", "b-top", "c-top", "d-top", "e-target", "f-familiar", "z-novel"),
            diversityBefore.map { it.base.base.recordingId },
        )
        val explored = pipeline.rank()
        val limited = pipeline.rank(limit = 5)

        assertEquals(listOf("a-top", "b-top", "c-top", "d-top", "z-novel"),
            explored.take(5).map { it.base.base.recordingId })
        assertEquals("z-novel", limited.last().base.base.recordingId)
        assertEquals(7, explored[4].promotedFromRank)
        assertEquals(5, explored[4].promotedToRank)
        assertEquals(setOf(ExplorationReason.UNDEREXPOSED_PROMOTION), explored[4].explorationReasons)
        assertEquals(1, explored.count { it.explorationReasons.isNotEmpty() })
        assertEquals(diversityBefore.toSet(), explored.map { it.diversity }.toSet())
        assertEquals(personalBefore.associateBy { it.recordingId },
            explored.associate { it.base.base.recordingId to it.base.base })
        assertEquals(sessionBefore.associateBy { it.base.recordingId },
            explored.associate { it.base.base.recordingId to it.base })
        assertTrue(explored.all { it.finalScore == it.diversity.finalScore })
        assertFalse(explored.any { it.base.base.recordingId == "x-unavailable" })
        assertEquals(availableBefore, source.candidates())
        assertEquals(ratingBefore, recordings.associateWith { ratings.currentRating(it) })
        assertEquals(historyBefore, recordings.associateWith { ratings.ratingHistory(it) })
        assertEquals(behaviorBefore, recordings.associateWith { listeningStore.behaviorSummary(it) })
        assertNull(listeningStore.currentSession(clock.now))
    }

    private class MutableClock(var now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private class GuardedIds : ListeningIdGenerator {
        private var sequence: Int = 0
        var allowWrites = true
        override fun nextId(): String {
            check(allowWrites) { "Recommendations must never write Listening events" }
            sequence += 1
            return "exploration-fixture-id-" + sequence
        }
    }
}

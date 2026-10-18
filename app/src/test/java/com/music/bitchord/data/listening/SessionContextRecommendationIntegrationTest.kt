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
class SessionContextRecommendationIntegrationTest {
    private lateinit var context: Context
    private var catalogDatabase: CanonicalCatalogDatabase? = null
    private var listeningDatabase: ListeningEventDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(LISTENING_DATABASE)
    }

    @After
    fun tearDown() {
        catalogDatabase?.close()
        listeningDatabase?.close()
        context.deleteDatabase(LISTENING_DATABASE)
    }

    @Test
    fun `real Room library pipeline derives ephemeral session context without persisting preference or scores`() = runTest {
        catalogDatabase = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listeningDatabase = openListeningDatabase()

        val catalogDao = requireNotNull(catalogDatabase).catalogDao()
        listOf(RECORDING_A, RECORDING_B, RECORDING_C).forEach { recordingId ->
            catalogDao.upsertRecording(
                RecordingEntity(
                    id = recordingId,
                    title = recordingId,
                    artistDisplay = "Fixture Artist",
                    durationMs = 180_000L,
                ),
            )
            catalogDao.upsertMediaAsset(localAsset(recordingId))
        }

        val clock = MutableClock(10_000_000L)
        var eventStore = eventStore(
            clock,
            "session-1",
            "start-a",
            "end-a",
            "start-b",
            "end-b",
        )
        var ratingStore = ratingStore(clock)
        ratingStore.setRecordingRating(RECORDING_A, 5.0)
        ratingStore.setRecordingRating(RECORDING_B, 5.0)
        ratingStore.setRecordingRating(RECORDING_C, 4.5)

        eventStore.recordPlaybackStarted(PlaybackStart(RECORDING_A))
        clock.now += 1_000L
        eventStore.recordPlaybackEnded(
            PlaybackEnd(
                recordingId = RECORDING_A,
                listenedDurationMs = 5_000L,
                recordingDurationMs = 180_000L,
                endCause = PlaybackEndCause.NEXT,
            ),
        )
        clock.now += 1_000L
        eventStore.recordPlaybackStarted(PlaybackStart(RECORDING_B))
        clock.now += 180_000L
        eventStore.recordPlaybackEnded(
            PlaybackEnd(
                recordingId = RECORDING_B,
                listenedDurationMs = 180_000L,
                recordingDurationMs = 180_000L,
                endCause = PlaybackEndCause.ENDED,
            ),
        )

        val availability = CanonicalLibraryAvailabilityRepository(
            store = RoomCanonicalLibraryAvailabilityStore(catalogDao),
            providerConfiguration = CanonicalLibraryProviderConfigurationReader {
                setOf(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
            },
        )
        val candidateSource = LibraryRecommendationCandidateSource(availability)
        val groupingReader = CanonicalRecordingArtistGroupingRepository(
            RoomCanonicalRecordingArtistGroupingStore(catalogDao),
        )
        var personalRanker = PersonalRanker(eventStore, ratingStore, nowEpochMs = { clock.now })
        var sessionReranker = SessionContextReranker(eventStore, nowEpochMs = { clock.now })
        var pipeline = LibraryRecommendationPipeline(
            candidateSource,
            personalRanker,
            sessionReranker,
            ArtistAwareDiversityReranker(groupingReader),
        )

        val candidates = candidateSource.candidates()
        val baseBefore = personalRanker.rank(candidates)
        val ratingsBefore = listOf(RECORDING_A, RECORDING_B, RECORDING_C).associateWith {
            ratingStore.currentRating(it)
        }
        val behaviorBefore = listOf(RECORDING_A, RECORDING_B, RECORDING_C).associateWith {
            eventStore.behaviorSummary(it)
        }
        val assetsBefore = listOf(RECORDING_A, RECORDING_B, RECORDING_C).associateWith {
            catalogDao.mediaAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, sourceKey(it))
        }

        val contextual = pipeline.rank()
        val limited = pipeline.rank(limit = 1)

        assertEquals(
            baseBefore.associateBy { it.recordingId },
            contextual.associate { it.base.base.recordingId to it.base.base },
        )
        assertEquals(RECORDING_C, contextual.first().base.base.recordingId)
        assertEquals(listOf(RECORDING_C), limited.map { it.base.base.recordingId })
        assertTrue(contextual.first { it.base.base.recordingId == RECORDING_A }.base.sessionAdjustment < 0.0)
        assertEquals(
            setOf(SessionContextReason.SESSION_EARLY_SKIP),
            contextual.first { it.base.base.recordingId == RECORDING_A }.base.sessionReasons,
        )
        assertEquals(
            setOf(SessionContextReason.SESSION_RECENTLY_COMPLETED),
            contextual.first { it.base.base.recordingId == RECORDING_B }.base.sessionReasons,
        )
        assertEquals(
            0.0,
            contextual.first { it.base.base.recordingId == RECORDING_C }.base.sessionAdjustment,
            EPSILON,
        )
        assertTrue(contextual.all { it.diversityAdjustment == 0.0 && it.finalScore == it.base.finalScore })
        assertEquals(ratingsBefore, listOf(RECORDING_A, RECORDING_B, RECORDING_C).associateWith { ratingStore.currentRating(it) })
        assertEquals(behaviorBefore, listOf(RECORDING_A, RECORDING_B, RECORDING_C).associateWith { eventStore.behaviorSummary(it) })
        assertEquals(assetsBefore, listOf(RECORDING_A, RECORDING_B, RECORDING_C).associateWith {
            catalogDao.mediaAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, sourceKey(it))
        })

        requireNotNull(listeningDatabase).close()
        listeningDatabase = openListeningDatabase()
        eventStore = eventStore(clock)
        ratingStore = ratingStore(clock)
        personalRanker = PersonalRanker(eventStore, ratingStore, nowEpochMs = { clock.now })
        sessionReranker = SessionContextReranker(eventStore, nowEpochMs = { clock.now })
        pipeline = LibraryRecommendationPipeline(
            candidateSource,
            personalRanker,
            sessionReranker,
            ArtistAwareDiversityReranker(groupingReader),
        )

        val afterReopen = pipeline.rank()
        assertEquals(contextual.map { it.base.base.recordingId }, afterReopen.map { it.base.base.recordingId })
        assertEquals(contextual.map { it.base.sessionReasons }, afterReopen.map { it.base.sessionReasons })

        repeat(7) {
            clock.now += TWENTY_MINUTES_MS
            eventStore.recordPlaybackActivity(PlaybackActivity(RECORDING_C))
        }
        val afterDecay = pipeline.rank()
        assertTrue(afterDecay.all { it.base.sessionAdjustment == 0.0 })
        assertTrue(afterDecay.all { it.base.finalScore == it.base.base.score })
        assertTrue(afterDecay.all { it.diversityAdjustment == 0.0 && it.finalScore == it.base.finalScore })

        clock.now += THIRTY_MINUTES_MS
        val persistedSession = eventStore.currentSession(clock.now)
        assertNull(persistedSession)
        val staleResult = pipeline.rank()
        assertTrue(staleResult.all { it.base.sessionAdjustment == 0.0 })
        assertTrue(staleResult.all { it.base.finalScore == it.base.base.score })
        assertTrue(staleResult.all { it.diversityAdjustment == 0.0 && it.finalScore == it.base.finalScore })
    }

    private fun openListeningDatabase(): ListeningEventDatabase =
        Room.databaseBuilder(context, ListeningEventDatabase::class.java, LISTENING_DATABASE)
            .allowMainThreadQueries()
            .build()
            .also { listeningDatabase = it }

    private fun eventStore(clock: MutableClock, vararg ids: String): RoomListeningEventStore =
        RoomListeningEventStore(
            dao = requireNotNull(listeningDatabase).listeningEventDao(),
            clock = clock,
            idGenerator = SequenceIdGenerator(ids.toList()),
        )

    private fun ratingStore(clock: MutableClock): RoomRecordingRatingStore =
        RoomRecordingRatingStore(
            dao = requireNotNull(listeningDatabase).recordingRatingDao(),
            nowEpochMs = { clock.now },
        )

    private fun localAsset(recordingId: String) = MediaAssetEntity(
        id = "asset:$recordingId",
        recordingId = recordingId,
        provider = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
        sourceKey = sourceKey(recordingId),
        uri = sourceKey(recordingId),
        path = null,
        dateAddedSeconds = null,
        dateModifiedSeconds = null,
        artworkUri = null,
    )

    private fun sourceKey(recordingId: String): String = "content://media/external/audio/${recordingId.takeLast(1)}"

    private class MutableClock(var now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private class SequenceIdGenerator(ids: List<String>) : ListeningIdGenerator {
        private val values = ArrayDeque(ids)
        override fun nextId(): String = values.removeFirst()
    }

    private companion object {
        const val LISTENING_DATABASE = "session-context-integration.db"
        const val RECORDING_A = "recording:a"
        const val RECORDING_B = "recording:b"
        const val RECORDING_C = "recording:c"
        const val TWENTY_MINUTES_MS = 20L * 60L * 1000L
        const val THIRTY_MINUTES_MS = 30L * 60L * 1000L
        const val EPSILON = 0.000001
    }
}

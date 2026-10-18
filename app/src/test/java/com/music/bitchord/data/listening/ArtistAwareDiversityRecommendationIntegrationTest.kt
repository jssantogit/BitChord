package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingRepository
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
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
class ArtistAwareDiversityRecommendationIntegrationTest {
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
    fun `real Room library pipeline interleaves canonical artists without mutating prior stages or stores`() = runTest {
        catalogDatabase = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listeningDatabase = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val catalogDao = requireNotNull(catalogDatabase).catalogDao()
        listOf(
            ArtistEntity(ARTIST_X, "Artist X"),
            ArtistEntity(ARTIST_Y, "Artist Y"),
            ArtistEntity(ARTIST_Z, "Artist Z"),
            ArtistEntity(ARTIST_X_CLONE, "Artist X"),
        ).forEach { catalogDao.upsertArtist(it) }

        val recordings = listOf(X1, X2, X3, Y1, Z1, UNKNOWN, COLLAB, SAME_NAME_OTHER_ID)
        recordings.forEach { id ->
            catalogDao.upsertRecording(
                RecordingEntity(
                    id = id,
                    title = id,
                    artistDisplay = if (id == Y1) "Artist Y" else if (id == Z1) "Artist Z" else "Artist X",
                    durationMs = 180_000L,
                ),
            )
            catalogDao.upsertMediaAsset(localAsset(id))
        }
        catalogDao.upsertRecordingArtistCredits(
            listOf(
                RecordingArtistCreditEntity(X1, 0, ARTIST_X),
                RecordingArtistCreditEntity(X2, 0, ARTIST_X),
                RecordingArtistCreditEntity(X3, 0, ARTIST_X),
                RecordingArtistCreditEntity(Y1, 0, ARTIST_Y),
                RecordingArtistCreditEntity(Z1, 0, ARTIST_Z),
                RecordingArtistCreditEntity(COLLAB, 0, ARTIST_X),
                RecordingArtistCreditEntity(COLLAB, 1, ARTIST_Y),
                RecordingArtistCreditEntity(SAME_NAME_OTHER_ID, 0, ARTIST_X_CLONE),
            ),
        )
        catalogDao.upsertMusicBrainzRecordingArtistCredits(
            listOf(
                MusicBrainzRecordingArtistCreditEntity(
                    recordingId = UNKNOWN,
                    position = 0,
                    musicBrainzArtistId = "mbid-x",
                    name = "Artist X",
                    joinPhrase = null,
                ),
            ),
        )

        val clock = MutableClock(0L)
        val eventStore = RoomListeningEventStore(
            dao = requireNotNull(listeningDatabase).listeningEventDao(),
            clock = clock,
            idGenerator = FailingIdGenerator,
        )
        val ratingStore = RoomRecordingRatingStore(
            dao = requireNotNull(listeningDatabase).recordingRatingDao(),
            nowEpochMs = { clock.now },
        )
        mapOf(
            X1 to 5.0,
            X2 to 4.9,
            X3 to 4.8,
            Y1 to 4.7,
            Z1 to 4.6,
            UNKNOWN to 4.5,
            COLLAB to 4.4,
            SAME_NAME_OTHER_ID to 4.3,
        ).forEach { (id, value) -> ratingStore.setRecordingRating(id, value) }
        clock.now = NOW

        val availability = CanonicalLibraryAvailabilityRepository(
            RoomCanonicalLibraryAvailabilityStore(catalogDao),
            CanonicalLibraryProviderConfigurationReader { setOf(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER) },
        )
        val candidateSource = LibraryRecommendationCandidateSource(availability)
        val personalRanker = PersonalRanker(eventStore, ratingStore, nowEpochMs = { clock.now })
        val sessionReranker = SessionContextReranker(eventStore, nowEpochMs = { clock.now })
        val diversityReranker = ArtistAwareDiversityReranker(
            CanonicalRecordingArtistGroupingRepository(
                RoomCanonicalRecordingArtistGroupingStore(catalogDao),
            ),
        )
        val pipeline = LibraryRecommendationPipeline(
            candidateSource,
            personalRanker,
            sessionReranker,
            diversityReranker,
        )

        val candidates = candidateSource.candidates()
        val personalBefore = personalRanker.rank(candidates)
        val sessionBefore = sessionReranker.rerank(personalBefore)
        val ratingsBefore = recordings.associateWith { ratingStore.currentRating(it) }
        val behaviorBefore = recordings.associateWith { eventStore.behaviorSummary(it) }
        val creditsBefore = recordings.associateWith { catalogDao.recordingArtistCredits(it) }
        val assetsBefore = recordings.associateWith {
            catalogDao.mediaAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, sourceKey(it))
        }
        val catalogTablesBefore = catalogTableNames()
        val listeningTablesBefore = listeningTableNames()

        val diversified = pipeline.rank()
        val limited = pipeline.rank(limit = 3)

        assertEquals(
            listOf(X1, X2, X3, Y1, Z1, UNKNOWN, COLLAB, SAME_NAME_OTHER_ID),
            personalBefore.map { it.recordingId },
        )
        assertEquals(personalBefore, sessionBefore.map { it.base })
        assertTrue(sessionBefore.all { it.sessionAdjustment == 0.0 && it.finalScore == it.base.score })
        assertEquals(listOf(X1, Y1, Z1), limited.map { it.base.base.recordingId })
        assertEquals(X1, diversified.first().base.base.recordingId)
        assertTrue(diversified.indexOfFirst { it.base.base.recordingId == Y1 } < diversified.indexOfFirst { it.base.base.recordingId == X2 })
        assertTrue(diversified.indexOfFirst { it.base.base.recordingId == Z1 } < diversified.indexOfFirst { it.base.base.recordingId == X3 })

        val unknown = diversified.first { it.base.base.recordingId == UNKNOWN }
        val sameNameOtherId = diversified.first { it.base.base.recordingId == SAME_NAME_OTHER_ID }
        val collab = diversified.first { it.base.base.recordingId == COLLAB }
        assertEquals(0.0, unknown.diversityAdjustment, EPSILON)
        assertTrue(unknown.overlappingArtistIds.isEmpty())
        assertEquals(0.0, sameNameOtherId.diversityAdjustment, EPSILON)
        assertTrue(sameNameOtherId.overlappingArtistIds.isEmpty())
        assertTrue(collab.diversityAdjustment in -15.0..0.0)
        assertTrue(collab.overlappingArtistIds.any { it == ARTIST_X || it == ARTIST_Y })
        assertTrue(collab.diversityAdjustment >= -15.0)

        assertEquals(
            personalBefore.associateBy { it.recordingId },
            diversified.associate { it.base.base.recordingId to it.base.base },
        )
        assertEquals(
            sessionBefore.associateBy { it.base.recordingId },
            diversified.associate { it.base.base.recordingId to it.base },
        )
        assertEquals(ratingsBefore, recordings.associateWith { ratingStore.currentRating(it) })
        assertEquals(behaviorBefore, recordings.associateWith { eventStore.behaviorSummary(it) })
        assertEquals(creditsBefore, recordings.associateWith { catalogDao.recordingArtistCredits(it) })
        assertEquals(assetsBefore, recordings.associateWith {
            catalogDao.mediaAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, sourceKey(it))
        })
        assertEquals(catalogTablesBefore, catalogTableNames())
        assertEquals(listeningTablesBefore, listeningTableNames())
        assertTrue(catalogTablesBefore.none { it.contains("diversity", ignoreCase = true) || it.contains("recommendation", ignoreCase = true) })
        assertTrue(listeningTablesBefore.none { it.contains("diversity", ignoreCase = true) || it.contains("recommendation", ignoreCase = true) })
        assertNull(eventStore.currentSession(clock.now))
    }

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

    private fun sourceKey(recordingId: String) = "content://media/external/audio/$recordingId"

    private fun catalogTableNames(): Set<String> = tableNames(requireNotNull(catalogDatabase))
    private fun listeningTableNames(): Set<String> = tableNames(requireNotNull(listeningDatabase))

    private fun tableNames(database: androidx.room.RoomDatabase): Set<String> {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        )
        return cursor.use {
            buildSet {
                while (it.moveToNext()) add(it.getString(0))
            }
        }
    }

    private class MutableClock(var now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private object FailingIdGenerator : ListeningIdGenerator {
        override fun nextId(): String = error("Diversity reranking must not write Listening events")
    }

    private companion object {
        const val ARTIST_X = "artist:x"
        const val ARTIST_Y = "artist:y"
        const val ARTIST_Z = "artist:z"
        const val ARTIST_X_CLONE = "artist:x-clone"
        const val X1 = "recording:x1"
        const val X2 = "recording:x2"
        const val X3 = "recording:x3"
        const val Y1 = "recording:y1"
        const val Z1 = "recording:z1"
        const val UNKNOWN = "recording:unknown"
        const val COLLAB = "recording:collab"
        const val SAME_NAME_OTHER_ID = "recording:same-name-other-id"
        const val NOW = 30L * 24L * 60L * 60L * 1000L
        const val EPSILON = 0.000001
    }
}

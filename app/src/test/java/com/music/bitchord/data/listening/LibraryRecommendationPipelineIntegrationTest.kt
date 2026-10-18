package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingRepository
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.OpenSubsonicLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.RoomCanonicalLibraryAvailabilityStore
import com.music.bitchord.data.catalog.RoomCanonicalRecordingArtistGroupingStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicProviderKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class LibraryRecommendationPipelineIntegrationTest {
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
    fun `real Room catalog flows through candidates and first-party stores into ranking pipeline`() = runTest {
        catalogDatabase = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listeningDatabase = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val catalogDao = requireNotNull(catalogDatabase).catalogDao()
        val accountRepository = accountRepository().also(::configureAccount)
        val activeRemoteProvider = openSubsonicProviderKey(ACTIVE_ACCOUNT_ID)
        val forgottenRemoteProvider = openSubsonicProviderKey("forgotten-account")

        upsertRecording(catalogDao, FAVORITE_REMOTE)
        upsertRecording(catalogDao, COMPLETED_LOCAL)
        upsertRecording(catalogDao, INACTIVE_REMOTE)
        upsertRecording(catalogDao, HISTORY_ONLY)
        catalogDao.upsertMediaAsset(asset("asset-favorite", FAVORITE_REMOTE, activeRemoteProvider, "remote-song-1"))
        catalogDao.upsertMediaAsset(
            asset(
                "asset-local",
                COMPLETED_LOCAL,
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                "content://media/external/audio/42",
            ),
        )
        catalogDao.upsertMediaAsset(asset("asset-inactive", INACTIVE_REMOTE, forgottenRemoteProvider, "remote-song-old"))

        val clock = MutableClock(1_000L)
        val eventStore = RoomListeningEventStore(
            dao = requireNotNull(listeningDatabase).listeningEventDao(),
            clock = clock,
            idGenerator = SequenceIdGenerator("session-1", "start-local", "end-local"),
        )
        val ratingStore = RoomRecordingRatingStore(
            dao = requireNotNull(listeningDatabase).recordingRatingDao(),
            nowEpochMs = { clock.now },
        )
        ratingStore.setRecordingRating(FAVORITE_REMOTE, 5.0)
        ratingStore.setRecordingRating(COMPLETED_LOCAL, 4.0)
        ratingStore.setRecordingRating(INACTIVE_REMOTE, 5.0)

        eventStore.recordPlaybackStarted(
            PlaybackStart(
                recordingId = COMPLETED_LOCAL,
                selectionIntent = SelectionIntent.MANUAL,
                selectionOrigin = SelectionOrigin.LIBRARY,
            ),
        )
        clock.now = 2_000L
        eventStore.recordPlaybackEnded(
            PlaybackEnd(
                recordingId = COMPLETED_LOCAL,
                listenedDurationMs = 180_000L,
                recordingDurationMs = 180_000L,
                endCause = PlaybackEndCause.ENDED,
            ),
        )
        clock.now = THIRTY_DAYS_MS

        val availability = CanonicalLibraryAvailabilityRepository(
            store = RoomCanonicalLibraryAvailabilityStore(catalogDao),
            providerConfiguration = OpenSubsonicLibraryProviderConfigurationReader(accountRepository),
        )
        val candidateSource = LibraryRecommendationCandidateSource(availability)
        val ranker = PersonalRanker(
            behaviorReader = eventStore,
            ratingReader = ratingStore,
            nowEpochMs = { clock.now },
        )
        val pipeline = LibraryRecommendationPipeline(
            candidateSource = candidateSource,
            personalRanker = ranker,
            sessionContextReranker = SessionContextReranker(eventStore, nowEpochMs = { clock.now }),
            artistAwareDiversityReranker = ArtistAwareDiversityReranker(
                CanonicalRecordingArtistGroupingRepository(
                    RoomCanonicalRecordingArtistGroupingStore(catalogDao),
                ),
            ),
        )

        val localBehaviorBefore = eventStore.behaviorSummary(COMPLETED_LOCAL)
        val favoriteHistoryBefore = ratingStore.ratingHistory(FAVORITE_REMOTE)
        val localAssetBefore = catalogDao.mediaAsset(
            CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
            "content://media/external/audio/42",
        )
        val remoteAssetBefore = catalogDao.mediaAsset(activeRemoteProvider, "remote-song-1")

        val candidates = candidateSource.candidates()
        val ranked = pipeline.rank()

        assertEquals(
            listOf(COMPLETED_LOCAL, FAVORITE_REMOTE).sorted(),
            candidates.map { it.recordingId },
        )
        assertEquals(listOf(FAVORITE_REMOTE, COMPLETED_LOCAL), ranked.map { it.base.base.recordingId })
        assertTrue(RecommendationReason.EXPLICIT_FAVORITE in ranked.first().base.base.reasons)
        assertTrue(RecommendationReason.COMPLETION_HEAVY in ranked[1].base.base.reasons)
        assertTrue(ranked.all {
            it.base.sessionAdjustment == 0.0 &&
                it.base.finalScore == it.base.base.score &&
                it.diversityAdjustment == 0.0 &&
                it.finalScore == it.base.finalScore
        })
        assertFalse(ranked.any { it.base.base.recordingId == INACTIVE_REMOTE })
        assertFalse(ranked.any { it.base.base.recordingId == HISTORY_ONLY })

        assertEquals(localBehaviorBefore, eventStore.behaviorSummary(COMPLETED_LOCAL))
        assertEquals(favoriteHistoryBefore, ratingStore.ratingHistory(FAVORITE_REMOTE))
        assertEquals(localAssetBefore, catalogDao.mediaAsset(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, "content://media/external/audio/42"))
        assertEquals(remoteAssetBefore, catalogDao.mediaAsset(activeRemoteProvider, "remote-song-1"))
    }

    @Test
    fun `real Room availability reacts to Forget without deleting assets and keeps local fallback`() = runTest {
        catalogDatabase = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val catalogDao = requireNotNull(catalogDatabase).catalogDao()
        val accountRepository = accountRepository().also(::configureAccount)
        val remoteProvider = openSubsonicProviderKey(ACTIVE_ACCOUNT_ID)

        upsertRecording(catalogDao, REMOTE_ONLY)
        upsertRecording(catalogDao, SHARED_LOCAL_REMOTE)
        catalogDao.upsertMediaAsset(asset("asset-remote-only", REMOTE_ONLY, remoteProvider, "remote-only"))
        catalogDao.upsertMediaAsset(asset("asset-shared-remote", SHARED_LOCAL_REMOTE, remoteProvider, "shared-remote"))
        catalogDao.upsertMediaAsset(
            asset(
                "asset-shared-local",
                SHARED_LOCAL_REMOTE,
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                "content://media/external/audio/99",
            ),
        )

        val availability = CanonicalLibraryAvailabilityRepository(
            store = RoomCanonicalLibraryAvailabilityStore(catalogDao),
            providerConfiguration = OpenSubsonicLibraryProviderConfigurationReader(accountRepository),
        )
        assertEquals(
            listOf(REMOTE_ONLY, SHARED_LOCAL_REMOTE).sorted(),
            availability.availableRecordingIds(),
        )

        accountRepository.forget()

        assertEquals(listOf(SHARED_LOCAL_REMOTE), availability.availableRecordingIds())
        assertNotNull(catalogDao.mediaAsset(remoteProvider, "remote-only"))
        assertNotNull(catalogDao.mediaAsset(remoteProvider, "shared-remote"))
    }

    private fun accountRepository(): OpenSubsonicAccountRepository = OpenSubsonicAccountRepository(
        persistence = FakeAccountPersistence(),
        credentials = FakeCredentialStore(),
        idFactory = { ACTIVE_ACCOUNT_ID },
    )

    private fun configureAccount(repository: OpenSubsonicAccountRepository) {
        repository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://offline-is-fine.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "fixture-key",
            ),
        )
        repository.recordHealthFailure(atEpochSeconds = 321L)
    }

    private suspend fun upsertRecording(
        dao: com.music.bitchord.data.catalog.CanonicalCatalogDao,
        recordingId: String,
    ) {
        dao.upsertRecording(
            RecordingEntity(
                id = recordingId,
                title = recordingId,
                artistDisplay = "Fixture Artist",
                durationMs = 180_000L,
            ),
        )
    }

    private fun asset(
        id: String,
        recordingId: String,
        provider: String,
        sourceKey: String,
    ) = MediaAssetEntity(
        id = id,
        recordingId = recordingId,
        provider = provider,
        sourceKey = sourceKey,
        uri = "fixture://$id",
        path = null,
        dateAddedSeconds = null,
        dateModifiedSeconds = null,
        artworkUri = null,
    )

    private class MutableClock(var now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private class SequenceIdGenerator(vararg ids: String) : ListeningIdGenerator {
        private val values = ArrayDeque(ids.toList())
        override fun nextId(): String = values.removeFirst()
    }

    private class FakeAccountPersistence : OpenSubsonicAccountPersistence {
        private var record: OpenSubsonicAccountRecord? = null
        override fun read(): OpenSubsonicAccountRecord? = record
        override fun write(record: OpenSubsonicAccountRecord) {
            this.record = record
        }
        override fun clear() {
            record = null
        }
    }

    private class FakeCredentialStore : OpenSubsonicCredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun read(accountId: String): String? = values[accountId]
        override fun write(accountId: String, credential: String) {
            values[accountId] = credential
        }
        override fun clear(accountId: String) {
            values.remove(accountId)
        }
    }

    private companion object {
        const val ACTIVE_ACCOUNT_ID = "home"
        const val FAVORITE_REMOTE = "recording:favorite-remote"
        const val COMPLETED_LOCAL = "recording:completed-local"
        const val INACTIVE_REMOTE = "recording:inactive-remote"
        const val HISTORY_ONLY = "recording:history-only"
        const val REMOTE_ONLY = "recording:remote-only"
        const val SHARED_LOCAL_REMOTE = "recording:shared-local-remote"
        const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

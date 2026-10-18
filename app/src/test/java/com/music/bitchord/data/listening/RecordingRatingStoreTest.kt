package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
class RecordingRatingStoreTest {
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
    fun firstRatingCreatesCurrentStateAndHistory() = runTest {
        val clock = MutableClock(1_000L)
        val store = openStore(clock)

        store.setRecordingRating(RECORDING_A, 5.0)

        assertEquals(
            RecordingRating(RECORDING_A, 5.0, 1_000L),
            store.currentRating(RECORDING_A),
        )
        val history = store.ratingHistory(RECORDING_A)
        assertEquals(1, history.size)
        assertNull(history.single().previousValue)
        assertEquals(5.0, history.single().newValue!!, 0.0)
        assertEquals(RecordingRatingChangeType.SET, history.single().type)
        assertEquals(1_000L, history.single().changedAtEpochMs)

        val signal = store.explicitRatingSignal(RECORDING_A)
        assertTrue(signal.hasExplicitFeedback)
        assertEquals(5.0, signal.currentRating!!, 0.0)
        assertEquals(1_000L, signal.currentRatedAtEpochMs!!)
        assertEquals(RecordingRatingDirection.INITIAL_SET, signal.lastChangeDirection)
    }

    @Test
    fun downgradeFromFiveToFourPointFivePreservesTransition() = runTest {
        val clock = MutableClock(1_000L)
        val store = openStore(clock)
        store.setRecordingRating(RECORDING_A, 5.0)

        clock.now = 2_000L
        store.setRecordingRating(RECORDING_A, 4.5)

        assertEquals(4.5, store.currentRating(RECORDING_A)!!.value, 0.0)
        val history = store.ratingHistory(RECORDING_A)
        assertEquals(2, history.size)
        assertEquals(5.0, history[1].previousValue!!, 0.0)
        assertEquals(4.5, history[1].newValue!!, 0.0)
        assertEquals(RecordingRatingDirection.DECREASED, store.explicitRatingSignal(RECORDING_A).lastChangeDirection)
    }

    @Test
    fun clearRemovesEffectiveStateWithoutDestroyingHistory() = runTest {
        val clock = MutableClock(1_000L)
        val store = openStore(clock)
        store.setRecordingRating(RECORDING_A, 5.0)

        clock.now = 2_000L
        store.clearRecordingRating(RECORDING_A)

        assertNull(store.currentRating(RECORDING_A))
        val history = store.ratingHistory(RECORDING_A)
        assertEquals(2, history.size)
        assertEquals(RecordingRatingChangeType.CLEAR, history[1].type)
        assertEquals(5.0, history[1].previousValue!!, 0.0)
        assertNull(history[1].newValue)
        val signal = store.explicitRatingSignal(RECORDING_A)
        assertTrue(signal.hasExplicitFeedback)
        assertNull(signal.currentRating)
        assertEquals(RecordingRatingDirection.CLEARED, signal.lastChangeDirection)
    }

    @Test
    fun reratingAfterClearKeepsLineageAndDoesNotPretendItWasDirectDowngrade() = runTest {
        val clock = MutableClock(1_000L)
        val store = openStore(clock)
        store.setRecordingRating(RECORDING_A, 5.0)
        clock.now = 2_000L
        store.clearRecordingRating(RECORDING_A)
        clock.now = 3_000L

        store.setRecordingRating(RECORDING_A, 4.5)

        val history = store.ratingHistory(RECORDING_A)
        assertEquals(3, history.size)
        assertEquals(
            listOf(RecordingRatingChangeType.SET, RecordingRatingChangeType.CLEAR, RecordingRatingChangeType.SET),
            history.map { it.type },
        )
        assertNull(history[2].previousValue)
        assertEquals(4.5, history[2].newValue!!, 0.0)
        assertEquals(RecordingRatingDirection.INITIAL_SET, store.explicitRatingSignal(RECORDING_A).lastChangeDirection)
    }

    @Test
    fun ratingIdentityIsRecordingIdAcrossTwoMediaAssets() = runTest {
        val store = openStore(MutableClock(1_000L))
        val local = mediaAsset("asset-local", RECORDING_A, "local-mediastore", "content://track/1")
        val navidrome = mediaAsset("asset-remote", RECORDING_A, "opensubsonic:home", "remote-song-id")

        store.setRecordingRating(local.recordingId, 4.0)

        assertEquals(4.0, store.currentRating(navidrome.recordingId)!!.value, 0.0)
        assertEquals(1, store.ratedRecordings().size)
        assertEquals(RECORDING_A, store.ratedRecordings().single().recordingId)
    }

    @Test
    fun anotherRecordingNeverInheritsRatingFromMatchingProviderMetadata() = runTest {
        val store = openStore(MutableClock(1_000L))
        val assetA = mediaAsset("asset-a", RECORDING_A, "opensubsonic:home", "provider-song-7")
        val assetB = mediaAsset("asset-b", RECORDING_B, "opensubsonic:home", "provider-song-7")

        store.setRecordingRating(assetA.recordingId, 5.0)

        assertNull(store.currentRating(assetB.recordingId))
        assertFalse(store.explicitRatingSignal(RECORDING_B).hasExplicitFeedback)
    }

    @Test
    fun stateAndHistorySurviveDatabaseCloseAndReopen() = runTest {
        val clock = MutableClock(1_000L)
        val store = openStore(clock, persistent = true)
        store.setRecordingRating(RECORDING_A, 5.0)
        clock.now = 2_000L
        store.setRecordingRating(RECORDING_A, 4.5)

        database!!.close()
        database = null
        val reopened = openStore(clock, persistent = true)

        assertEquals(4.5, reopened.currentRating(RECORDING_A)!!.value, 0.0)
        assertEquals(listOf(5.0, 4.5), reopened.ratingHistory(RECORDING_A).map { it.newValue })
    }

    @Test
    fun rapidConcurrentUpdatesKeepCurrentStateEqualToLastHistoryEntry() = runTest {
        val store = openStore(MutableClock(10_000L))
        val values = listOf(5.0, 4.5, 4.0, 3.0, 2.0, 4.5, 5.0, 3.0)

        coroutineScope {
            values.map { value ->
                async(Dispatchers.Default) { store.setRecordingRating(RECORDING_A, value) }
            }.awaitAll()
        }

        val history = store.ratingHistory(RECORDING_A)
        assertEquals(values.size, history.size)
        assertEquals(history.last().newValue, store.currentRating(RECORDING_A)!!.value)
        history.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.newValue, next.previousValue)
        }
    }

    @Test
    fun ratedRecordingsAreDeterministicForTimestampTies() = runTest {
        val store = openStore(MutableClock(1_000L))
        store.setRecordingRating("recording:z", 4.0)
        store.setRecordingRating("recording:a", 5.0)
        store.setRecordingRating("recording:m", 3.0)

        assertEquals(
            listOf("recording:a", "recording:m", "recording:z"),
            store.ratedRecordings().map { it.recordingId },
        )
    }

    @Test
    fun changingRatingDoesNotMutatePlaybackEventStore() = runTest {
        val db = openDatabase(persistent = false)
        database = db
        val playbackStore = RoomListeningEventStore(
            dao = db.listeningEventDao(),
            clock = FixedListeningClock(1_000L),
            idGenerator = SequenceIdGenerator("session-1", "playback-start"),
        )
        val start = playbackStore.recordPlaybackStarted(PlaybackStart(recordingId = RECORDING_A))
        val before = playbackStore.eventsForSession(start.sessionId)
        val ratingStore = RoomRecordingRatingStore(db.recordingRatingDao(), nowEpochMs = { 2_000L })

        ratingStore.setRecordingRating(RECORDING_A, 5.0)
        ratingStore.clearRecordingRating(RECORDING_A)

        assertEquals(before, playbackStore.eventsForSession(start.sessionId))
    }

    @Test
    fun forgettingOpenSubsonicConnectionDoesNotRemoveFirstPartyRating() = runTest {
        val store = openStore(MutableClock(1_000L))
        store.setRecordingRating(RECORDING_A, 5.0)
        val persistence = FakeAccountPersistence()
        val credentials = FakeCredentialStore()
        val accountRepository = OpenSubsonicAccountRepository(
            persistence = persistence,
            credentials = credentials,
            idFactory = { "account-1" },
        )
        accountRepository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "secret",
            ),
        )

        accountRepository.forget()

        assertNull(accountRepository.current())
        assertEquals(5.0, store.currentRating(RECORDING_A)!!.value, 0.0)
        assertEquals(1, store.ratingHistory(RECORDING_A).size)
    }

    @Test
    fun observeRatingEmitsCanonicalStateChange() = runTest {
        val store = openStore(MutableClock(1_000L))
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            store.observeRating(RECORDING_A).drop(1).first()
        }

        store.setRecordingRating(RECORDING_A, 4.0)

        assertEquals(4.0, observed.await()!!.value, 0.0)
    }

    private fun openStore(
        clock: MutableClock,
        persistent: Boolean = false,
    ): RoomRecordingRatingStore {
        val db = openDatabase(persistent)
        database = db
        return RoomRecordingRatingStore(db.recordingRatingDao(), nowEpochMs = { clock.now })
    }

    private fun openDatabase(persistent: Boolean): ListeningEventDatabase =
        if (persistent) {
            Room.databaseBuilder(context, ListeningEventDatabase::class.java, TEST_DATABASE)
                .allowMainThreadQueries()
                .build()
        } else {
            Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }

    private fun mediaAsset(
        id: String,
        recordingId: String,
        provider: String,
        sourceKey: String,
    ) = MediaAssetEntity(
        id = id,
        recordingId = recordingId,
        provider = provider,
        sourceKey = sourceKey,
        uri = "bitchord://test/$id",
        path = null,
        dateAddedSeconds = null,
        dateModifiedSeconds = null,
        artworkUri = null,
    )

    private class MutableClock(var now: Long)

    private class FixedListeningClock(private val now: Long) : ListeningClock {
        override fun nowEpochMs(): Long = now
    }

    private class SequenceIdGenerator(vararg values: String) : ListeningIdGenerator {
        private val remaining = ArrayDeque(values.toList())
        override fun nextId(): String = remaining.removeFirst()
    }

    private class FakeAccountPersistence : OpenSubsonicAccountPersistence {
        var value: OpenSubsonicAccountRecord? = null
        override fun read(): OpenSubsonicAccountRecord? = value
        override fun write(record: OpenSubsonicAccountRecord) {
            value = record
        }
        override fun clear() {
            value = null
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
        const val TEST_DATABASE = "recording-rating-store-test.db"
        const val RECORDING_A = "recording:canonical:a"
        const val RECORDING_B = "recording:canonical:b"
    }
}

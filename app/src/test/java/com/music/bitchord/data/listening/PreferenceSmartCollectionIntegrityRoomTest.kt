package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class PreferenceSmartCollectionIntegrityRoomTest {
    private lateinit var context: Context
    private var catalog: CanonicalCatalogDatabase? = null
    private var listening: ListeningEventDatabase? = null
    private val catName = "l8-catalog-integrity.db"
    private val listenName = "l8-listening-integrity.db"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(catName)
        context.deleteDatabase(listenName)
    }
    @After fun cleanup() {
        catalog?.close()
        listening?.close()
        context.deleteDatabase(catName)
        context.deleteDatabase(listenName)
    }
    private fun open() {
        catalog = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, catName)
            .allowMainThreadQueries().build()
        listening = Room.databaseBuilder(context, ListeningEventDatabase::class.java, listenName)
            .allowMainThreadQueries().build()
    }
    private fun store() = RoomRecordingRatingStore(
        listening!!.recordingRatingDao(), nowEpochMs = { 12_000L },
    )
    private fun inference() = CanonicalInferredPreferencePreview(
        RoomCanonicalPreferenceMembershipReader(catalog!!), store(),
    )

    @Test fun ratingClearAndRestartNeverCreateInferredNeutralOrChangeEventStore() = runTest {
        open()
        catalog!!.catalogDao().upsertRecording(RecordingEntity("rec:a", "A", "Artist", 200_000L))
        catalog!!.catalogDao().upsertRelease(ReleaseEntity("rel:a", "Album", null, null))
        catalog!!.catalogDao().upsertReleaseTrack(ReleaseTrackEntity("rel:a", "rec:a", null))
        val writer = store()
        writer.setRecordingRating("rec:a", 5.0)
        assertEquals(5.0,
            inference().preview(CanonicalPreferenceTargetType.RELEASE, "rel:a")!!.inferredRating!!, 0.0)
        writer.clearRecordingRating("rec:a")
        assertNull(inference().preview(CanonicalPreferenceTargetType.RELEASE, "rel:a")!!.inferredRating)
        assertEquals(2, writer.ratingHistory("rec:a").size)
        assertTrue(listening!!.listeningEventDao().activeSession() == null)

        catalog!!.close()
        listening!!.close()
        catalog = null
        listening = null
        open()
        assertNull(inference().preview(CanonicalPreferenceTargetType.RELEASE, "rel:a")!!.inferredRating)
        assertEquals(2, store().ratingHistory("rec:a").size)
        assertTrue(store().ratedRecordings().isEmpty())
        assertTrue(listening!!.listeningEventDao().activeSession() == null)
    }

    @Test fun concurrentExplicitMutationsAndBoundedPreviewReadsHaveConsistentFinalState() = runTest {
        open()
        catalog!!.catalogDao().upsertRecording(RecordingEntity("rec:a", "A", "Artist", 200_000L))
        catalog!!.catalogDao().upsertRelease(ReleaseEntity("rel:a", "Album", null, null))
        catalog!!.catalogDao().upsertReleaseTrack(ReleaseTrackEntity("rel:a", "rec:a", null))
        val writer = store()
        val projection = inference()
        coroutineScope {
            (0 until 12).map { iteration ->
                async(Dispatchers.Default) {
                    when (iteration % 3) {
                        0 -> writer.setRecordingRating("rec:a", 5.0)
                        1 -> writer.setRecordingRating("rec:a", 4.0)
                        else -> writer.clearRecordingRating("rec:a")
                    }
                    val interim = projection.preview(CanonicalPreferenceTargetType.RELEASE, "rel:a")
                    assertNotNull(interim)
                    assertTrue(interim!!.coverage in 0.0..1.0)
                }
            }.awaitAll()
        }
        val current = writer.currentRating("rec:a")
        val history = writer.ratingHistory("rec:a")
        assertTrue(history.isNotEmpty())
        assertEquals(current?.value, history.last().newValue)
        assertEquals(current?.value, projection.preview(
            CanonicalPreferenceTargetType.RELEASE, "rel:a")!!.inferredRating)
    }
}

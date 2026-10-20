package com.music.bitchord.data.listening

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
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
class CanonicalInferredPreferenceRoomTest {
    private lateinit var context: Context
    private var catalog: CanonicalCatalogDatabase? = null
    private var listening: ListeningEventDatabase? = null

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("l5-catalog.db")
        context.deleteDatabase("l5-listening.db")
    }

    @After fun cleanup() {
        catalog?.close()
        listening?.close()
        context.deleteDatabase("l5-catalog.db")
        context.deleteDatabase("l5-listening.db")
    }

    private fun open() {
        catalog = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, "l5-catalog.db")
            .allowMainThreadQueries().build()
        listening = Room.databaseBuilder(context, ListeningEventDatabase::class.java, "l5-listening.db")
            .allowMainThreadQueries().build()
    }

    private fun preview() = CanonicalInferredPreferencePreview(
        RoomCanonicalPreferenceMembershipReader(catalog!!),
        RoomRecordingRatingStore(listening!!.recordingRatingDao(), nowEpochMs = { 9_000L }),
    )

    @Test fun releaseAndArtistAreReadOnlyAndSurviveBothRoomReopens() = runTest {
        open()
        val dao = catalog!!.catalogDao()
        dao.upsertRecording(RecordingEntity("rec:a", "A", "Same Display", null))
        dao.upsertRecording(RecordingEntity("rec:b", "B", "Same Display", null))
        dao.upsertRecording(RecordingEntity("rec:c", "C", "Same Display", null))
        dao.upsertRelease(ReleaseEntity("rel:x", "X", null, null))
        dao.upsertArtist(ArtistEntity("artist:one", "Same Display"))
        dao.upsertArtist(ArtistEntity("artist:two", "Same Display"))
        listOf("rec:a", "rec:b", "rec:c").forEach {
            dao.upsertReleaseTrack(ReleaseTrackEntity("rel:x", it, null))
        }
        dao.upsertRecordingArtistCredits(listOf(
            RecordingArtistCreditEntity("rec:a", 0, "artist:one"),
            RecordingArtistCreditEntity("rec:b", 0, "artist:one"),
            RecordingArtistCreditEntity("rec:c", 0, "artist:two"),
        ))
        val ratings = RoomRecordingRatingStore(listening!!.recordingRatingDao(), nowEpochMs = { 9_000L })
        ratings.setRecordingRating("rec:a", 5.0)
        ratings.setRecordingRating("rec:b", 4.0)

        val release = preview().preview(CanonicalPreferenceTargetType.RELEASE, "rel:x")!!
        assertEquals(4.5, release.inferredRating!!, 0.0)
        assertEquals(3, release.totalRecordingCount)
        assertEquals(2, release.ratedRecordingCount)
        assertEquals(CanonicalPreferenceConfidence.MEDIUM, release.confidence)
        val artist = preview().preview(CanonicalPreferenceTargetType.ARTIST, "artist:two")!!
        assertNull(artist.inferredRating)
        assertEquals(1, artist.totalRecordingCount)
        assertEquals(CanonicalPreferenceConfidence.INSUFFICIENT, artist.confidence)
        assertEquals(2, ratings.ratedRecordings().size)

        catalog!!.close()
        listening!!.close()
        catalog = null
        listening = null
        open()
        assertEquals(release, preview().preview(CanonicalPreferenceTargetType.RELEASE, "rel:x"))
        assertEquals(artist, preview().preview(CanonicalPreferenceTargetType.ARTIST, "artist:two"))
        assertEquals(2, listening!!.recordingRatingDao().ratedRecordings(Int.MAX_VALUE).size)
    }

    @Test fun missingCanonicalIdentityAndIncompleteMembersFailClosed() = runTest {
        open()
        assertNull(preview().preview(CanonicalPreferenceTargetType.RELEASE, "rel:missing"))
        val dao = catalog!!.catalogDao()
        dao.upsertRecording(RecordingEntity("rec:a", "A", "A", null))
        dao.upsertRecording(RecordingEntity("rec:b", "B", "B", null))
        dao.upsertRelease(ReleaseEntity("rel:large", "Large", null, null))
        dao.upsertReleaseTrack(ReleaseTrackEntity("rel:large", "rec:a", null))
        dao.upsertReleaseTrack(ReleaseTrackEntity("rel:large", "rec:b", null))
        val bounded = CanonicalInferredPreferencePreview(
            RoomCanonicalPreferenceMembershipReader(catalog!!, maximumMembers = 1),
            RoomRecordingRatingStore(listening!!.recordingRatingDao()),
        )
        assertNull(bounded.preview(CanonicalPreferenceTargetType.RELEASE, "rel:large"))
        assertTrue(preview().preview(CanonicalPreferenceTargetType.RELEASE, "rel:large")!!.evidenceRecordingIds.isEmpty())
    }
}

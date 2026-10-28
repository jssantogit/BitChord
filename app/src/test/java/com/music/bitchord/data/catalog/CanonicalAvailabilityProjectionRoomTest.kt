package com.music.bitchord.data.catalog

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalAvailabilityProjectionRoomTest {
    @Test fun providerIdOnlyProjectionDeduplicatesCanonicalRecordingsAndNeverCrossesSources() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:alpha", "A", "Artist", 100_000L))
            dao.upsertRecording(RecordingEntity("rec:beta", "B", "Artist", 100_000L))
            suspend fun asset(id: String, recordingId: String, provider: String) {
                dao.upsertMediaAsset(MediaAssetEntity(
                    id = id, recordingId = recordingId, provider = provider,
                    sourceKey = id, uri = "content://fixture/$id",
                    path = null, dateAddedSeconds = null, dateModifiedSeconds = null,
                    artworkUri = null,
                ))
            }
            asset("local:alpha:1", "rec:alpha", CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
            asset("local:alpha:2", "rec:alpha", CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
            asset("remote:alpha", "rec:alpha", "opensubsonic:account-one")
            asset("remote:beta", "rec:beta", "opensubsonic:account-one")
            val store = RoomCanonicalLibraryAvailabilityStore(dao)
            assertEquals(listOf("rec:alpha"), store.recordingIdsForProviders(
                listOf(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER),
            ))
            assertEquals(listOf("rec:alpha", "rec:beta"), store.recordingIdsForProviders(
                listOf("opensubsonic:account-one"),
            ))
            assertEquals(emptyList<String>(), store.recordingIdsForProviders(
                listOf("opensubsonic:other-account"),
            ))
        } finally {
            db.close()
        }
    }
}

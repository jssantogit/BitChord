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
    @Test fun playbackRevalidationCanRead500CanonicalAssetsInSqliteSafeChunks() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val ids = (1..500).map { "rec:" + it.toString().padStart(3, '0') }
            ids.forEach { id ->
                dao.upsertRecording(RecordingEntity(id, "Track", "Artist", 90_000L))
                dao.upsertMediaAsset(MediaAssetEntity(
                    id = "asset:$id", recordingId = id,
                    provider = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                    sourceKey = "native:$id", uri = "content://fixture/$id",
                    path = null, dateAddedSeconds = null,
                    dateModifiedSeconds = null, artworkUri = null,
                ))
            }
            val recordings = ids.chunked(400).flatMap { dao.recordingsByIds(it) }
                .associateBy { it.id }
            val assets = ids.map { "asset:$it" }.chunked(400)
                .flatMap { dao.mediaAssetsByIds(it) }.associateBy { it.id }
            assertEquals(500, recordings.size)
            assertEquals(500, assets.size)
            for (id in ids) {
                assertEquals(id, assets["asset:$id"]?.recordingId)
                assertEquals(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, assets["asset:$id"]?.provider)
                assertEquals("Track", recordings[id]?.title)
            }
            assertEquals(emptyList<MediaAssetEntity>(), dao.mediaAssetsByIds(
                listOf("asset:missing"),
            ))
        } finally {
            db.close()
        }
    }

}

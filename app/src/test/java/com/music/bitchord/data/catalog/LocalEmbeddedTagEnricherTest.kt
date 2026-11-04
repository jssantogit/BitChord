package com.music.bitchord.data.catalog

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class LocalEmbeddedTagEnricherTest {
    @Test fun unchangedFileIsNeverReparsedAndChangedFilesInvalidatePreviousTags() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r", "Song", "Artist", 200_000L))
            dao.upsertMediaAsset(MediaAssetEntity(
                "asset", "r", CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                "song", "content://media/audio/1", null, null, 10L, null))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity(
                "asset", "[\"Pop\"]", null,
                localSourceFingerprint = "v3:10:100",
            ))
            var reads = 0
            val first = LocalEmbeddedTagEnricher.processPending(db) {
                reads++
                LocalEmbeddedTags(listOf("Dream Pop"), listOf("Calm"),
                    listOf(128.5), listOf("Shoegaze"), listOf("Alice"))
            }
            assertEquals(1, first.updated)
            assertEquals(1, reads)
            val tagged = requireNotNull(dao.mediaAssetTagsById("asset"))
            assertEquals("[\"Pop\",\"Dream Pop\"]", tagged.genresJson)
            assertEquals("[\"Calm\"]", tagged.moodsJson)
            assertEquals("[128.5]", tagged.bpmsJson)
            assertEquals("[\"Shoegaze\"]", tagged.stylesJson)
            assertEquals("[\"Alice\"]", tagged.composersJson)
            assertEquals("v3:10:100", tagged.localParsedFingerprint)
            LocalEmbeddedTagEnricher.processPending(db) {
                reads++; error("unchanged tag was reopened")
            }
            assertEquals(1, reads)

            dao.upsertMediaAssetTags(MediaAssetTagsEntity(
                "asset", "[\"New Index Genre\"]", null,
                localSourceFingerprint = "v3:11:100",
            ))
            LocalEmbeddedTagEnricher.processPending(db) {
                reads++; LocalEmbeddedTags(emptyList(), emptyList(),
                    emptyList(), emptyList())
            }
            assertEquals(2, reads)
            val updated = requireNotNull(dao.mediaAssetTagsById("asset"))
            assertEquals("[\"New Index Genre\"]", updated.genresJson)
            assertEquals("[]", updated.bpmsJson)
        } finally { db.close() }
    }

    @Test fun rescanOfUnchangedFilePreservesParsedTagsButNewFingerprintInvalidatesThem() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context,
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sourceUri = "content://media/external/audio/media/100"
            val original = com.music.bitchord.data.model.Song(
                videoId = sourceUri, title = "Test", artist = "Artist",
                thumbnailUrl = null, durationText = "3:00", localUri = sourceUri,
                localDateModifiedSeconds = 100L, localSizeBytes = 1000L,
            )
            CanonicalLocalLibrary.syncWithDatabase(context, db, listOf(original))
            val dao = db.catalogDao()
            val asset = requireNotNull(dao.mediaAsset(
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, sourceUri))
            dao.upsertMediaAssetTags(requireNotNull(dao.mediaAssetTagsById(asset.id)).copy(
                moodsJson = "[\"Calm\"]", bpmsJson = "[120.0]",
                stylesJson = "[\"House\"]", composersJson = "[\"Alice\"]",
                localParsedFingerprint = "v3:100:1000",
            ))
            CanonicalLocalLibrary.syncWithDatabase(context, db, listOf(original))
            var after = requireNotNull(dao.mediaAssetTagsById(asset.id))
            assertEquals("[\"Calm\"]", after.moodsJson)
            assertEquals("[120.0]", after.bpmsJson)
            assertEquals("[\"House\"]", after.stylesJson)
            assertEquals("[\"Alice\"]", after.composersJson)
            assertEquals("v3:100:1000", after.localParsedFingerprint)

            CanonicalLocalLibrary.syncWithDatabase(context, db, listOf(
                original.copy(localSizeBytes = 1001L),
            ))
            after = requireNotNull(dao.mediaAssetTagsById(asset.id))
            assertNull(after.moodsJson)
            assertNull(after.bpmsJson)
            assertNull(after.stylesJson)
            assertNull(after.composersJson)
            assertEquals("v3:100:1001", after.localSourceFingerprint)
            assertNull(after.localParsedFingerprint)
        } finally { db.close() }
    }

    @Test fun inaccessibleFileIsRetriedAndNeverRecordedAsKnownEmpty() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r", "Test", "Artist", null))
            dao.upsertMediaAsset(MediaAssetEntity("asset", "r",
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                "local:1", "content://media/audio/1", null, null, 1L, null))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity(
                "asset", null, null, localSourceFingerprint = "v3:1:10",
            ))
            val failed = LocalEmbeddedTagEnricher.processPending(db) {
                error("Permission transiently denied")
            }
            assertEquals(1, failed.accessErrors)
            assertNull(dao.mediaAssetTagsById("asset")?.localParsedFingerprint)
            assertNull(dao.mediaAssetTagsById("asset")?.moodsJson)
            assertEquals(1, LocalEmbeddedTagEnricher.processPending(db) {
                LocalEmbeddedTags(emptyList(), emptyList(), emptyList(), emptyList())
            }.updated)
            assertEquals("[]", dao.mediaAssetTagsById("asset")?.moodsJson)
        } finally { db.close() }
    }

    @Test fun staleFingerprintIsNeverOverwrittenAfterConcurrentRescan() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r", "Song", "Artist", null))
            dao.upsertMediaAsset(MediaAssetEntity(
                "asset", "r", CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                "song", "content://media/audio/1", null, null, 10L, null))
            dao.upsertMediaAssetTags(MediaAssetTagsEntity(
                "asset", null, null, localSourceFingerprint = "v3:10:100",
            ))
            val result = LocalEmbeddedTagEnricher.processPending(db) {
                dao.upsertMediaAssetTags(MediaAssetTagsEntity(
                    "asset", null, null, localSourceFingerprint = "v3:11:101",
                ))
                LocalEmbeddedTags(listOf("STALE"), emptyList(), listOf(120.0), emptyList())
            }
            assertEquals(0, result.updated)
            assertNull(dao.mediaAssetTagsById("asset")?.genresJson)
            assertNull(dao.mediaAssetTagsById("asset")?.localParsedFingerprint)
            assertTrue(result.superseded > 0)
        } finally { db.close() }
    }
}

package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.CanonicalMultiSourceBrowseRepository
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.RoomCanonicalMultiSourceBrowseStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalMultiSourceBrowseRoomTest {
    private val local = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
    private val remote = "opensubsonic:home"

    private suspend fun seed(db: CanonicalCatalogDatabase) {
        val dao = db.catalogDao()
        dao.upsertRecording(RecordingEntity("canonical-a", "Same Title", "Artist", null))
        dao.upsertRecording(RecordingEntity("canonical-b", "Same Title", "Artist", null))
        dao.upsertMediaAsset(MediaAssetEntity(
            "asset-local", "canonical-a", local, "mediastore-native-55", "content://test/55",
            null, null, null, null,
        ))
        dao.upsertMediaAsset(MediaAssetEntity(
            "asset-remote", "canonical-a", remote, "server-song-900",
            "opensubsonic://asset/900", null, null, null, "opensubsonic://artwork/900",
        ))
        dao.upsertMediaAsset(MediaAssetEntity(
            "asset-same-title", "canonical-b", remote, "server-song-901",
            "opensubsonic://asset/901", null, null, null, null,
        ))
    }

    @Test fun canonicalRecordingGroupsOnlyItsOwnDistinctAssetsNotMatchingNames() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val repo = CanonicalMultiSourceBrowseRepository(RoomCanonicalMultiSourceBrowseStore(db.catalogDao()))
            val model = repo.observe(setOf(remote, local)).first()
            assertEquals(listOf("canonical-a", "canonical-b"), model.tracks.map { it.recordingId })
            val a = model.tracks.first()
            assertEquals(setOf("asset-local", "asset-remote"), a.availableAssets.map { it.mediaAssetId }.toSet())
            assertEquals(setOf(local, remote), a.availableAssets.map { it.providerKey }.toSet())
            assertEquals(listOf("asset-same-title"), model.tracks.last().availableAssets.map { it.mediaAssetId })
            assertTrue(model.tracks.none { it.recordingId == "server-song-900" })
            assertTrue(model.tracks.none { it.recordingId == "mediastore-native-55" })
        } finally { db.close() }
    }

    @Test fun sourceFilterRemovalAndDiskReopenAreDeterministic() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val name = "c7-multi-" + System.nanoTime() + ".db"
        var db = Room.databaseBuilder(ctx, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            seed(db)
            val before = CanonicalMultiSourceBrowseRepository(
                RoomCanonicalMultiSourceBrowseStore(db.catalogDao()))
                .observe(setOf(local, remote)).first()
            db.close()
            db = Room.databaseBuilder(ctx, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            val after = CanonicalMultiSourceBrowseRepository(
                RoomCanonicalMultiSourceBrowseStore(db.catalogDao()))
            assertEquals(before, after.observe(setOf(remote, local, local)).first())
            assertEquals(listOf("canonical-a"), after.observe(setOf(local)).first().tracks.map { it.recordingId })
            assertEquals(2, after.observe(setOf(remote)).first().tracks.size)
            assertEquals(emptyList<String>(), after.observe(emptySet()).first().tracks.map { it.recordingId })
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }

    @Test fun invalidProviderKeyFailsClosed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = CanonicalMultiSourceBrowseRepository(
                RoomCanonicalMultiSourceBrowseStore(db.catalogDao()))
            try {
                repo.observe(setOf(" "))
                throw AssertionError("Blank provider accepted")
            } catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }
}

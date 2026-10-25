package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalMultiSourceBrowseRepository
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RoomCanonicalMultiSourceBrowseStore
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzCatalogIndexTest {
    @Test
    fun knownRecordingPersistsWithoutFabricatingAssetsAndLaterRemoteAssetReusesIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var counter = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "known-${++counter}" })
            val original = MusicBrainzRecordingHit(
                "e614e432-ca77-41c1-82c1-4e3a24ebbb0c", "Magnetic", "ILLIT", 160_000L,
            )
            val recordingId = index.persistRecordings(listOf(original))[original.musicBrainzId]!!
            assertEquals("known-1", recordingId)
            assertEquals("Magnetic", db.catalogDao().recordingById(recordingId)?.title)
            assertEquals(
                recordingId,
                db.catalogDao().entityIdForExternalIdentity("musicbrainz", "recording", original.musicBrainzId),
            )

            val browse = CanonicalMultiSourceBrowseRepository(RoomCanonicalMultiSourceBrowseStore(db.catalogDao()))
            assertEquals(emptyList<String>(), browse.observe(setOf("opensubsonic:home")).first().tracks.map { it.recordingId })

            val repeated = original.copy(title = "Untrusted later spelling")
            assertEquals(recordingId, index.persistRecordings(listOf(repeated))[original.musicBrainzId])
            assertEquals("Magnetic", db.catalogDao().recordingById(recordingId)?.title)
            assertEquals(1, counter)
            assertEquals(
                listOf(original.musicBrainzId),
                db.catalogDao().searchKnownMusicBrainzRecordings("magnetic", 25)
                    .map { it.musicBrainzId },
            )
            assertEquals(
                emptyList<String>(),
                db.catalogDao().searchKnownMusicBrainzRecordings("Mag%", 25)
                    .map { it.musicBrainzId },
            )

            db.catalogDao().upsertMediaAsset(MediaAssetEntity(
                "asset-home", recordingId, "opensubsonic:home", "native-888",
                "opensubsonic://internal", null, null, null, null,
            ))
            val available = browse.observe(setOf("opensubsonic:home")).first().tracks
            assertEquals(listOf(recordingId), available.map { it.recordingId })
            assertEquals(listOf("asset-home"), available.single().availableAssets.map { it.mediaAssetId })
            assertEquals(
                listOf(original.musicBrainzId),
                db.catalogDao().musicBrainzRecordingMbidsWithAssets(listOf(original.musicBrainzId)),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun sameTitleAndArtistNeverMergeDistinctMusicBrainzRecordings() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var counter = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "known-${++counter}" })
            val normal = MusicBrainzRecordingHit(
                "e614e432-ca77-41c1-82c1-4e3a24ebbb0c", "Magnetic", "ILLIT", 160000L,
            )
            val spedUp = MusicBrainzRecordingHit(
                "3e619ba2-34f0-4649-83d8-05483e2fabe5", "Magnetic", "ILLIT", 124000L,
            )
            val ids = index.persistRecordings(listOf(normal, spedUp))
            assertNotEquals(ids[normal.musicBrainzId], ids[spedUp.musicBrainzId])
            assertEquals(2, counter)
            assertTrue(ids.values.all { db.catalogDao().recordingById(it) != null })
        } finally {
            db.close()
        }
    }

    @Test
    fun invalidMbidCannotBeClaimedByPublicIndex() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val index = MusicBrainzCatalogIndex(db, idFactory = { "unexpected" })
            assertTrue(index.persistRecordings(listOf(
                MusicBrainzRecordingHit("not-an-mbid", "Magnetic", "ILLIT", null),
            )).isEmpty())
            assertNull(db.catalogDao().recordingById("unexpected"))
        } finally {
            db.close()
        }
    }
}

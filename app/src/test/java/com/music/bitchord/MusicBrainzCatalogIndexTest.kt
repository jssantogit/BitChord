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
    fun homeCatalogPrioritizesActualMediaAssetsBeforeMetadataOnlyRecordings() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var serial = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "recording-${++serial}" })
            val metadataOnly = MusicBrainzRecordingHit(
                "e614e432-ca77-41c1-82c1-4e3a24ebbb0c", "A metadata-only song", "A artist", 160000,
            )
            val playable = MusicBrainzRecordingHit(
                "3e619ba2-34f0-4649-83d8-05483e2fabe5", "Z stored song", "Z artist", 124000,
            )
            val identities = index.persistRecordings(listOf(metadataOnly, playable))
            val dao = db.catalogDao()
            assertEquals(metadataOnly.musicBrainzId, dao.searchKnownMusicBrainzRecordings("", 1)
                .single().musicBrainzId)
            dao.upsertMediaAsset(MediaAssetEntity(
                "asset-z", identities[playable.musicBrainzId]!!, "local-mediastore", "store-123",
                "content://media/external/audio/media/123", null, null, null, null,
            ))
            assertEquals(playable.musicBrainzId, dao.searchKnownMusicBrainzRecordings("", 1)
                .single().musicBrainzId)
            assertEquals(2, dao.searchKnownMusicBrainzRecordings("", 20).size)
        } finally { db.close() }
    }

    @Test
    fun musicBrainzArtistNameIsSearchableWithoutOverwritingLocalArtistAlias() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val mbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            val ids = MusicBrainzCatalogIndex(db).persistArtists(listOf(
                com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit(
                    mbid, "ILLIT", "South Korean girl group", "KR",
                ),
            ))
            val artistId = ids[mbid]!!
            db.catalogDao().upsertArtist(com.music.bitchord.data.catalog.ArtistEntity(
                artistId, "My local alias",
            ))
            assertEquals("My local alias", db.catalogDao().artistById(artistId)!!.name)
            val remote = db.catalogDao().searchKnownMusicBrainzArtists("ILLIT", 10).single()
            assertEquals("ILLIT", remote.name)
            assertEquals(mbid, remote.musicBrainzId)
            assertEquals("South Korean girl group", remote.disambiguation)
        } finally { db.close() }
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

package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CatalogMusicBrainzTrackEntity
import com.music.bitchord.data.catalog.CatalogReleaseGroupEditionEntity
import com.music.bitchord.data.catalog.CatalogReleaseGroupEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzReleaseGraphRoomTest {
    @Test fun editionsAndTrackMbidsRemainDistinctWhileRecordingsCanBeShared() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val group = "group"
            dao.upsertCatalogReleaseGroup(CatalogReleaseGroupEntity(group, "SUPER REAL ME", "ILLIT", "EP", "2024-03-25"))
            dao.upsertRecording(RecordingEntity("original", "Magnetic", "ILLIT", 160_000))
            dao.upsertRecording(RecordingEntity("sped", "Magnetic (sped up)", "ILLIT", 124_000))
            for (edition in listOf("worldwide", "japan")) {
                dao.upsertRelease(ReleaseEntity(edition, "SUPER REAL ME", "ILLIT", null))
                dao.upsertCatalogReleaseGroupEdition(CatalogReleaseGroupEditionEntity(group, edition))
            }
            dao.upsertCatalogMusicBrainzTrack(CatalogMusicBrainzTrackEntity(
                trackMbid = "14f273b6-813b-4239-b0f8-c4b839b902c6",
                releaseId = "worldwide", recordingId = "original",
                mediumNumber = 1, trackNumber = 2, title = "Magnetic", durationMs = 160_000,
            ))
            dao.upsertCatalogMusicBrainzTrack(CatalogMusicBrainzTrackEntity(
                trackMbid = "bce9c8f8-b7a6-4427-9f46-97e88f8ae5f7",
                releaseId = "japan", recordingId = "original",
                mediumNumber = 1, trackNumber = 2, title = "Magnetic", durationMs = 160_000,
            ))
            dao.upsertCatalogMusicBrainzTrack(CatalogMusicBrainzTrackEntity(
                trackMbid = "7d950448-969b-4d73-a00f-fec131238b5d",
                releaseId = "worldwide", recordingId = "sped",
                mediumNumber = 1, trackNumber = 8, title = "Magnetic (sped up)", durationMs = 124_000,
            ))

            assertEquals(listOf("japan", "worldwide"),
                dao.catalogGroupEditions(group).map { it.releaseId })
            val worldwide = dao.catalogMusicBrainzTracks("worldwide")
            assertEquals(listOf(2, 8), worldwide.map { it.trackNumber })
            assertNotEquals(worldwide[0].trackMbid, worldwide[1].trackMbid)
            assertEquals("original", dao.catalogMusicBrainzTracks("japan").single().recordingId)
            assertEquals("original", worldwide.first().recordingId)
            assertEquals("sped", worldwide.last().recordingId)
        } finally {
            db.close()
        }
    }
    @Test fun cachedEditionPreservesExplicitOfficialStatusAndSparseSearchCannotEraseIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var next = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "edition-test-${++next}" })
            val groupMbid = "11111111-1111-4111-8111-111111111111"
            val editionMbid = "22222222-2222-4222-8222-222222222222"
            index.persistReleaseGroups(listOf(MusicBrainzReleaseGroupHit(
                groupMbid, "Get Up", "EP", "2023-07-21", "NewJeans",
            )))
            val official = MusicBrainzReleaseHit(
                editionMbid, "Get Up", "XW", "2023-07-21", "NewJeans", status = "Official",
            )
            index.persistGroupEditions(groupMbid, listOf(official))
            assertEquals("Official", index.cachedGroupReleases(groupMbid).single().status)
            // Later sparse search results omit status; retain positive evidence.
            index.persistReleases(listOf(official.copy(status = null)))
            assertEquals("Official", index.cachedGroupReleases(groupMbid).single().status)
        } finally { db.close() }
    }

}

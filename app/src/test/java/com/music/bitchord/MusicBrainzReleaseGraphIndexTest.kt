package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzReleaseGraphIndexTest {
    @Test fun releaseGroupAndEditionsArePersistedWithoutAudioOrTrackIdentityCollisions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var serial = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "local-${++serial}" })
            val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
            val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val worldwide = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val japan = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
            val details = listOf(
                MusicBrainzCatalogReleaseDetail(worldwide, group, "SUPER REAL ME", "ILLIT", "XW", "2024-03-25",
                    listOf(MusicBrainzCatalogTrack("14f273b6-813b-4239-b0f8-c4b839b902c6",recording,"Magnetic",1,2,160000))),
                MusicBrainzCatalogReleaseDetail(japan, group, "SUPER REAL ME", "ILLIT", "JP", "2024-03-25",
                    listOf(MusicBrainzCatalogTrack("bce9c8f8-b7a6-4427-9f46-97e88f8ae5f7",recording,"Magnetic",1,2,160000))),
            )
            val r1 = index.persistReleaseDetail(details[0])!!
            val r2 = index.persistReleaseDetail(details[1])!!
            assertNotEquals(r1, r2)
            val dao = db.catalogDao()
            val groupId = dao.entityIdForExternalIdentity("musicbrainz", "release-group", group)!!
            assertEquals(setOf(r1,r2), dao.catalogGroupEditions(groupId).map { it.releaseId }.toSet())
            assertEquals("14f273b6-813b-4239-b0f8-c4b839b902c6",
                dao.catalogMusicBrainzTracks(r1).single().trackMbid)
            assertEquals("bce9c8f8-b7a6-4427-9f46-97e88f8ae5f7",
                dao.catalogMusicBrainzTracks(r2).single().trackMbid)
            assertEquals(dao.catalogMusicBrainzTracks(r1).single().recordingId,
                dao.catalogMusicBrainzTracks(r2).single().recordingId)
            assertEquals(4, serial) // 1 Recording + 1 ReleaseGroup + 2 Releases; no reclaims
            assertEquals(r1, index.persistReleaseDetail(details[0]))
            assertEquals(emptyList<String>(), dao.musicBrainzRecordingMbidsWithAssets(listOf(recording)))
        } finally { db.close() }
    }
}

package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
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
    @Test fun recordingMetadataComesFromNestedRecordingWhileTrackKeepsEditionFields() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val track = "14f273b6-813b-4239-b0f8-c4b839b902c6"
            val graph = MusicBrainzCatalogGraph(MusicBrainzClient(transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String,
                    parameters: Map<String, String>,
                    userAgent: String,
                ): String = """{"id":"$release","title":"SUPER REAL ME","media":[{"position":1,"tracks":[
                    {"id":"$track","position":2,"title":"Magnetic","length":160000,
                     "recording":{"id":"$recording","title":"Magnetic (sped up)","length":124000,
                     "artist-credit":[{"name":"ILLIT"}]}}
                ]}]}"""
            }))
            val detail = (graph.lookupRelease(release) as
                MusicBrainzCatalogGraphResult.Success<MusicBrainzCatalogReleaseDetail>).value
            assertEquals("Magnetic", detail.tracks.single().title)
            assertEquals(160_000L, detail.tracks.single().durationMs)

            val index = MusicBrainzCatalogIndex(db)
            index.persistReleaseDetail(detail)
            val recordingId = db.catalogDao().entityIdForExternalIdentity(
                "musicbrainz", "recording", recording,
            )!!
            val cachedRecording = db.catalogDao().recordingById(recordingId)!!

            assertEquals("Magnetic (sped up)", cachedRecording.title)
            assertEquals(124_000L, cachedRecording.durationMs)
            assertEquals("Magnetic", db.catalogDao().catalogMusicBrainzTracks(
                db.catalogDao().entityIdForExternalIdentity("musicbrainz", "release", release)!!,
            ).single().title)
            val cachedEdition = index.cachedRelease(release)!!
            assertEquals("Magnetic", cachedEdition.tracks.single().title)
            assertEquals(160_000L, cachedEdition.tracks.single().durationMs)
            assertEquals("Magnetic (sped up)", cachedEdition.tracks.single().recordingTitle)
            assertEquals(124_000L, cachedEdition.tracks.single().recordingDurationMs)
        } finally { db.close() }
    }

    @Test fun cachedRecordingEvidenceDoesNotOverwriteExistingCanonicalLocalMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
            val index = MusicBrainzCatalogIndex(db)
            val id = index.persistRecordings(listOf(
                com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit(
                    recording, "Preserved local title", "Local credits", 99_000L,
                ),
            ))[recording]!!
            val detail = MusicBrainzCatalogReleaseDetail(release, null, "SUPER REAL ME", "ILLIT",
                "XW", "2024-03-25", listOf(
                    MusicBrainzCatalogTrack(
                        trackMbid = "14f273b6-813b-4239-b0f8-c4b839b902c6",
                        recordingMbid = recording,
                        title = "Magnetic (edition)",
                        mediumNumber = 1, trackNumber = 2, durationMs = 160_000L,
                        recordingTitle = "Magnetic (sped up)",
                        recordingDurationMs = 123_000L,
                    ),
                ),
            )
            index.persistReleaseDetail(detail)
            assertEquals("Preserved local title", db.catalogDao().recordingById(id)!!.title)
            assertEquals(99_000L, db.catalogDao().recordingById(id)!!.durationMs)
            val cached = index.cachedRelease(release)!!.tracks.single()
            assertEquals("Magnetic (edition)", cached.title)
            assertEquals(160_000L, cached.durationMs)
            assertEquals("Magnetic (sped up)", cached.recordingTitle)
            assertEquals(123_000L, cached.recordingDurationMs)
        } finally { db.close() }
    }

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
                    listOf(MusicBrainzCatalogTrack("14f273b6-813b-4239-b0f8-c4b839b902c6",recording,"Magnetic",1,2,160000,
                        artistDisplay = "ILLIT feat. Guest", recordingArtistDisplay = "ILLIT"))),
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
            val canonicalRecordingId = dao.catalogMusicBrainzTracks(r1).single().recordingId
            assertEquals("ILLIT", dao.recordingById(canonicalRecordingId)?.artistDisplay)
            assertEquals("ILLIT feat. Guest", index.cachedRelease(worldwide)!!.tracks.single().artistDisplay)
            assertEquals(emptyList<String>(), dao.musicBrainzRecordingMbidsWithAssets(listOf(recording)))
        } finally { db.close() }
    }
}

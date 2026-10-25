package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzOfflineEditionsTest {
    private val worldwide = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val japan = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val track = "14f273b6-813b-4239-b0f8-c4b839b902c6"
    private val japanTrack = "a5f07fd0-c0bd-4784-b074-8508ccbb5639"

    @Test fun editionSearchRetainsTerritoryAndDateWithoutInventingTracklistOrAudio() = runBlocking {
        val db = database()
        try {
            val index = MusicBrainzCatalogIndex(db)
            val hits = listOf(
                MusicBrainzReleaseHit(worldwide, "SUPER REAL ME", "XW", "2024-03-25", "ILLIT"),
                MusicBrainzReleaseHit(japan, "SUPER REAL ME", "JP", "2024-03-25", "ILLIT"),
            )
            val ids = index.persistReleases(hits)
            assertEquals(2, ids.values.toSet().size)
            assertEquals(hits.toSet(), db.catalogDao().searchKnownMusicBrainzReleases("ILLIT", 25).toSet())
            assertNull(index.cachedRelease(worldwide))
            assertTrue(db.catalogDao().musicBrainzRecordingMbidsWithAssets(listOf(recording)).isEmpty())
            index.persistReleases(listOf(hits[0].copy(country = null, releaseDate = null)))
            assertEquals("XW", db.catalogDao().searchKnownMusicBrainzReleases("SUPER", 25)
                .single { it.musicBrainzId == worldwide }.country)
        } finally { db.close() }
    }

    @Test fun cachedGraphKeepsEditionTrackIdentityAndNeverPrunesOnSparseBrowse() = runBlocking {
        val db = database()
        try {
            val index = MusicBrainzCatalogIndex(db)
            val detail = MusicBrainzCatalogReleaseDetail(worldwide, group, "SUPER REAL ME",
                "ILLIT", "XW", "2024-03-25", listOf(
                    MusicBrainzCatalogTrack(track, recording, "Magnetic", 1, 2, 160000, "ILLIT"),
                ))
            index.persistReleaseDetail(detail)
            val complete = detail.copy(tracks = detail.tracks.map {
                it.copy(recordingTitle = it.title, recordingDurationMs = it.durationMs)
            })
            assertEquals(complete, index.cachedRelease(worldwide))
            index.persistGroupEditions(group, emptyList())
            assertEquals(listOf(worldwide), index.cachedGroupReleases(group).map { it.musicBrainzId })
            assertEquals(complete, index.cachedRelease(worldwide))
            assertTrue(db.catalogDao().musicBrainzRecordingMbidsWithAssets(listOf(recording)).isEmpty())
            assertNull(index.cachedRelease("not-an-mbid"))
        } finally { db.close() }
    }

    @Test fun offlineEditionTrackDoesNotBorrowAnotherEditionsCreditForTheSameRecording() = runBlocking {
        val db = database()
        try {
            val index = MusicBrainzCatalogIndex(db)
            index.persistReleaseDetail(MusicBrainzCatalogReleaseDetail(
                worldwide, group, "SUPER REAL ME", "ILLIT", "XW", "2024-03-25", listOf(
                    MusicBrainzCatalogTrack(
                        track, recording, "Magnetic", 1, 2, 160000,
                        artistDisplay = "Edition One credit",
                        recordingArtistDisplay = "Recording credit",
                    ),
                ),
            ))
            index.persistReleaseDetail(MusicBrainzCatalogReleaseDetail(
                japan, group, "SUPER REAL ME", "ILLIT", "JP", "2024-03-25", listOf(
                    MusicBrainzCatalogTrack(
                        japanTrack, recording, "Magnetic", 1, 2, 160000,
                        artistDisplay = "Edition Two credit",
                        recordingArtistDisplay = "Recording credit",
                    ),
                ),
            ))

            val worldwideTrack = index.cachedRelease(worldwide)!!.tracks.single()
            val japanEditionTrack = index.cachedRelease(japan)!!.tracks.single()

            assertEquals("Edition One credit", worldwideTrack.artistDisplay)
            assertEquals("Edition Two credit", japanEditionTrack.artistDisplay)
            val recordingId = db.catalogDao().entityIdForExternalIdentity(
                "musicbrainz", "recording", recording,
            )!!
            assertEquals("Recording credit", db.catalogDao().recordingById(recordingId)!!.artistDisplay)
        } finally { db.close() }
    }

    @Test fun paginatedEditionAssociationsAccumulateAndRemainAvailableOffline() = runBlocking {
        val db = database()
        try {
            val index = MusicBrainzCatalogIndex(db)
            index.persistReleaseGroups(listOf(MusicBrainzReleaseGroupHit(
                group, "SUPER REAL ME", "EP", "2024-03-25", "ILLIT",
            )))
            val pageOne = MusicBrainzReleaseHit(worldwide, "SUPER REAL ME", "XW", "2024-03-25", "ILLIT")
            val pageTwo = MusicBrainzReleaseHit(japan, "SUPER REAL ME", "JP", "2024-03-25", "ILLIT")
            index.persistGroupEditions(group, listOf(pageOne))
            index.persistGroupEditions(group, listOf(pageTwo))
            index.persistGroupEditions(group, emptyList())
            assertEquals(setOf(pageOne, pageTwo), index.cachedGroupReleases(group).toSet())
        } finally { db.close() }
    }

    @Test fun artistDiscographyCacheIsBoundToArtistMbidNotHomonymousArtistName() = runBlocking {
        val db = database()
        try {
            val index = MusicBrainzCatalogIndex(db)
            val illit = "6b128dd7-e998-4edb-a4f5-400fef190445"
            val namesake = "fa741e5c-1a5d-4a7a-a041-9d8061bd18fb"
            val anotherGroup = "6cdf5ba6-70f1-4bf6-82b6-f9c821a7ec5a"
            index.persistArtists(listOf(
                MusicBrainzArtistHit(illit, "ILLIT", "Korean group", "KR"),
                MusicBrainzArtistHit(namesake, "ILLIT", "Different artist", "US"),
            ))
            val first = MusicBrainzReleaseGroupHit(group, "SUPER REAL ME", "EP", "2024-03-25", "ILLIT")
            val second = MusicBrainzReleaseGroupHit(anotherGroup, "Namesake release", "Album", null, "ILLIT")
            index.persistArtistGroups(illit, listOf(first))
            index.persistArtistGroups(namesake, listOf(second))

            assertEquals(listOf(first), index.cachedArtistGroups(illit))
            assertEquals(listOf(second), index.cachedArtistGroups(namesake))
            index.persistArtistGroups(illit, emptyList())
            assertEquals(listOf(first), index.cachedArtistGroups(illit))
            assertTrue(index.cachedArtistGroups("not-a-valid-mbid").isEmpty())
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
    ).allowMainThreadQueries().build()
}

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
            assertEquals(detail, index.cachedRelease(worldwide))
            index.persistGroupEditions(group, emptyList())
            assertEquals(listOf(worldwide), index.cachedGroupReleases(group).map { it.musicBrainzId })
            assertEquals(detail, index.cachedRelease(worldwide))
            assertTrue(db.catalogDao().musicBrainzRecordingMbidsWithAssets(listOf(recording)).isEmpty())
            assertNull(index.cachedRelease("not-an-mbid"))
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
    ).allowMainThreadQueries().build()
}

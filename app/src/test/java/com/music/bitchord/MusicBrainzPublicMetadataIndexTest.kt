package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
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
class MusicBrainzPublicMetadataIndexTest {
    @Test fun artistAndReleaseGroupAreSearchableOfflineByStrongMbidsOnly() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var id = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "generated-${++id}" })
            val artistMbid = "b51c672b-85e0-48fe-8648-470a2422229f"
            val groupMbid = "529f87a1-1081-48a4-8ee1-3c08de229858"
            val artist = MusicBrainzArtistHit(artistMbid, "aespa", "South Korean girl group", "KR")
            val group = MusicBrainzReleaseGroupHit(groupMbid, "SUPER REAL ME", "EP", "2024-03-25", "ILLIT")
            val artists = index.persistArtists(listOf(artist))
            val groups = index.persistReleaseGroups(listOf(group))
            assertNotEquals(artists[artistMbid], groups[groupMbid])
            assertEquals(2, id)
            assertEquals(artistMbid, db.catalogDao().searchKnownMusicBrainzArtists("AESPA", 20)
                .single().musicBrainzId)
            assertEquals(groupMbid, db.catalogDao().searchKnownMusicBrainzGroups("real", 20)
                .single().musicBrainzId)
            assertEquals("EP", db.catalogDao().catalogReleaseGroup(groups[groupMbid]!!)?.primaryType)
            assertEquals(groups, index.persistReleaseGroups(listOf(group.copy(title = "Untrusted synonym"))))
            assertEquals(2, id)
            assertEquals(emptyList<String>(), db.catalogDao().searchKnownMusicBrainzGroups("REAL%", 20)
                .map { it.musicBrainzId })
        } finally { db.close() }
    }
}

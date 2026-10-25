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
    @Test fun offlineArtistsWithSameNameKeepDistinctMbidsAndDisambiguation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var count = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "artist-${++count}" })
            val korean = "b51c672b-85e0-48fe-8648-470a2422229f"
            val namesake = "11111111-2222-3333-4444-555555555555"
            index.persistArtists(listOf(
                MusicBrainzArtistHit(korean, "aespa", "South Korean girl group", "KR"),
                MusicBrainzArtistHit(namesake, "aespa", "Unrelated namesake", "US"),
            ))
            val found = db.catalogDao().searchKnownMusicBrainzArtists("aespa", 25)
                .associateBy { it.musicBrainzId }
            assertEquals(2, found.size)
            assertEquals("South Korean girl group", found[korean]?.disambiguation)
            assertEquals("KR", found[korean]?.country)
            assertEquals("Unrelated namesake", found[namesake]?.disambiguation)
            // A later sparse hit does not clear the earlier trustworthy text.
            index.persistArtists(listOf(MusicBrainzArtistHit(korean, "aespa", null, null)))
            val restored = db.catalogDao().searchKnownMusicBrainzArtists("aespa", 25)
                .associateBy { it.musicBrainzId }
            assertEquals("South Korean girl group", restored[korean]?.disambiguation)
            assertEquals("KR", restored[korean]?.country)
            assertEquals(2, count)
        } finally { db.close() }
    }

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

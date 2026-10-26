package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MusicBrainzCoverArtCacheEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
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
            val group = MusicBrainzReleaseGroupHit(groupMbid, "SUPER REAL ME", "EP", "2024-03-25", "ILLIT",
                secondaryTypes = "Remix|Compilation")
            val artists = index.persistArtists(listOf(artist))
            val groups = index.persistReleaseGroups(listOf(group))
            assertNotEquals(artists[artistMbid], groups[groupMbid])
            assertEquals(2, id)
            assertEquals(artistMbid, db.catalogDao().searchKnownMusicBrainzArtists("AESPA", 20)
                .single().musicBrainzId)
            assertEquals(groupMbid, db.catalogDao().searchKnownMusicBrainzGroups("real", 20)
                .single().musicBrainzId)
            assertEquals("EP", db.catalogDao().catalogReleaseGroup(groups[groupMbid]!!)?.primaryType)
            assertEquals("Remix|Compilation",
                db.catalogDao().catalogReleaseGroup(groups[groupMbid]!!)?.secondaryTypes)
            assertEquals("Remix|Compilation",
                db.catalogDao().searchKnownMusicBrainzGroups("real", 20).single().secondaryTypes)
            index.persistReleaseGroups(listOf(group.copy(primaryType = null, secondaryTypes = null)))
            assertEquals("Remix|Compilation",
                db.catalogDao().catalogReleaseGroup(groups[groupMbid]!!)?.secondaryTypes)
            assertEquals(groups, index.persistReleaseGroups(listOf(group.copy(title = "Untrusted synonym"))))
            assertEquals(2, id)
            assertEquals(emptyList<String>(), db.catalogDao().searchKnownMusicBrainzGroups("REAL%", 20)
                .map { it.musicBrainzId })
        } finally { db.close() }
    }
    @Test fun offlineReleaseCoversUseValidatedFrontV2AndNeverSurfaceLegacyPoster() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var id = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "front-test-${++id}" })
            val groupId = "22222222-2222-4222-8222-222222222222"
            val editionId = "33333333-3333-4333-8333-333333333333"
            index.persistReleaseGroups(listOf(MusicBrainzReleaseGroupHit(
                groupId, "New Jeans", "EP", "2022-08-01", "NewJeans",
            )))
            index.persistReleases(listOf(MusicBrainzReleaseHit(
                editionId, "New Jeans", "KR", "2022-08-01", "NewJeans",
            )))
            val dao = db.catalogDao()
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group", groupId, "https://archive.org/legacy-poster.jpg", 1000L,
            ))
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release", editionId, "https://archive.org/legacy-video.jpg", 1000L,
            ))
            assertEquals(null, dao.searchKnownMusicBrainzGroups("New Jeans", 10).single().artworkUri)
            assertEquals(null, dao.searchKnownMusicBrainzReleases("New Jeans", 10).single().artworkUri)
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v2", groupId, "https://archive.org/verified-group-front.jpg",
                2000L, thumbnailUri = "https://archive.org/verified-group-thumb-500.jpg",
            ))
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release:front-v2", editionId, "https://archive.org/verified-release-front.jpg", 2000L,
            ))
            assertEquals("https://archive.org/verified-group-thumb-500.jpg",
                dao.searchKnownMusicBrainzGroups("New Jeans", 10).single().artworkUri)
            assertEquals("https://archive.org/verified-group-front.jpg",
                dao.musicBrainzCoverArtCache("release-group:front-v2", groupId)?.artworkUri)
            assertEquals("https://archive.org/verified-release-front.jpg",
                dao.searchKnownMusicBrainzReleases("New Jeans", 10).single().artworkUri)
        } finally { db.close() }
    }

    @Test fun cachedArtistDiscographyReusesVerifiedCoverWithoutRequestingCAAAgain() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var number = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "artist-card-${++number}" })
            val artistMbid = "b51c672b-85e0-48fe-8648-470a2422229f"
            val groupMbid = "529f87a1-1081-48a4-8ee1-3c08de229858"
            index.persistArtists(listOf(MusicBrainzArtistHit(
                artistMbid, "aespa", "South Korean group", "KR")))
            index.persistArtistGroups(artistMbid, listOf(MusicBrainzReleaseGroupHit(
                groupMbid, "SUPER REAL ME", "EP", "2024-03-25", "aespa",
                secondaryTypes = "Remix",
            )))
            db.catalogDao().upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v2", groupMbid,
                "https://archive.org/verified-album-1200.jpg", 20_000L,
                thumbnailUri = "https://archive.org/verified-album-500.jpg",
            ))
            val cached = index.cachedArtistGroups(artistMbid).single()
            assertEquals(groupMbid, cached.musicBrainzId)
            assertEquals("Remix", cached.secondaryTypes)
            assertEquals("https://archive.org/verified-album-500.jpg", cached.artworkUri)
        } finally { db.close() }
    }

}

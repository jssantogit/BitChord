package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MusicBrainzCoverArtCacheEntity
import com.music.bitchord.data.catalog.CatalogMusicBrainzArtistGroupEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzRecordingHit
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRecording
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
    @Test fun offlineReleaseCoversUseStrictFrontV3AndNeverSurfaceLegacyPoster() = runBlocking {
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
                "release-group:front-v3", groupId, "https://archive.org/stale-unverified-video.jpg",
                1000L, thumbnailUri = "https://archive.org/stale-poster-thumb.jpg",
            ))
            assertEquals(null, dao.searchKnownMusicBrainzGroups("New Jeans", 10).single().artworkUri)
            assertEquals(emptyList<MusicBrainzCoverArtCacheEntity>(),
                dao.verifiedGroupFronts(listOf(groupId)))
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v4", groupId, "https://archive.org/verified-group-front.jpg",
                2000L, thumbnailUri = "https://archive.org/verified-group-thumb-500.jpg",
            ))
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release:front-v2", editionId, "https://archive.org/previous-front-video.jpg", 1000L,
            ))
            assertEquals(null, dao.searchKnownMusicBrainzReleases("New Jeans", 10)
                .single().artworkUri)
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release:front-v3", editionId, "https://archive.org/verified-release-front.jpg", 2000L,
            ))
            assertEquals("https://archive.org/verified-group-thumb-500.jpg",
                dao.searchKnownMusicBrainzGroups("New Jeans", 10).single().artworkUri)
            assertEquals("https://archive.org/verified-group-front.jpg",
                dao.musicBrainzCoverArtCache("release-group:front-v4", groupId)?.artworkUri)
            assertEquals("https://archive.org/verified-release-front.jpg",
                dao.searchKnownMusicBrainzReleases("New Jeans", 10).single().artworkUri)
        } finally { db.close() }
    }


    @Test fun legacyArtistMembershipCannotClaimOfficialDiscographyUntilWebsiteDefaultBrowse() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var seq = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "legacy-${++seq}" })
            val artistMbid = "b51c672b-85e0-48fe-8648-470a2422229f"
            val oldGroup = MusicBrainzReleaseGroupHit(
                "22222222-2222-4222-8222-222222222222",
                "Unverified old bootleg", "Album", "2020", "NewJeans")
            val newGroup = MusicBrainzReleaseGroupHit(
                "33333333-3333-4333-8333-333333333333",
                "Get Up", "EP", "2023-07-21", "NewJeans")
            val artistLocal = requireNotNull(index.persistArtists(listOf(
                MusicBrainzArtistHit(artistMbid, "NewJeans", null, "KR")
            ))[artistMbid])
            val groupLocal = requireNotNull(index.persistReleaseGroups(listOf(oldGroup))[
                oldGroup.musicBrainzId])
            db.catalogDao().upsertMusicBrainzArtistGroup(
                CatalogMusicBrainzArtistGroupEntity(artistLocal, groupLocal))
            assertEquals(emptyList<MusicBrainzReleaseGroupHit>(),
                index.cachedArtistGroups(artistMbid))
            assertEquals(1, db.catalogDao().musicBrainzArtistGroupMembershipCount(artistLocal))

            // Only a browse through artist MBID with the website-default
            // status filter may promote groups into official discography.
            index.persistArtistGroups(artistMbid, listOf(newGroup))
            assertEquals(listOf("Get Up"), index.cachedArtistGroups(artistMbid).map { it.title })
            assertEquals(2, db.catalogDao().musicBrainzArtistGroupMembershipCount(artistLocal))
            index.persistArtistGroups(artistMbid, listOf(oldGroup))
            assertEquals(setOf("Get Up", "Unverified old bootleg"),
                index.cachedArtistGroups(artistMbid).map { it.title }.toSet())
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
            val artistIds = index.persistArtists(listOf(MusicBrainzArtistHit(
                artistMbid, "aespa", "South Korean group", "KR")))
            index.persistArtistGroups(artistMbid, listOf(MusicBrainzReleaseGroupHit(
                groupMbid, "SUPER REAL ME", "EP", "2024-03-25", "aespa",
                secondaryTypes = "Remix",
            )))
            db.catalogDao().upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v3", groupMbid,
                "https://archive.org/stale-direct-shortcut.jpg", 10_000L,
                thumbnailUri = "https://archive.org/stale-direct-500.jpg",
            ))
            assertEquals(null, index.cachedArtistGroups(artistMbid).single().artworkUri)
            db.catalogDao().upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v4", groupMbid,
                "https://archive.org/verified-album-1200.jpg", 20_000L,
                thumbnailUri = "https://archive.org/verified-album-500.jpg",
            ))
            val joined = db.catalogDao().verifiedArtistDiscographyRows(
                requireNotNull(artistIds[artistMbid])).single()
            assertEquals(groupMbid, joined.musicBrainzId)
            assertEquals("https://archive.org/verified-album-500.jpg", joined.artworkUri)
            assertEquals("Remix", joined.secondaryTypes)
            val cached = index.cachedArtistGroups(artistMbid).single()
            assertEquals(groupMbid, cached.musicBrainzId)
            assertEquals("Remix", cached.secondaryTypes)
            assertEquals("https://archive.org/verified-album-500.jpg", cached.artworkUri)
        } finally { db.close() }
    }

    @Test fun offlineCuratedRecordingsRequireVerifiedAudioEvidenceAndRejectLegacyVideos() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var next = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "audio-${++next}" })
            val valid = MusicBrainzRecordingHit(
                "11111111-1111-4111-8111-111111111111", "Hurt", "NewJeans", 180000,
                hasOfficialAudioRelease = true, isVideo = false,
            )
            val video = valid.copy(
                musicBrainzId = "22222222-2222-4222-8222-222222222222",
                isVideo = true,
            )
            val other = valid.copy(
                musicBrainzId = "33333333-3333-4333-8333-333333333333",
                hasOfficialAudioRelease = false,
            )
            val legacy = valid.copy(
                musicBrainzId = "44444444-4444-4444-8444-444444444444",
                hasOfficialAudioRelease = null, isVideo = null,
            )
            index.persistRecordings(listOf(valid, video, other, legacy))
            val raw = db.catalogDao().searchKnownMusicBrainzRecordings("Hurt", 20)
                .associateBy { it.musicBrainzId }
            assertEquals(4, raw.size)
            assertEquals(listOf(valid.musicBrainzId), raw.values
                .filter(::isCuratedMusicBrainzRecording)
                .map { it.musicBrainzId })
            // Sparse edition/index lookups cannot erase confirmed evidence.
            index.persistRecordings(listOf(valid.copy(
                hasOfficialAudioRelease = null, isVideo = null)))
            val again = db.catalogDao().searchKnownMusicBrainzRecordings("Hurt", 20)
                .associateBy { it.musicBrainzId }
            assertEquals(true, again[valid.musicBrainzId]?.hasOfficialAudioRelease)
            assertEquals(false, again[valid.musicBrainzId]?.isVideo)
            // A previously saved (unverified) recording can become discoverable
            // after a real MusicBrainz audio-release verification.
            index.persistRecordings(listOf(legacy.copy(
                hasOfficialAudioRelease = true, isVideo = false)))
            assertEquals(setOf(valid.musicBrainzId, legacy.musicBrainzId),
                db.catalogDao().searchKnownMusicBrainzRecordings("Hurt", 20)
                    .filter(::isCuratedMusicBrainzRecording)
                    .map { it.musicBrainzId }.toSet())
        } finally { db.close() }
    }


    @Test fun offlineDiscographyUsesDocumentedOfficialEditionDisplayLabelNotCompositeGroupTitle() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var id = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "offline-title-" + (++id) })
            val artist = "49204a7a-ed85-407a-828f-6fd46f1d8126"
            val group = "9750354e-78df-4058-8758-b5a7a2eccf66"
            index.persistArtists(listOf(MusicBrainzArtistHit(artist, "NewJeans", null, "KR")))
            index.persistArtistGroups(artist, listOf(
                MusicBrainzReleaseGroupHit(group, "How Sweet & Bubble Gum",
                    "Single", "2024-05-24", "NewJeans"),
            ))
            assertEquals("How Sweet & Bubble Gum", index.cachedArtistGroups(artist).single().title)
            index.persistGroupEditions(group, listOf(
                MusicBrainzReleaseHit("11111111-1111-4111-8111-111111111111",
                    "How Sweet", "XW", "2024-05-24", "NewJeans", status = "Official"),
                MusicBrainzReleaseHit("22222222-2222-4222-8222-222222222222",
                    "How Sweet (music video)", "KR", "2024-05-24", "NewJeans",
                    status = "Official"),
            ))
            assertEquals("How Sweet", index.cachedArtistGroups(artist).single().title)
            assertEquals("How Sweet", db.catalogDao().searchKnownMusicBrainzGroups(
                "How Sweet", 30,
            ).single { it.musicBrainzId == group }.title)
            // A later raw MB browse must not undo the edition-backed display label.
            index.persistArtistGroups(artist, listOf(
                MusicBrainzReleaseGroupHit(group, "How Sweet & Bubble Gum",
                    "Single", "2024-05-24", "NewJeans"),
            ))
            assertEquals("How Sweet", index.cachedArtistGroups(artist).single().title)
        } finally { db.close() }
    }

}

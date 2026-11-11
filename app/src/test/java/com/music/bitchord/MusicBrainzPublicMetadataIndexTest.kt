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

    @Test fun artistCorrectionsUpdateMusicBrainzNameButNeverUserCanonicalAlias() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val artist = "b51c672b-85e0-48fe-8648-470a2422229f"
            var created = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "artist-${++created}" })
            val before = MusicBrainzArtistHit(artist, "Original source name", "Old credit", "KR")
            val id = requireNotNull(index.persistArtists(listOf(before))[artist])
            db.catalogDao().upsertArtist(
                com.music.bitchord.data.catalog.ArtistEntity(id, "My local artist alias"))
            val corrected = before.copy(
                name = "Updated official artist", disambiguation = "Corrected profile",
                country = "JP",
            )
            assertEquals(id, index.persistArtists(listOf(corrected))[artist])
            val result = db.catalogDao().searchKnownMusicBrainzArtists("Updated official", 25)
                .single()
            assertEquals(artist, result.musicBrainzId)
            assertEquals("Updated official artist", result.name)
            assertEquals("Corrected profile", result.disambiguation)
            assertEquals("JP", result.country)
            assertEquals("My local artist alias", db.catalogDao().artistById(id)?.name)
            assertEquals(artist, db.catalogDao()
                .searchKnownMusicBrainzArtists("My local artist alias", 25)
                .single().musicBrainzId)
            // A later response without optional fields must preserve the proof.
            index.persistArtists(listOf(corrected.copy(disambiguation = null, country = null)))
            assertEquals("Corrected profile", db.catalogDao()
                .searchKnownMusicBrainzArtists("Updated official", 25)
                .single().disambiguation)
            assertEquals(1, created)
        } finally { db.close() }
    }

    @Test fun correctedGroupTitleAndArtistRefreshExactlyOneOfflineArtistDiscography() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val artist = "b51c672b-85e0-48fe-8648-470a2422229f"
            val group = "3f5eba8d-4dc1-4599-a936-21f501787f8e"
            var created = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "group-${++created}" })
            index.persistArtists(listOf(MusicBrainzArtistHit(artist, "aespa", null, "KR")))
            val first = MusicBrainzReleaseGroupHit(
                group, "Original group title", "EP", "2024-01-01", "Old group artist",
            )
            index.persistArtistGroups(artist, listOf(first))
            val groupId = db.catalogDao().entityIdForExternalIdentity(
                "musicbrainz", "release-group", group)!!
            val corrected = first.copy(
                title = "Updated album title", artistDisplay = "Correct artist",
                primaryType = "Album", firstReleaseDate = "2024-05-27",
            )
            index.persistReleaseGroups(listOf(corrected))
            val underlying = db.catalogDao().catalogReleaseGroup(groupId)!!
            assertEquals("Updated album title", underlying.title)
            assertEquals("Correct artist", underlying.artistDisplay)
            assertEquals("Album", underlying.primaryType)
            assertEquals("2024-05-27", underlying.firstReleaseDate)
            assertEquals(group, db.catalogDao().searchKnownMusicBrainzGroups("Updated", 25)
                .single().musicBrainzId)
            val discography = index.cachedArtistGroups(artist).single()
            assertEquals(group, discography.musicBrainzId)
            assertEquals("Updated album title", discography.title)
            assertEquals("Correct artist", discography.artistDisplay)
            // Missing optional type/date/credit must not regress known fields.
            index.persistReleaseGroups(listOf(corrected.copy(
                primaryType = null, firstReleaseDate = null, artistDisplay = null,
            )))
            assertEquals("Album", index.cachedArtistGroups(artist).single().primaryType)
            assertEquals("Correct artist", index.cachedArtistGroups(artist).single().artistDisplay)
            assertEquals(2, created) // Exactly one Artist and one ReleaseGroup identity.
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
            // The old v4 selection could borrow collector package artwork.
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v4", groupId, "https://archive.org/old-twice-package.jpg",
                1000L,
            ))
            assertEquals(null, dao.searchKnownMusicBrainzGroups("New Jeans", 10)
                .single().artworkUri)
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v5", groupId, "https://archive.org/verified-group-front.jpg",
                System.currentTimeMillis() - 1_000L,
                thumbnailUri = "https://archive.org/verified-group-thumb-500.jpg",
            ))
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release:front-v2", editionId, "https://archive.org/previous-front-video.jpg", 1000L,
            ))
            assertEquals(null, dao.searchKnownMusicBrainzReleases("New Jeans", 10)
                .single().artworkUri)
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release:front-v3", editionId, "https://archive.org/verified-release-front.jpg", 2000L,
            ))
            // Offline SQL cannot leak raw URLs without TTL/host validation.
            assertEquals(null, dao.searchKnownMusicBrainzGroups("New Jeans", 10)
                .single().artworkUri)
            val archive = com.music.bitchord.data.catalog.metadata.CoverArtArchive(
                store = com.music.bitchord.data.catalog.metadata.RoomMusicBrainzCoverArtStore(dao),
            )
            assertEquals("https://archive.org/verified-group-thumb-500.jpg",
                archive.cachedVerifiedGroupCover(groupId))
            assertEquals("https://archive.org/verified-group-front.jpg",
                dao.musicBrainzCoverArtCache("release-group:front-v5", groupId)?.artworkUri)
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
                "release-group:front-v5", groupMbid,
                "https://archive.org/verified-album-1200.jpg",
                System.currentTimeMillis() - 1_000L,
                thumbnailUri = "https://archive.org/verified-album-500.jpg",
            ))
            val joined = db.catalogDao().verifiedArtistDiscographyRows(
                requireNotNull(artistIds[artistMbid])).single()
            assertEquals(groupMbid, joined.musicBrainzId)
            // DAO only returns group identity; index must validate the
            // separately fetched Front-v5 record before publishing artwork.
            assertEquals(null, joined.artworkUri)
            assertEquals("Remix", joined.secondaryTypes)
            val cached = index.cachedArtistGroups(artistMbid).single()
            assertEquals(groupMbid, cached.musicBrainzId)
            assertEquals("Remix", cached.secondaryTypes)
            assertEquals("https://archive.org/verified-album-500.jpg", cached.artworkUri)
        } finally { db.close() }
    }

    @Test fun sparseRecordingSearchCannotErasePreviouslyVerifiedVideoFromRoom() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val mbid = "2c124b3c-e76b-4046-9980-e88c5ffa4ab2"
            val index = MusicBrainzCatalogIndex(db)
            index.persistRecordings(listOf(MusicBrainzRecordingHit(
                mbid, "New Jeans", "NewJeans", 180000,
                hasOfficialAudioRelease = false, isVideo = true,
            )))
            // The search API can omit both fields, unlike a previous exact
            // lookup. The DTO must carry null/unknown through to Room.
            val search = com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch(
                com.music.bitchord.data.catalog.metadata.MusicBrainzClient(
                    transport = object :
                        com.music.bitchord.data.catalog.metadata.MusicBrainzTransport {
                        override suspend fun get(
                            path: String, parameters: Map<String, String>, userAgent: String,
                        ): String = """{"count":1,"offset":0,"recordings":[
                            {"id":"$mbid","title":"New Jeans"}
                        ]}"""
                    },
                ),
            )
            val hit = (search.searchRecordings("New Jeans") as
                com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult.Success<
                    MusicBrainzRecordingHit
                >).page.items.single()
            assertEquals(null, hit.isVideo)
            assertEquals(null, hit.hasOfficialAudioRelease)
            index.persistRecordings(listOf(hit))
            val after = db.catalogDao().searchKnownMusicBrainzRecordings("New Jeans", 20)
                .single { it.musicBrainzId == mbid }
            assertEquals(true, after.isVideo)
            assertEquals(false, after.hasOfficialAudioRelease)
            assertEquals(false, isCuratedMusicBrainzRecording(after))
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


    @Test fun aRecordingMayReuseOnlyItsOwnOfficialReleaseGroupFront() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var n = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "recording-cover-" + (++n) })
            val group = "11111111-1111-4111-8111-111111111111"
            val release = "22222222-2222-4222-8222-222222222222"
            val record = "33333333-3333-4333-8333-333333333333"
            val track = "44444444-4444-4444-8444-444444444444"
            val unrelated = "55555555-5555-4555-8555-555555555555"
            index.persistReleaseGroups(listOf(
                MusicBrainzReleaseGroupHit(group, "Taste of Love", "EP", "2021-06-11", "TWICE"),
            ))
            index.persistGroupEditions(group, listOf(
                MusicBrainzReleaseHit(release, "Taste of Love", "XW",
                    "2021-06-11", "TWICE", status = "Official"),
            ))
            index.persistReleaseDetail(
                com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail(
                    musicBrainzId = release, groupMbid = group, title = "Taste of Love",
                    artistDisplay = "TWICE", country = "XW", releaseDate = "2021-06-11",
                    tracks = listOf(com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack(
                        trackMbid = track, recordingMbid = record, title = "Alcohol-Free",
                        mediumNumber = 1, trackNumber = 1, durationMs = 200000L,
                    )),
                ),
            )
            val dao = db.catalogDao()
            // A known Recording->Track->Official Release->Group claim is required.
            assertEquals(null, dao.verifiedRecordingCoverGroupMbid(record))
            assertEquals(null, dao.verifiedRecordingCoverGroupMbid(unrelated))
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v4", group, "https://archive.org/old-collector.jpg",
                1000L,
            ))
            assertEquals(null, dao.verifiedRecordingCoverGroupMbid(record))
            dao.upsertMusicBrainzCoverArtCache(MusicBrainzCoverArtCacheEntity(
                "release-group:front-v5", group, "https://archive.org/verified-main.jpg",
                2000L,
            ))
            assertEquals(group, dao.verifiedRecordingCoverGroupMbid(record))
            assertEquals(null, dao.verifiedRecordingCoverGroupMbid(unrelated))
            // A verified Front cannot turn a proven video-only Official
            // edition into positive Recording -> audio group evidence.
            index.persistGroupEditions(group, listOf(MusicBrainzReleaseHit(
                release, "Taste of Love", "XW", "2021-06-11", "TWICE",
                status = "Official", isVideoOnlyMedia = true,
            )))
            assertEquals(null, dao.verifiedRecordingCoverGroupMbid(record))
            assertEquals(emptyList<com.music.bitchord.data.catalog.CanonicalRecordingCoverGroupRow>(),
                dao.verifiedRecordingCoverGroupMbids(listOf(record)))
            // Updated explicit audio/mixed media evidence can correct the
            // earlier classification without changing a single MBID.
            index.persistGroupEditions(group, listOf(MusicBrainzReleaseHit(
                release, "Taste of Love", "XW", "2021-06-11", "TWICE",
                status = "Official", isVideoOnlyMedia = false,
            )))
            assertEquals(group, dao.verifiedRecordingCoverGroupMbid(record))
            // Bulk hydration must share the same proven official-edition edge
            // as the individual lookup. A twenty-song album cannot incur one
            // expensive Room graph traversal per cover, and unrelated songs
            // must not borrow the nearest album's artwork.
            val batch = dao.verifiedRecordingCoverGroupMbids(
                listOf(unrelated, record, record, "66666666-6666-4666-8666-666666666666"),
            )
            assertEquals(listOf(record to group), batch.map {
                it.recordingMbid to it.groupMbid
            }.distinct())

        } finally { db.close() }
    }


    @Test fun cachedDiscographyEditionsRetainAllStrongIdsAndOfficialStatusesInOneRead() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            var next = 0
            val index = MusicBrainzCatalogIndex(db, idFactory = { "edition-batch-" + (++next) })
            val group = "11111111-1111-4111-8111-111111111111"
            index.persistReleaseGroups(listOf(
                MusicBrainzReleaseGroupHit(group, "Get Up", "EP", "2023-07-21", "NewJeans"),
            ))
            val originals = (1..30).map { n ->
                MusicBrainzReleaseHit(
                    "22222222-2222-4222-8222-" + n.toString().padStart(12, '0'),
                    "Get Up", if (n == 1) "XW" else "KR",
                    "2023-07-21", "NewJeans",
                    status = if (n % 7 == 0) "Promotion" else "Official",
                )
            }
            index.persistGroupEditions(group, originals)
            val cached = index.cachedGroupReleases(group)
            assertEquals(30, cached.size)
            assertEquals(originals.map { it.musicBrainzId }.toSet(),
                cached.map { it.musicBrainzId }.toSet())
            assertEquals("XW", cached.single {
                it.musicBrainzId == originals[0].musicBrainzId
            }.country)
            assertEquals("Promotion", cached.single {
                it.musicBrainzId == originals[6].musicBrainzId
            }.status)
        } finally { db.close() }
    }

}

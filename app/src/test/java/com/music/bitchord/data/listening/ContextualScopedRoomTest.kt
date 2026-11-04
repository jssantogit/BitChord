package com.music.bitchord.data.listening

import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MusicBrainzReleaseEnrichmentEntity
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ContextualScopedRoomTest {
    @Test fun albumAndArtistSourcesAreProvenByProviderAssetAndCanonicalLinks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val current = "opensubsonic:one"
            val other = "opensubsonic:two"
            for (id in listOf("one", "two", "orphan")) {
                dao.upsertRecording(RecordingEntity("rec:$id", "Identical track", "Artist", 120_000))
                dao.upsertRelease(ReleaseEntity("rel:$id", "Shared EP", "Artist", null))
                dao.upsertReleaseTrack(ReleaseTrackEntity("rel:$id", "rec:$id", 1))
                dao.upsertArtist(ArtistEntity("artist:$id", "Shared name"))
                dao.upsertRecordingArtistCredits(listOf(
                    RecordingArtistCreditEntity("rec:$id", 0, "artist:$id"),
                ))
                dao.upsertReleaseArtistCredits(listOf(
                    ReleaseArtistCreditEntity("rel:$id", 0, "artist:$id"),
                ))
            }
            dao.upsertMediaAsset(MediaAssetEntity("asset:one", "rec:one",
                current, "native-one", "opensubsonic://one", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:two", "rec:two",
                other, "native-two", "opensubsonic://two", null, null, null, null))
            val albumRule = SavedSmartPlaylistV2Filter("f:a", "EP", SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.RELEASE_TITLE,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "ep",
            ))
            val artistRule = SavedSmartPlaylistV2Filter("f:b", "Shared", SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.ARTIST_DISPLAY,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "shared",
            ))
            val service = CanonicalContextualFilterLibrary(dao)
            assertEquals(listOf("rel:one"), service.albums(current, albumRule).map { it.id })
            assertEquals(listOf("artist:one"), service.artists(current, artistRule).map { it.id })
            assertEquals(listOf("rel:two"), service.albums(other, albumRule).map { it.id })
            assertEquals(listOf("artist:two"), service.artists(other, artistRule).map { it.id })
            assertTrue(runCatching { service.albums("", albumRule) }.exceptionOrNull()
                is IllegalArgumentException)
            assertTrue(runCatching { service.artists(" ", artistRule) }.exceptionOrNull()
                is IllegalArgumentException)
            assertEquals(listOf("rec:one"), dao.providerScopedReleaseRecordingIds(
                current, "rel:one",
            ))
            assertEquals(listOf("rec:one"), dao.providerScopedArtistRecordingIds(
                current, "artist:one",
            ))
            assertTrue(dao.providerScopedReleaseRecordingIds(
                current, "rel:two",
            ).isEmpty())
            assertTrue(dao.providerScopedArtistRecordingIds(
                current, "artist:two",
            ).isEmpty())
            assertTrue(dao.providerScopedReleaseRecordingIds(
                current, "rel:orphan",
            ).isEmpty())
            assertEquals(listOf("rel:two"), dao.providerScopedReleases(other).map { it.id })
            assertEquals(listOf("artist:two"), dao.providerScopedArtists(other).map { it.id })
            assertTrue(dao.providerScopedReleases("opensubsonic:unknown").isEmpty())
            assertTrue(dao.providerScopedArtists("opensubsonic:unknown").isEmpty())
            // Removing an account's asset revokes Album/Artist browse eligibility.
            dao.deleteAllMediaAssets(current)
            assertTrue(dao.providerScopedReleases(current).isEmpty())
            assertTrue(dao.providerScopedArtists(current).isEmpty())
            assertTrue(dao.providerScopedReleaseRecordingIds(
                current, "rel:one",
            ).isEmpty())
            assertTrue(dao.providerScopedArtistRecordingIds(
                current, "artist:one",
            ).isEmpty())
            assertEquals(listOf("rel:two"), dao.providerScopedReleases(other).map { it.id })
        } finally {
            db.close()
        }
    }
    @Test fun releaseOnlyArtistCreditDrillsDownToProviderBoundRecording() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("rec:one", "Shared", "Display", 200_000))
            dao.upsertRecording(RecordingEntity("rec:other", "Shared", "Display", 200_000))
            dao.upsertRelease(ReleaseEntity("rel:one", "EP", "Display", null))
            dao.upsertRelease(ReleaseEntity("rel:other", "EP", "Display", null))
            dao.upsertReleaseTrack(ReleaseTrackEntity("rel:one", "rec:one", 1))
            dao.upsertReleaseTrack(ReleaseTrackEntity("rel:other", "rec:other", 1))
            dao.upsertArtist(ArtistEntity("artist:one", "Genuine Credit"))
            dao.upsertReleaseArtistCredits(listOf(
                ReleaseArtistCreditEntity("rel:one", 0, "artist:one"),
                ReleaseArtistCreditEntity("rel:other", 0, "artist:one"),
            ))
            dao.upsertMediaAsset(MediaAssetEntity("asset:one", "rec:one",
                "opensubsonic:current", "native-one", "opensubsonic://one",
                null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:other", "rec:other",
                "opensubsonic:elsewhere", "native-other", "opensubsonic://other",
                null, null, null, null))
            assertEquals(listOf("artist:one"),
                dao.providerScopedArtists("opensubsonic:current").map { it.id })
            assertEquals(listOf("rec:one"), dao.providerScopedArtistRecordingIds(
                "opensubsonic:current", "artist:one",
            ))
            assertEquals(listOf("rec:other"), dao.providerScopedArtistRecordingIds(
                "opensubsonic:elsewhere", "artist:one",
            ))
            assertTrue(dao.providerScopedArtistRecordingIds(
                "opensubsonic:unknown", "artist:one",
            ).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun savedSongFilterRunsAcrossLocalAndRemoteWithExactCanonicalSourceIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val local = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
            val home = "opensubsonic:home"
            val work = "opensubsonic:work"
            dao.upsertRecording(RecordingEntity("rec:shared", "Ditto", "NewJeans", 180_000))
            dao.upsertRecording(RecordingEntity("rec:home", "Get Up", "NewJeans", 60_000))
            dao.upsertRecording(RecordingEntity("rec:work", "Get Up", "NewJeans", 60_000))
            dao.upsertMediaAsset(MediaAssetEntity(
                "asset:shared-local", "rec:shared", local,
                "mediastore-shared", "content://media/audio/100", null, null, null, null,
            ))
            dao.upsertMediaAsset(MediaAssetEntity(
                "asset:shared-remote", "rec:shared", home,
                "remote-shared", "opensubsonic://media/100", null, null, null, null,
            ))
            dao.upsertMediaAsset(MediaAssetEntity(
                "asset:home", "rec:home", home,
                "remote-home", "opensubsonic://media/101", null, null, null, null,
            ))
            dao.upsertMediaAsset(MediaAssetEntity(
                "asset:work", "rec:work", work,
                "remote-work", "opensubsonic://media/102", null, null, null, null,
            ))
            val ditto = SavedSmartPlaylistV2Filter("f:ditto", "Title Ditto",
                SmartPlaylistRule.Comparison(SmartPlaylistRuleField.TITLE,
                    SmartPlaylistRuleOperator.EQUAL, textValue = "Ditto"))
            val getUp = ditto.copy(rules = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.EQUAL, textValue = "Get Up"))
            val service = CanonicalContextualFilterLibrary(dao)
            assertEquals(listOf("rec:shared"), service.songs(local, ditto).map { it.recordingId })
            assertEquals(listOf("rec:shared"), service.songs(home, ditto).map { it.recordingId })
            assertTrue(service.songs(work, ditto).isEmpty())
            assertEquals(listOf("rec:home"), service.songs(home, getUp).map { it.recordingId })
            assertEquals(listOf("rec:work"), service.songs(work, getUp).map { it.recordingId })
            val localAsset = service.songs(local, ditto).single().availableAssets.single()
            assertEquals(local, localAsset.providerKey)
            assertEquals("asset:shared-local", localAsset.mediaAssetId)
            val homeAsset = service.songs(home, ditto).single().availableAssets.single()
            assertEquals(home, homeAsset.providerKey)
            assertEquals("asset:shared-remote", homeAsset.mediaAssetId)
            dao.deleteAllMediaAssets(home)
            assertTrue(service.songs(home, ditto).isEmpty())
            assertEquals(listOf("rec:shared"), service.songs(local, ditto).map { it.recordingId })
            assertEquals(listOf("rec:work"), service.songs(work, getUp).map { it.recordingId })
            assertTrue(runCatching { service.songs("", ditto) }.exceptionOrNull()
                is IllegalArgumentException)
            assertTrue(runCatching { service.songs(home, ditto.copy(rules =
                SmartPlaylistRule.Comparison(SmartPlaylistRuleField.EXPLICIT_RATING,
                    SmartPlaylistRuleOperator.IS_MISSING))) }.exceptionOrNull()
                is IllegalArgumentException)
        } finally {
            db.close()
        }
    }

    @Test fun sourceKindFilterIsConsistentForSourceBoundSongsAlbumsAndArtists() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val local = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
            val remote = "opensubsonic:one"
            dao.upsertRecording(RecordingEntity("recording:one", "Good Song", "Artist", 180_000))
            dao.upsertRelease(ReleaseEntity("release:one", "Full Album", "Artist", null))
            dao.upsertReleaseTrack(ReleaseTrackEntity("release:one", "recording:one", 1))
            dao.upsertArtist(ArtistEntity("artist:one", "Genuine Artist"))
            dao.upsertReleaseArtistCredits(listOf(
                ReleaseArtistCreditEntity("release:one", 0, "artist:one"),
            ))
            dao.upsertMediaAsset(MediaAssetEntity("asset:l", "recording:one", local,
                "local:key", "content://media/1", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:r", "recording:one", remote,
                "remote:key", "opensubsonic://media/1", null, null, null, null))
            val scope = CanonicalContextualFilterLibrary(dao)
            val localFilter = SavedSmartPlaylistV2Filter(
                "f:local", "Local", SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
                    textValue = "local",
                ),
            )
            val remoteFilter = localFilter.copy(
                id = "f:remote", name = "Remote",
                rules = SmartPlaylistRule.Comparison(
                    SmartPlaylistRuleField.SOURCE_KIND, SmartPlaylistRuleOperator.EQUAL,
                    textValue = "opensubsonic",
                ),
            )
            assertEquals(listOf("recording:one"), scope.songs(local, localFilter)
                .map { it.recordingId })
            assertEquals(listOf("recording:one"), scope.songs(remote, remoteFilter)
                .map { it.recordingId })
            assertEquals(listOf("release:one"), scope.albums(local, localFilter)
                .map { it.id })
            assertEquals(listOf("release:one"), scope.albums(remote, remoteFilter)
                .map { it.id })
            assertEquals(listOf("artist:one"), scope.artists(local, localFilter)
                .map { it.id })
            assertEquals(listOf("artist:one"), scope.artists(remote, remoteFilter)
                .map { it.id })
            assertTrue(scope.songs(local, remoteFilter).isEmpty())
            assertTrue(scope.albums(remote, localFilter).isEmpty())
            assertTrue(scope.artists(local, remoteFilter).isEmpty())
            dao.deleteAllMediaAssets(local)
            assertTrue(scope.albums(local, localFilter).isEmpty())
            assertTrue(scope.artists(local, localFilter).isEmpty())
            assertEquals(listOf("release:one"), scope.albums(remote, remoteFilter)
                .map { it.id })
            assertEquals(listOf("artist:one"), scope.artists(remote, remoteFilter)
                .map { it.id })
        } finally { db.close() }
    }

    @Test fun localAddedDateRuleUsesOnlyPersistedMediaStoreEvidenceForCurrentSource() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val local = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
            val remote = "opensubsonic:private"
            val nowSeconds = System.currentTimeMillis() / 1_000
            dao.upsertRecording(RecordingEntity("rec:old", "Old Local", "A", 100_000))
            dao.upsertRecording(RecordingEntity("rec:unknown", "Undated", "B", 100_000))
            dao.upsertRecording(RecordingEntity("rec:future", "Future", "C", 100_000))
            dao.upsertMediaAsset(MediaAssetEntity("asset:old", "rec:old", local,
                "key:old", "content://media/1", null,
                nowSeconds - 15L * 86_400L, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:undated", "rec:unknown", local,
                "key:unknown", "content://media/2", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:future", "rec:future", local,
                "key:future", "content://media/3", null, nowSeconds + 86_400L, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset:remote", "rec:old", remote,
                "key:remote", "opensubsonic://media/1", null, null, null, null))
            val ageRule = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, value = 10.0,
            )
            val presentRule = SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
                SmartPlaylistRuleOperator.IS_PRESENT,
            )
            val reader = CanonicalContextualFilterLibrary(dao)
            val old = SavedSmartPlaylistV2Filter("old", "Old", ageRule)
            assertEquals(listOf("rec:old"), reader.songs(local, old).map { it.recordingId })
            assertEquals(listOf("rec:old"), reader.songs(local, old.copy(
                rules = presentRule,
            )).map { it.recordingId })
            assertEquals(emptyList<String>(), reader.songs(local, old.copy(
                rules = SmartPlaylistRule.Not(presentRule),
            )).map { it.recordingId })
            assertTrue(runCatching { reader.songs(remote, old) }.exceptionOrNull()
                is IllegalArgumentException)
            dao.deleteAllMediaAssets(local)
            assertTrue(reader.songs(local, old).isEmpty())
            // The remote copy cannot acquire the date from an unrelated local asset.
            assertTrue(runCatching { reader.songs(remote, old) }.exceptionOrNull()
                is IllegalArgumentException)
        } finally { db.close() }
    }

    @Test fun albumYearRequiresUniqueTrustedMusicBrainzAliasAndCurrentAccountMembership() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.catalogDao()
            val provider = "opensubsonic:trusted"
            val other = "opensubsonic:unrelated"
            val validMbid = "123e4567-e89b-12d3-a456-426614174000"
            val wrongMbid = "123e4567-e89b-12d3-a456-426614174001"
            val ambiguousMbid = "123e4567-e89b-12d3-a456-426614174002"
            val mismatchedAliasMbid = "123e4567-e89b-12d3-a456-426614174003"
            val invalidMbid = "123e4567-e89b-12d3-a456-426614174004"
            val secondAmbiguousMbid = "123e4567-e89b-12d3-a456-426614174005"
            for (id in listOf("trusted", "wrong", "ambiguous", "invalid", "orphan")) {
                dao.upsertRecording(RecordingEntity("recording:$id", "Example", "Band", 150_000))
                dao.upsertRelease(ReleaseEntity("release:$id", "Example EP", "Band", null))
                dao.upsertReleaseTrack(ReleaseTrackEntity("release:$id", "recording:$id", 1))
                if (id != "orphan") dao.upsertMediaAsset(MediaAssetEntity(
                    "asset:$id", "recording:$id", if (id == "invalid") other else provider,
                    "source:$id", "opensubsonic://$id", null, null, null, null,
                ))
                val mbid = when (id) {
                    "wrong" -> wrongMbid
                    "invalid" -> invalidMbid
                    "ambiguous" -> ambiguousMbid
                    else -> validMbid
                }
                dao.upsertMusicBrainzReleaseEnrichment(MusicBrainzReleaseEnrichmentEntity(
                    "release:$id", mbid, "Example EP", "Band",
                    if (id == "invalid") "2023-02-30" else "2023-07-21",
                    null, null, 1000L,
                ))
            }
            for (id in listOf("trusted", "wrong", "ambiguous", "invalid")) {
                dao.upsertExternalIdentity(ExternalIdentityEntity(
                    "musicbrainz", "release",
                    when (id) {
                        "wrong" -> mismatchedAliasMbid
                        "ambiguous" -> ambiguousMbid
                        "invalid" -> invalidMbid
                        else -> validMbid
                    },
                    "release:$id",
                ))
            }
            dao.upsertExternalIdentity(ExternalIdentityEntity(
                "musicbrainz", "release", secondAmbiguousMbid, "release:ambiguous",
            ))
            val filter = SavedSmartPlaylistV2Filter("year", "2023",
                SmartPlaylistRule.Comparison(SmartPlaylistRuleField.RELEASE_YEAR,
                    SmartPlaylistRuleOperator.EQUAL, value = 2023.0))
            val service = CanonicalContextualFilterLibrary(dao)
            assertEquals(listOf("release:trusted"), service.albums(provider, filter)
                .map { it.id })
            assertTrue(service.albums(other, filter).isEmpty())
            dao.deleteAllMediaAssets(provider)
            assertTrue(service.albums(provider, filter).isEmpty())
        } finally { db.close() }
    }

}

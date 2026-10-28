package com.music.bitchord.data.listening

import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
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

}

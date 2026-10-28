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
            assertEquals(listOf("rel:two"), dao.providerScopedReleases(other).map { it.id })
            assertEquals(listOf("artist:two"), dao.providerScopedArtists(other).map { it.id })
            assertTrue(dao.providerScopedReleases("opensubsonic:unknown").isEmpty())
            assertTrue(dao.providerScopedArtists("opensubsonic:unknown").isEmpty())
        } finally {
            db.close()
        }
    }
}

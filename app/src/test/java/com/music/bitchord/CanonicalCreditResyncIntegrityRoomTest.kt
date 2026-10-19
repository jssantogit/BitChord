package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingArtistCreditEntity
import com.music.bitchord.data.catalog.MusicBrainzRecordingEnrichmentEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCatalogSync
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * C3: the actual OpenSubsonic transport -> canonical sync -> Room boundary, not a fake DAO.
 * Reuses a named database after close/reopen to prove durable canonical credit preservation.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CanonicalCreditResyncIntegrityRoomTest {
    private val recordingMbid = "11111111-1111-1111-1111-111111111111"
    private val conflictMbid = "44444444-4444-4444-4444-444444444444"
    private val artistAMbid = "22222222-2222-2222-2222-222222222222"
    private val artistBMbid = "33333333-3333-3333-3333-333333333333"

    private class FakeTransport : OpenSubsonicTransport {
        var mode = "online"
        var calls = 0
        override suspend fun get(endpoint: String, parameters: Map<String, String>): String {
            calls++
            if (mode == "offline") throw IOException("simulated connection loss")
            if (mode == "auth") return envelope("\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"no authorization\"}")
            return when (endpoint) {
                "getAlbumList2" -> envelope("\"status\":\"ok\",\"albumList2\":{\"album\":[{\"id\":\"album-1\",\"name\":\"Album\"}]}")
                "getAlbum" -> envelope(
                    "\"status\":\"ok\",\"album\":{\"id\":\"album-1\",\"name\":\"Album\",\"artists\":[{\"id\":\"provider-artist-b\",\"name\":\"Artist B\"}],\"song\":[" +
                    song("song-1") +
                    (if (mode == "partial-transaction") "," + song("song-2") else "") +
                    "]}"
                )
                else -> error("Unexpected request")
            }
        }
        private fun envelope(payload: String) = "{\"subsonic-response\":{" + payload + "}}"
        private fun song(id: String) =
            "{\"id\":\"" + id + "\",\"title\":\"Updated from server\",\"artist\":\"Artist B\"," +
            "\"musicBrainzId\":\"11111111-1111-1111-1111-111111111111\"," +
            "\"artists\":[{\"id\":\"provider-artist-b\",\"name\":\"Artist B\"," +
            "\"musicBrainzId\":\"33333333-3333-3333-3333-333333333333\"}]}"
    }

    private fun provider(transport: OpenSubsonicTransport) = OpenSubsonicProvider(
        OpenSubsonicAccountConfig(
            accountId = "home",
            serverUrl = "https://music.example.test",
            auth = OpenSubsonicAuth.ApiKey("fixture-key"),
        ),
        transport = transport,
    )

    private suspend fun seed(db: CanonicalCatalogDatabase) {
        val dao = db.catalogDao()
        dao.upsertRecording(RecordingEntity("recording-1", "Original", "Artist A", 100L))
        dao.upsertArtist(ArtistEntity("artist-a", "Artist A"))
        dao.upsertArtist(ArtistEntity("artist-b", "Artist B"))
        dao.upsertRelease(ReleaseEntity("release-1", "Original album", "Artist A", null))
        dao.upsertExternalIdentity(ExternalIdentityEntity("opensubsonic:home", "release", "album-1", "release-1"))
        dao.upsertReleaseArtistCredits(listOf(ReleaseArtistCreditEntity("release-1", 0, "artist-a")))
        dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", recordingMbid, "recording-1"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", artistAMbid, "artist-a"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "artist", artistBMbid, "artist-b"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("opensubsonic:home", "recording", "song-1", "recording-1"))
        dao.upsertExternalIdentity(ExternalIdentityEntity("opensubsonic:home", "artist", "provider-artist-b", "artist-b"))
        dao.upsertRecordingArtistCredits(listOf(RecordingArtistCreditEntity("recording-1", 0, "artist-a")))
        dao.upsertMusicBrainzRecordingEnrichment(
            MusicBrainzRecordingEnrichmentEntity("recording-1", recordingMbid, null, null, null, 1L))
        dao.upsertMusicBrainzRecordingArtistCredits(listOf(
            MusicBrainzRecordingArtistCreditEntity("recording-1", 0, artistAMbid, "Artist A", null)))
        dao.upsertMediaAsset(MediaAssetEntity(
            "asset-1", "recording-1", "opensubsonic:home", "song-1",
            "opensubsonic://asset/old", null, null, null, null))
    }

    @Test fun fullResyncDoesNotReplaceCanonicalCreditWithConflictingProviderCredit() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "c3-integrity-" + System.nanoTime() + ".db"
        var db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            seed(db)
            val transport = FakeTransport()
            val ids = java.util.concurrent.atomic.AtomicInteger(0)
            val result = OpenSubsonicCatalogSync.sync(db, provider(transport),
                idFactory = { "new-" + ids.incrementAndGet() })
            assertTrue(result is ProviderSyncResult.Success)
            val dao = db.catalogDao()
            assertEquals(listOf("artist-a"), dao.recordingArtistCredits("recording-1").map { it.artistId })
            assertEquals(listOf("artist-a"), dao.releaseArtistCredits("release-1").map { it.artistId })
            assertEquals("recording-1", dao.entityIdForExternalIdentity("musicbrainz", "recording", recordingMbid))
            assertEquals("artist-a", dao.entityIdForExternalIdentity("musicbrainz", "artist", artistAMbid))
            assertEquals("artist-b", dao.entityIdForExternalIdentity("opensubsonic:home", "artist", "provider-artist-b"))
            assertEquals("asset-1", dao.mediaAsset("opensubsonic:home", "song-1")?.id)
            assertEquals(1, dao.musicBrainzRecordingArtistCredits("recording-1").size)
            db.close()
            db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            assertEquals(listOf("artist-a"), db.catalogDao().recordingArtistCredits("recording-1").map { it.artistId })
            assertEquals(listOf("artist-a"), db.catalogDao().releaseArtistCredits("release-1").map { it.artistId })
            assertEquals("asset-1", db.catalogDao().mediaAsset("opensubsonic:home", "song-1")?.id)
            val replay = OpenSubsonicCatalogSync.sync(db, provider(transport),
                idFactory = { error("A replay must not allocate new canonical IDs") })
            assertTrue(replay is ProviderSyncResult.Success)
            assertEquals(listOf("artist-a"), db.catalogDao().recordingArtistCredits("recording-1").map { it.artistId })
            assertEquals("asset-1", db.catalogDao().mediaAsset("opensubsonic:home", "song-1")?.id)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun offlineAuthFailureAndReconnectionKeepLastCompleteCreditsAndAssets() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val transport = FakeTransport()
            val client = provider(transport)
            val dao = db.catalogDao()
            val baselineAsset = dao.mediaAsset("opensubsonic:home", "song-1")
            val baselineEvidence = dao.musicBrainzRecordingArtistCredits("recording-1")
            for (mode in listOf("offline", "auth")) {
                transport.mode = mode
                assertTrue(OpenSubsonicCatalogSync.sync(db, client) is ProviderSyncResult.Failure)
                assertEquals(baselineAsset, dao.mediaAsset("opensubsonic:home", "song-1"))
                assertEquals(baselineEvidence, dao.musicBrainzRecordingArtistCredits("recording-1"))
                assertEquals(listOf("artist-a"), dao.recordingArtistCredits("recording-1").map { it.artistId })
            }
            transport.mode = "online"
            assertTrue(OpenSubsonicCatalogSync.sync(db, client, idFactory = { "new-release" }) is ProviderSyncResult.Success)
            assertEquals("asset-1", dao.mediaAsset("opensubsonic:home", "song-1")?.id)
            assertEquals(listOf("artist-a"), dao.recordingArtistCredits("recording-1").map { it.artistId })
        } finally { db.close() }
    }

    @Test fun laterApplyFailureRollsBackEarlierWritesAndPreservesCanonicalCredits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val dao = db.catalogDao()
            val transport = FakeTransport().apply { mode = "partial-transaction" }
            val beforeRecording = dao.recordingById("recording-1")
            val beforeAsset = dao.mediaAsset("opensubsonic:home", "song-1")
            val failed = OpenSubsonicCatalogSync.sync(db, provider(transport), idFactory = {
                error("simulated failure while allocating previously unseen MediaAsset")
            })
            assertTrue(failed is ProviderSyncResult.Failure)
            assertEquals(beforeRecording, dao.recordingById("recording-1"))
            assertEquals(beforeAsset, dao.mediaAsset("opensubsonic:home", "song-1"))
            assertEquals(listOf("artist-a"), dao.recordingArtistCredits("recording-1").map { it.artistId })
            assertEquals(null, dao.entityIdForExternalIdentity("opensubsonic:home", "recording", "song-2"))
            assertEquals(null, dao.mediaAsset("opensubsonic:home", "song-2"))
        } finally { db.close() }
    }
}

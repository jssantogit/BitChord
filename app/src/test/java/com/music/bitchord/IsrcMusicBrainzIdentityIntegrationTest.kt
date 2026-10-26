package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import com.music.bitchord.data.catalog.identity.CanonicalRecordingIdentityDiscoveryRepository
import com.music.bitchord.data.catalog.identity.MusicBrainzRecordingIdentityDiscoveryProvider
import com.music.bitchord.data.catalog.identity.RecordingIdentityResolutionResult
import com.music.bitchord.data.catalog.metadata.CanonicalMetadataEnrichmentRepository
import com.music.bitchord.data.catalog.metadata.MetadataFieldSource
import com.music.bitchord.data.catalog.metadata.MetadataRefreshResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzMetadataEnrichmentProvider
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.provider.CanonicalCatalogSyncStore
import com.music.bitchord.data.catalog.provider.CanonicalRemoteCatalogSync
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class IsrcMusicBrainzIdentityIntegrationTest {

    @Test
    fun openSubsonicIsrcPromotesMbidThenExistingEnrichmentSurvivesReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "isrc-musicbrainz-vertical-${System.nanoTime()}.db"
        val provider = openSubsonicProvider(
            songId = "song-1",
            isrcs = listOf("US-S1Z-99-00001"),
        )
        var idSequence = 0

        var database = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        var dao = database.catalogDao()
        val sync = CanonicalRemoteCatalogSync.sync(
            provider = provider,
            store = RoomStore(dao),
            idFactory = { "canonical-${++idSequence}" },
        )
        assertTrue(sync is ProviderSyncResult.Success)
        val recordingId = requireNotNull(
            dao.entityIdForExternalIdentity("opensubsonic:home", "recording", "song-1"),
        )
        val mediaAsset = requireNotNull(dao.mediaAsset("opensubsonic:home", "song-1"))
        assertEquals(recordingId, mediaAsset.recordingId)
        assertEquals(recordingId, dao.entityIdForExternalIdentity("isrc", "recording", ISRC))
        assertTrue(dao.externalIdentitiesForEntity("musicbrainz", "recording", recordingId).isEmpty())

        var virtualTime = 0L
        val transport = RoutingMusicBrainzTransport { path ->
            when (path) {
                "isrc/$ISRC" -> """{"isrc":"$ISRC","recordings":[{"id":"$MBID"}]}"""
                "recording/$MBID" -> """
                    {
                      "id":"$MBID",
                      "title":"MusicBrainz Track",
                      "length":null,
                      "artist-credit":[{
                        "name":"Artist feat. Guest",
                        "artist":{"id":"11111111-1111-1111-1111-111111111111","name":"Artist feat. Guest"}
                      }]
                    }
                """.trimIndent()
                else -> error("unexpected MusicBrainz path $path")
            }
        }
        val sharedClient = MusicBrainzClient(
            transport = transport,
            rateLimiter = MusicBrainzRateLimiter(
                nowMillis = { virtualTime },
                delayMillis = { millis -> virtualTime += millis },
            ),
            userAgent = TEST_USER_AGENT,
        )
        var discovery = CanonicalRecordingIdentityDiscoveryRepository(
            dao,
            MusicBrainzRecordingIdentityDiscoveryProvider(sharedClient),
        )
        var enrichment = CanonicalMetadataEnrichmentRepository(
            dao,
            MusicBrainzMetadataEnrichmentProvider(sharedClient),
        ) { 1234L }

        assertEquals(
            RecordingIdentityResolutionResult.Promoted(MBID),
            discovery.resolveMusicBrainzRecordingIdentity(recordingId),
        )
        assertEquals(recordingId, dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID))
        assertTrue(enrichment.refreshRecording(recordingId) is MetadataRefreshResult.Refreshed)
        var resolved = requireNotNull(enrichment.resolveRecording(recordingId))
        assertEquals("MusicBrainz Track", resolved.title.value)
        assertEquals(MetadataFieldSource.MUSICBRAINZ, resolved.title.provenance?.source)
        assertEquals(180_000L, resolved.durationMs.value)
        assertEquals(MetadataFieldSource.CANONICAL, resolved.durationMs.provenance?.source)
        assertEquals(MBID, resolved.musicBrainzId)
        val networkCallsBeforeReopen = transport.paths.size

        database.close()
        database = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        dao = database.catalogDao()
        assertEquals(recordingId, dao.entityIdForExternalIdentity("isrc", "recording", ISRC))
        assertEquals(recordingId, dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID))

        val noNetworkDiscovery = object : com.music.bitchord.data.catalog.identity.RecordingIdentityDiscoveryProvider {
            override val providerKey: String = "musicbrainz"
            override suspend fun lookupByIsrc(isrc: String): com.music.bitchord.data.catalog.identity.StrongRecordingIdentityLookupResult =
                error("existing MBID must short-circuit rediscovery")
        }
        discovery = CanonicalRecordingIdentityDiscoveryRepository(dao, noNetworkDiscovery)
        enrichment = CanonicalMetadataEnrichmentRepository(
            dao,
            MusicBrainzMetadataEnrichmentProvider(sharedClient),
        ) { 9999L }
        assertEquals(
            RecordingIdentityResolutionResult.AlreadyResolved(MBID),
            discovery.resolveMusicBrainzRecordingIdentity(recordingId),
        )
        resolved = requireNotNull(enrichment.resolveRecording(recordingId))
        assertEquals("MusicBrainz Track", resolved.title.value)
        assertEquals(MetadataFieldSource.MUSICBRAINZ, resolved.title.provenance?.source)
        assertEquals(networkCallsBeforeReopen, transport.paths.size)

        database.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10, cursor.getInt(0))
        }
        database.close()
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun discoveryConflictNeverMergesCanonicalRecordingsOrMovesMappings() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.catalogDao()
            dao.upsertRecording(RecordingEntity("r1", "One", "Artist One", 1L))
            dao.upsertRecording(RecordingEntity("r2", "Two", "Artist Two", 2L))
            dao.upsertExternalIdentity(ExternalIdentityEntity("isrc", "recording", ISRC, "r1"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", MBID, "r2"))
            dao.upsertMediaAsset(MediaAssetEntity("asset-r1", "r1", "provider:a", "a", "provider://a", null, null, null, null))
            dao.upsertMediaAsset(MediaAssetEntity("asset-r2", "r2", "provider:b", "b", "provider://b", null, null, null, null))
            var virtualTime = 0L
            val transport = RoutingMusicBrainzTransport { path ->
                assertEquals("isrc/$ISRC", path)
                """{"isrc":"$ISRC","recordings":[{"id":"$MBID"}]}"""
            }
            val client = MusicBrainzClient(
                transport = transport,
                rateLimiter = MusicBrainzRateLimiter(
                    nowMillis = { virtualTime },
                    delayMillis = { millis -> virtualTime += millis },
                ),
                userAgent = TEST_USER_AGENT,
            )
            val repository = CanonicalRecordingIdentityDiscoveryRepository(
                dao,
                MusicBrainzRecordingIdentityDiscoveryProvider(client),
            )

            assertEquals(
                RecordingIdentityResolutionResult.IdentityConflict(MBID, "r2"),
                repository.resolveMusicBrainzRecordingIdentity("r1"),
            )
            assertEquals("r1", dao.entityIdForExternalIdentity("isrc", "recording", ISRC))
            assertEquals("r2", dao.entityIdForExternalIdentity("musicbrainz", "recording", MBID))
            assertEquals("r1", dao.mediaAsset("provider:a", "a")?.recordingId)
            assertEquals("r2", dao.mediaAsset("provider:b", "b")?.recordingId)
            assertEquals("One", dao.recordingById("r1")?.title)
            assertEquals("Two", dao.recordingById("r2")?.title)
            assertNull(dao.entityIdForExternalIdentity("musicbrainz", "recording", "different"))
        } finally {
            database.close()
        }
    }


    @Test
    fun incomingUnclaimedMbidCannotContaminateKnownIsrcRecording() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.catalogDao()
            val oldMbid = "11111111-1111-1111-1111-111111111111"
            val incomingMbid = "33333333-3333-3333-3333-333333333333"
            dao.upsertRecording(RecordingEntity("r1", "Original", "Artist", 180_000L))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", oldMbid, "r1"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("isrc", "recording", ISRC, "r1"))
            dao.upsertMediaAsset(MediaAssetEntity("old-asset", "r1", "opensubsonic:old", "old-song", "provider://old", null, null, null, null))

            val result = CanonicalRemoteCatalogSync.applySnapshot(
                providerKey = "opensubsonic:new",
                snapshot = com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot(
                    listOf(conflictingTrack("new-song", incomingMbid)),
                    complete = true,
                ),
                store = RoomStore(dao),
                idFactory = { "r2" },
            )

            // Strong MBID X cannot be attached to R1 just because ISRC S matches.
            assertEquals("r2", dao.entityIdForExternalIdentity("opensubsonic:new", "recording", "new-song"))
            assertEquals("r2", dao.entityIdForExternalIdentity("musicbrainz", "recording", incomingMbid))
            assertEquals("r1", dao.entityIdForExternalIdentity("musicbrainz", "recording", oldMbid))
            assertEquals(listOf(oldMbid), dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1").map { it.externalId })
            assertEquals("r1", dao.entityIdForExternalIdentity("isrc", "recording", ISRC))
            assertEquals("r1", dao.mediaAsset("opensubsonic:old", "old-song")?.recordingId)
            assertEquals("r2", dao.mediaAsset("opensubsonic:new", "new-song")?.recordingId)
            assertEquals("Original", dao.recordingById("r1")?.title)
            assertEquals(1, result.identityConflicts)
        } finally {
            database.close()
        }
    }

    @Test
    fun establishedProviderAliasAndAssetRemainStableWithoutClaimingDivergentMbid() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.catalogDao()
            val oldMbid = "11111111-1111-1111-1111-111111111111"
            val incomingMbid = "33333333-3333-3333-3333-333333333333"
            dao.upsertRecording(RecordingEntity("r1", "Original", "Artist", 180_000L))
            dao.upsertExternalIdentity(ExternalIdentityEntity("musicbrainz", "recording", oldMbid, "r1"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("isrc", "recording", ISRC, "r1"))
            dao.upsertExternalIdentity(ExternalIdentityEntity("opensubsonic:home", "recording", "song", "r1"))
            dao.upsertMediaAsset(MediaAssetEntity("stable-asset", "r1", "opensubsonic:home", "song", "provider://old", null, null, null, null))

            val result = CanonicalRemoteCatalogSync.applySnapshot(
                providerKey = "opensubsonic:home",
                snapshot = com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot(
                    listOf(conflictingTrack("song", incomingMbid)),
                    complete = true,
                ),
                store = RoomStore(dao),
                idFactory = { error("existing alias and asset must not allocate new Recording") },
            )

            assertEquals("r1", dao.entityIdForExternalIdentity("opensubsonic:home", "recording", "song"))
            assertEquals("stable-asset", dao.mediaAsset("opensubsonic:home", "song")?.id)
            assertEquals("r1", dao.mediaAsset("opensubsonic:home", "song")?.recordingId)
            assertEquals("r1", dao.entityIdForExternalIdentity("isrc", "recording", ISRC))
            assertEquals("r1", dao.entityIdForExternalIdentity("musicbrainz", "recording", oldMbid))
            assertNull(dao.entityIdForExternalIdentity("musicbrainz", "recording", incomingMbid))
            assertEquals(listOf(oldMbid), dao.externalIdentitiesForEntity("musicbrainz", "recording", "r1").map { it.externalId })
            assertEquals(1, result.identityConflicts)
        } finally {
            database.close()
        }
    }

    private fun conflictingTrack(sourceKey: String, mbid: String) =
        com.music.bitchord.data.catalog.provider.ProviderTrack(
            externalTrackId = sourceKey,
            title = "Conflicting provider track",
            artistDisplay = "Artist",
            externalArtistId = null,
            durationMs = 180_000L,
            trackNumber = 1,
            musicBrainzRecordingId = mbid,
            album = null,
            assetUri = "provider://$sourceKey",
            artworkUri = null,
            dateAddedSeconds = null,
            contentType = "audio/flac",
            suffix = "flac",
            bitRateKbps = null,
            bitDepth = null,
            samplingRateHz = null,
            artists = emptyList(),
            isrcs = listOf(ISRC),
        )

    private fun openSubsonicProvider(
        songId: String,
        isrcs: List<String>,
    ): OpenSubsonicProvider {
        val transport = object : OpenSubsonicTransport {
            override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
                when (endpoint) {
                    "getAlbumList2" -> """
                        {
                          "subsonic-response": {
                            "status":"ok",
                            "version":"1.16.1",
                            "albumList2":{"album":[{"id":"album-1","name":"Album"}]}
                          }
                        }
                    """.trimIndent()
                    "getAlbum" -> """
                        {
                          "subsonic-response": {
                            "status":"ok",
                            "version":"1.16.1",
                            "album": {
                              "id":"album-1",
                              "name":"Album",
                              "artist":"Canonical Artist",
                              "song":[{
                                "id":"$songId",
                                "title":"Canonical Track",
                                "artist":"Canonical Artist",
                                "duration":180,
                                "isrc":[${isrcs.joinToString(",") { "\"$it\"" }}]
                              }]
                            }
                          }
                        }
                    """.trimIndent()
                    else -> error("unexpected OpenSubsonic endpoint $endpoint")
                }
        }
        return OpenSubsonicProvider(
            config = OpenSubsonicAccountConfig(
                accountId = "home",
                serverUrl = "https://music.example.test",
                auth = OpenSubsonicAuth.ApiKey("secret"),
            ),
            transport = transport,
        )
    }

    private class RoutingMusicBrainzTransport(
        private val responder: suspend (String) -> String,
    ) : MusicBrainzTransport {
        val paths = mutableListOf<String>()

        override suspend fun get(
            path: String,
            parameters: Map<String, String>,
            userAgent: String,
        ): String {
            paths += path
            assertTrue("query" !in parameters)
            assertEquals("json", parameters["fmt"])
            return responder(path)
        }
    }

    private class RoomStore(
        private val dao: CanonicalCatalogDao,
    ) : CanonicalCatalogSyncStore {
        override suspend fun mediaAsset(provider: String, sourceKey: String) = dao.mediaAsset(provider, sourceKey)
        override suspend fun entityIdForExternalIdentity(provider: String, entityType: String, externalId: String) =
            dao.entityIdForExternalIdentity(provider, entityType, externalId)

        override suspend fun musicBrainzRecordingIdsForEntity(recordingId: String): List<String> =
            dao.externalIdentitiesForEntity("musicbrainz", "recording", recordingId)
                .map(ExternalIdentityEntity::externalId)

        override suspend fun claimMusicBrainzRecordingIdentity(mbid: String, recordingId: String): Boolean {
            val claim = dao.claimExternalIdentityExclusivelyForEntity(
                ExternalIdentityEntity("musicbrainz", "recording", mbid, recordingId),
            )
            return claim.candidateOwnerEntityId == recordingId &&
                claim.existingExternalIdsForEntity.all { it == mbid }
        }

        override suspend fun upsertRecording(recording: RecordingEntity) = dao.upsertRecording(recording)
        override suspend fun upsertRelease(release: ReleaseEntity) = dao.upsertRelease(release)
        override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) = dao.upsertReleaseTrack(releaseTrack)
        override suspend fun upsertArtist(artist: ArtistEntity) = dao.upsertArtist(artist)
        override suspend fun replaceRecordingArtistCredits(
            recordingId: String,
            credits: List<RecordingArtistCreditEntity>,
        ) = dao.replaceRecordingArtistCredits(recordingId, credits)
        override suspend fun replaceReleaseArtistCredits(
            releaseId: String,
            credits: List<ReleaseArtistCreditEntity>,
        ) = dao.replaceReleaseArtistCredits(releaseId, credits)
        override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) = dao.upsertMediaAsset(mediaAsset)
        override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) = dao.upsertExternalIdentity(identity)
        override suspend fun mediaAssetSourceKeys(provider: String) = dao.mediaAssetSourceKeys(provider)
        override suspend fun deleteMediaAsset(provider: String, sourceKey: String) = dao.deleteMediaAsset(provider, sourceKey)
    }

    companion object {
        private const val TEST_USER_AGENT = "Bitchord/test (https://github.com/jssantogit/BitChord)"
        private const val ISRC = "USS1Z9900001"
        private const val MBID = "22222222-2222-2222-2222-222222222222"
    }
}

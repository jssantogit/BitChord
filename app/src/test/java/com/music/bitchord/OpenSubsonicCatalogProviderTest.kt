package com.music.bitchord

import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import com.music.bitchord.data.catalog.provider.CanonicalCatalogSyncStore
import com.music.bitchord.data.catalog.provider.CanonicalRemoteCatalogSync
import com.music.bitchord.data.catalog.provider.ProviderAlbum
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.ProviderTrack
import com.music.bitchord.data.catalog.provider.RemoteCatalogProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicCatalogProviderTest {

    @Test
    fun passwordAuthUsesSaltedTokenAndNeverSendsPlainPassword() = runBlocking {
        val transport = FixtureTransport { endpoint, _ ->
            assertEquals("ping", endpoint)
            pingResponse()
        }
        val provider = OpenSubsonicProvider(
            config = OpenSubsonicAccountConfig(
                accountId = "home",
                serverUrl = "https://music.example.test",
                auth = OpenSubsonicAuth.Password(
                    username = "joao",
                    password = "super-secret",
                ),
            ),
            transport = transport,
            saltFactory = { "abcdef12" },
        )

        val health = provider.health()

        assertTrue(health.reachable)
        val params = transport.requests.single().parameters
        assertEquals("joao", params["u"])
        assertEquals("abcdef12", params["s"])
        assertEquals(md5("super-secretabcdef12"), params["t"])
        assertFalse(params.containsKey("p"))
        assertFalse(params.values.contains("super-secret"))
        assertEquals("1.16.1", params["v"])
        assertEquals("BitChord", params["c"])
        assertEquals("json", params["f"])
    }

    @Test
    fun apiKeyAuthDoesNotSendUsernameOrPasswordFields() = runBlocking {
        val transport = FixtureTransport { _, _ -> pingResponse() }
        val provider = OpenSubsonicProvider(
            config = OpenSubsonicAccountConfig(
                accountId = "home",
                serverUrl = "https://music.example.test/",
                auth = OpenSubsonicAuth.ApiKey("api-key-value"),
            ),
            transport = transport,
        )

        provider.health()

        val params = transport.requests.single().parameters
        assertEquals("api-key-value", params["apiKey"])
        assertFalse(params.containsKey("u"))
        assertFalse(params.containsKey("p"))
        assertFalse(params.containsKey("t"))
        assertFalse(params.containsKey("s"))
    }

    @Test
    fun capabilitiesAreExplicitAndDiscoverOpenSubsonicTranscoding() = runBlocking {
        val transport = FixtureTransport { endpoint, _ ->
            assertEquals("getOpenSubsonicExtensions", endpoint)
            """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "openSubsonic": true,
                    "openSubsonicExtensions": [
                      {"name":"transcoding","versions":[1]},
                      {"name":"apiKeyAuthentication","versions":[1]}
                    ]
                  }
                }
            """.trimIndent()
        }
        val provider = provider(transport)

        val capabilities = provider.capabilities()

        assertTrue(capabilities.librarySync)
        assertTrue(capabilities.healthCheck)
        assertTrue(capabilities.directPlay)
        assertTrue(capabilities.serverTranscoding)
        assertTrue(capabilities.apiKeyAuthentication)
    }

    @Test
    fun librarySnapshotPagesAlbumsAndMapsOpenSubsonicEvidence() = runBlocking {
        val transport = FixtureTransport { endpoint, parameters ->
            when (endpoint) {
                "getAlbumList2" -> when (parameters["offset"]) {
                    "0" -> albumListResponse("album-remote-1")
                    "1" -> albumListResponse()
                    else -> error("Unexpected offset ${parameters["offset"]}")
                }
                "getAlbum" -> {
                    assertEquals("album-remote-1", parameters["id"])
                    albumResponse()
                }
                else -> error("Unexpected endpoint $endpoint")
            }
        }
        val provider = OpenSubsonicProvider(
            config = config(),
            transport = transport,
            pageSize = 1,
            saltFactory = { "abcdef12" },
        )

        val snapshot = provider.librarySnapshot()

        assertTrue(snapshot.complete)
        assertEquals(1, snapshot.tracks.size)
        val track = snapshot.tracks.single()
        assertEquals("song-remote-9", track.externalTrackId)
        assertEquals("Paranoid Android", track.title)
        assertEquals("Radiohead", track.artistDisplay)
        assertEquals(383_000L, track.durationMs)
        assertEquals("recording-mbid", track.musicBrainzRecordingId)
        assertEquals("artist-remote-3", track.externalArtistId)
        assertEquals("audio/flac", track.contentType)
        assertEquals("flac", track.suffix)
        assertEquals(921, track.bitRateKbps)
        assertEquals(24, track.bitDepth)
        assertEquals(96_000, track.samplingRateHz)
        assertTrue(track.assetUri.startsWith("opensubsonic://asset/"))
        assertFalse(track.assetUri.contains("api-key-value"))

        val album = track.album
        requireNotNull(album)
        assertEquals("album-remote-1", album.externalAlbumId)
        assertEquals("OK Computer", album.title)
        assertEquals("Radiohead", album.artistDisplay)
        assertEquals("release-mbid", album.musicBrainzReleaseId)
        assertEquals("artist-remote-3", album.externalArtistId)
        assertTrue(album.artworkUri.orEmpty().startsWith("opensubsonic://artwork/"))

        assertEquals(
            listOf("getAlbumList2", "getAlbumList2", "getAlbum"),
            transport.requests.map(Request::endpoint),
        )
    }

    @Test
    fun syncReusesMusicBrainzCanonicalIdentityButKeepsProviderIdsAsAliases() = runBlocking {
        val store = FakeCatalogStore().apply {
            identities[identityKey("musicbrainz", "recording", "recording-mbid")] = "canonical-recording"
            identities[identityKey("musicbrainz", "release", "release-mbid")] = "canonical-release"
            assets[assetKey("opensubsonic:home", "stale-song")] = MediaAssetEntity(
                id = "stale-asset",
                recordingId = "old-recording",
                provider = "opensubsonic:home",
                sourceKey = "stale-song",
                uri = "opensubsonic://asset/stale",
                path = null,
                dateAddedSeconds = null,
                dateModifiedSeconds = null,
                artworkUri = null,
            )
        }
        val provider = SnapshotProvider(
            providerKey = "opensubsonic:home",
            snapshot = ProviderLibrarySnapshot(
                tracks = listOf(providerTrack()),
                complete = true,
            ),
        )
        val ids = ArrayDeque(listOf("new-asset-id"))

        val result = CanonicalRemoteCatalogSync.sync(
            provider = provider,
            store = store,
            idFactory = { ids.removeFirst() },
        )

        assertTrue(result is ProviderSyncResult.Success)
        result as ProviderSyncResult.Success
        assertEquals(1, result.syncedAssets)
        assertEquals(1, result.removedAssets)
        assertEquals("canonical-recording", store.recordings["canonical-recording"]?.id)
        assertEquals("canonical-release", store.releases["canonical-release"]?.id)
        assertEquals(
            "canonical-recording",
            store.identities[identityKey("opensubsonic:home", "recording", "song-remote-9")],
        )
        assertEquals(
            "canonical-release",
            store.identities[identityKey("opensubsonic:home", "release", "album-remote-1")],
        )
        val asset = store.assets[assetKey("opensubsonic:home", "song-remote-9")]
        requireNotNull(asset)
        assertEquals("new-asset-id", asset.id)
        assertEquals("canonical-recording", asset.recordingId)
        assertNotEquals("song-remote-9", asset.recordingId)
        assertNull(store.assets[assetKey("opensubsonic:home", "stale-song")])
        assertEquals(
            "old-recording",
            store.identities[identityKey("opensubsonic:home", "recording", "stale-song")],
        )
        assertTrue(
            store.releaseTracks.contains(
                ReleaseTrackEntity(
                    releaseId = "canonical-release",
                    recordingId = "canonical-recording",
                    trackNumber = 2,
                ),
            ),
        )
    }

    @Test
    fun networkFailureDoesNotTouchPreviouslySyncedCatalogState() = runBlocking {
        val store = FakeCatalogStore().apply {
            assets[assetKey("opensubsonic:home", "existing-song")] = MediaAssetEntity(
                id = "existing-asset",
                recordingId = "existing-recording",
                provider = "opensubsonic:home",
                sourceKey = "existing-song",
                uri = "opensubsonic://asset/existing",
                path = null,
                dateAddedSeconds = null,
                dateModifiedSeconds = null,
                artworkUri = null,
            )
        }
        val before = store.snapshot()
        val provider = object : RemoteCatalogProvider {
            override val providerKey = "opensubsonic:home"
            override suspend fun health() = ProviderHealth(reachable = false)
            override suspend fun capabilities() = ProviderCapabilities(
                librarySync = true,
                healthCheck = true,
                directPlay = true,
                serverTranscoding = false,
                apiKeyAuthentication = false,
            )
            override suspend fun librarySnapshot(): ProviderLibrarySnapshot {
                error("simulated network failure")
            }
        }

        val result = CanonicalRemoteCatalogSync.sync(provider, store)

        assertTrue(result is ProviderSyncResult.Failure)
        assertEquals(before, store.snapshot())
    }

    private fun provider(transport: OpenSubsonicTransport) = OpenSubsonicProvider(
        config = config(),
        transport = transport,
        saltFactory = { "abcdef12" },
    )

    private fun config() = OpenSubsonicAccountConfig(
        accountId = "home",
        serverUrl = "https://music.example.test",
        auth = OpenSubsonicAuth.ApiKey("api-key-value"),
    )

    private fun providerTrack() = ProviderTrack(
        externalTrackId = "song-remote-9",
        title = "Paranoid Android",
        artistDisplay = "Radiohead",
        externalArtistId = "artist-remote-3",
        durationMs = 383_000L,
        trackNumber = 2,
        musicBrainzRecordingId = "recording-mbid",
        album = ProviderAlbum(
            externalAlbumId = "album-remote-1",
            title = "OK Computer",
            artistDisplay = "Radiohead",
            externalArtistId = "artist-remote-3",
            musicBrainzReleaseId = "release-mbid",
            artworkUri = "opensubsonic://artwork/example",
        ),
        assetUri = "opensubsonic://asset/example",
        artworkUri = "opensubsonic://artwork/example",
        dateAddedSeconds = 1_700_000_000L,
        contentType = "audio/flac",
        suffix = "flac",
        bitRateKbps = 921,
        bitDepth = 24,
        samplingRateHz = 96_000,
    )

    private fun pingResponse() = """
        {
          "subsonic-response": {
            "status": "ok",
            "version": "1.16.1",
            "type": "navidrome",
            "serverVersion": "0.59.0",
            "openSubsonic": true
          }
        }
    """.trimIndent()

    private fun albumListResponse(vararg ids: String): String {
        val albums = ids.joinToString(",") { id ->
            """{"id":"$id","name":"OK Computer","artist":"Radiohead","songCount":1,"duration":383,"created":"2023-11-14T22:13:20Z"}"""
        }
        return """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "openSubsonic": true,
                "albumList2": {"album": [$albums]}
              }
            }
        """.trimIndent()
    }

    private fun albumResponse() = """
        {
          "subsonic-response": {
            "status": "ok",
            "version": "1.16.1",
            "openSubsonic": true,
            "album": {
              "id": "album-remote-1",
              "name": "OK Computer",
              "artist": "Radiohead",
              "displayArtist": "Radiohead",
              "artistId": "artist-remote-3",
              "coverArt": "cover-1",
              "songCount": 1,
              "duration": 383,
              "created": "2023-11-14T22:13:20Z",
              "musicBrainzId": "release-mbid",
              "song": [
                {
                  "id": "song-remote-9",
                  "isDir": false,
                  "title": "Paranoid Android",
                  "album": "OK Computer",
                  "albumId": "album-remote-1",
                  "artist": "Radiohead",
                  "displayArtist": "Radiohead",
                  "artistId": "artist-remote-3",
                  "track": 2,
                  "coverArt": "cover-1",
                  "duration": 383,
                  "contentType": "audio/flac",
                  "suffix": "flac",
                  "bitRate": 921,
                  "bitDepth": 24,
                  "samplingRate": 96000,
                  "created": "2023-11-14T22:13:20Z",
                  "musicBrainzId": "recording-mbid"
                }
              ]
            }
          }
        }
    """.trimIndent()

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class Request(
        val endpoint: String,
        val parameters: Map<String, String>,
    )

    private class FixtureTransport(
        private val responder: (String, Map<String, String>) -> String,
    ) : OpenSubsonicTransport {
        val requests = mutableListOf<Request>()

        override suspend fun get(endpoint: String, parameters: Map<String, String>): String {
            requests += Request(endpoint, parameters.toMap())
            return responder(endpoint, parameters)
        }
    }

    private class SnapshotProvider(
        override val providerKey: String,
        private val snapshot: ProviderLibrarySnapshot,
    ) : RemoteCatalogProvider {
        override suspend fun health() = ProviderHealth(reachable = true)
        override suspend fun capabilities() = ProviderCapabilities(
            librarySync = true,
            healthCheck = true,
            directPlay = true,
            serverTranscoding = true,
            apiKeyAuthentication = true,
        )
        override suspend fun librarySnapshot() = snapshot
    }

    private class FakeCatalogStore : CanonicalCatalogSyncStore {
        val recordings = linkedMapOf<String, RecordingEntity>()
        val releases = linkedMapOf<String, ReleaseEntity>()
        val releaseTracks = linkedSetOf<ReleaseTrackEntity>()
        val assets = linkedMapOf<String, MediaAssetEntity>()
        val identities = linkedMapOf<String, String>()

        override suspend fun mediaAsset(provider: String, sourceKey: String): MediaAssetEntity? =
            assets[assetKey(provider, sourceKey)]

        override suspend fun entityIdForExternalIdentity(
            provider: String,
            entityType: String,
            externalId: String,
        ): String? = identities[identityKey(provider, entityType, externalId)]

        override suspend fun upsertRecording(recording: RecordingEntity) {
            recordings[recording.id] = recording
        }

        override suspend fun upsertRelease(release: ReleaseEntity) {
            releases[release.id] = release
        }

        override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) {
            releaseTracks.removeAll { current ->
                current.releaseId == releaseTrack.releaseId &&
                    current.recordingId == releaseTrack.recordingId
            }
            releaseTracks += releaseTrack
        }

        override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) {
            assets[assetKey(mediaAsset.provider, mediaAsset.sourceKey)] = mediaAsset
        }

        override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) {
            identities[identityKey(identity.provider, identity.entityType, identity.externalId)] = identity.entityId
        }

        override suspend fun mediaAssetSourceKeys(provider: String): List<String> = assets.values
            .filter { it.provider == provider }
            .map(MediaAssetEntity::sourceKey)

        override suspend fun deleteMediaAsset(provider: String, sourceKey: String) {
            assets.remove(assetKey(provider, sourceKey))
        }

        fun snapshot() = CatalogSnapshot(
            recordings = recordings.toMap(),
            releases = releases.toMap(),
            releaseTracks = releaseTracks.toSet(),
            assets = assets.toMap(),
            identities = identities.toMap(),
        )
    }

    private data class CatalogSnapshot(
        val recordings: Map<String, RecordingEntity>,
        val releases: Map<String, ReleaseEntity>,
        val releaseTracks: Set<ReleaseTrackEntity>,
        val assets: Map<String, MediaAssetEntity>,
        val identities: Map<String, String>,
    )

    companion object {
        private fun identityKey(provider: String, entityType: String, externalId: String) =
            "$provider|$entityType|$externalId"

        private fun assetKey(provider: String, sourceKey: String) = "$provider|$sourceKey"
    }
}

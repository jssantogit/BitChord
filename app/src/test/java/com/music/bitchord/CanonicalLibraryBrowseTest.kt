package com.music.bitchord

import com.music.bitchord.data.catalog.CanonicalBrowseAssetRow
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalLibraryBrowseRepository
import com.music.bitchord.data.catalog.CanonicalLibraryBrowseStore
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
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountDraft
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryBrowseTest {

    @Test
    fun `synced provider tracks appear as canonical browse tracks`() = runTest {
        val store = InMemoryCatalogStore()
        val provider = MutableSnapshotProvider(
            providerKey = "opensubsonic:home",
            snapshot = snapshot(
                track(
                    externalTrackId = "server-track-9",
                    title = "Paranoid Android",
                    artist = "Radiohead",
                    durationMs = 383_000L,
                    trackNumber = 2,
                ),
            ),
        )
        val ids = ArrayDeque(listOf("recording-1", "release-1", "asset-1"))

        val result = CanonicalRemoteCatalogSync.sync(provider, store) { ids.removeFirst() }
        assertTrue(result is ProviderSyncResult.Success)

        val library = CanonicalLibraryBrowseRepository(store).observe(provider.providerKey).first()
        assertEquals(1, library.tracks.size)
        val row = library.tracks.single()
        assertEquals("recording-1", row.recordingId)
        assertEquals("Paranoid Android", row.title)
        assertEquals("Radiohead", row.artistDisplay)
        assertEquals(383_000L, row.durationMs)
        assertEquals("asset-1", row.availableAssets.single().mediaAssetId)
        assertEquals("opensubsonic:home", row.availableAssets.single().providerKey)
    }

    @Test
    fun `browse identity is canonical and does not expose provider source key or video id`() = runTest {
        val store = InMemoryCatalogStore()
        val provider = MutableSnapshotProvider(
            providerKey = "opensubsonic:home",
            snapshot = snapshot(track("provider-native-id", "Everything In Its Right Place", trackNumber = 1)),
        )
        val ids = ArrayDeque(listOf("canonical-recording", "canonical-release", "canonical-asset"))
        CanonicalRemoteCatalogSync.sync(provider, store) { ids.removeFirst() }

        val row = CanonicalLibraryBrowseRepository(store).observe(provider.providerKey).first().tracks.single()
        assertEquals("canonical-recording", row.recordingId)
        assertEquals("canonical-asset", row.availableAssets.single().mediaAssetId)
        assertNotEquals("provider-native-id", row.recordingId)
        assertNotEquals("provider-native-id", row.availableAssets.single().mediaAssetId)

        val trackFields = CanonicalBrowseTrack::class.java.declaredFields.map { it.name }.toSet()
        val assetFields = CanonicalBrowseMediaAsset::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("videoId" in trackFields)
        assertFalse("sourceKey" in trackFields)
        assertFalse("videoId" in assetFields)
        assertFalse("sourceKey" in assetFields)
    }

    @Test
    fun `cached browse remains readable when provider network later fails`() = runTest {
        val store = InMemoryCatalogStore()
        val provider = MutableSnapshotProvider(
            providerKey = "opensubsonic:home",
            snapshot = snapshot(track("server-track-1", "Offline Song", trackNumber = 1)),
        )
        val ids = ArrayDeque(listOf("recording-1", "release-1", "asset-1"))
        CanonicalRemoteCatalogSync.sync(provider, store) { ids.removeFirst() }
        assertEquals(1, provider.libraryCalls)

        provider.failReads = true
        val restartedBrowseRepository = CanonicalLibraryBrowseRepository(store)
        val library = restartedBrowseRepository.observe(provider.providerKey).first()

        assertEquals(listOf("Offline Song"), library.tracks.map { it.title })
        assertEquals(1, provider.libraryCalls)
    }

    @Test
    fun `provider with no persisted assets returns empty browse state`() = runTest {
        val library = CanonicalLibraryBrowseRepository(InMemoryCatalogStore())
            .observe("opensubsonic:empty")
            .first()

        assertTrue(library.tracks.isEmpty())
    }

    @Test
    fun `resync metadata update is reflected without changing canonical browse ids`() = runTest {
        val store = InMemoryCatalogStore()
        val provider = MutableSnapshotProvider(
            providerKey = "opensubsonic:home",
            snapshot = snapshot(track("server-track-1", "Old title", durationMs = 100_000L, trackNumber = 1)),
        )
        val ids = ArrayDeque(listOf("recording-1", "release-1", "asset-1"))
        CanonicalRemoteCatalogSync.sync(provider, store) { ids.removeFirst() }
        val repository = CanonicalLibraryBrowseRepository(store)
        val before = repository.observe(provider.providerKey).first().tracks.single()

        provider.snapshot = snapshot(
            track(
                externalTrackId = "server-track-1",
                title = "New title",
                durationMs = 120_000L,
                trackNumber = 1,
                artworkUri = "opensubsonic://artwork/new",
            ),
        )
        val result = CanonicalRemoteCatalogSync.sync(provider, store) {
            error("Resync of the same provider identities must not allocate new canonical ids")
        }
        assertTrue(result is ProviderSyncResult.Success)

        val after = repository.observe(provider.providerKey).first { library ->
            library.tracks.singleOrNull()?.title == "New title"
        }.tracks.single()
        assertEquals(before.recordingId, after.recordingId)
        assertEquals(before.availableAssets.single().mediaAssetId, after.availableAssets.single().mediaAssetId)
        assertEquals(120_000L, after.durationMs)
        assertEquals("opensubsonic://artwork/new", after.artworkUri)
    }

    @Test
    fun `forgetting account configuration does not delete canonical recordings or releases`() = runTest {
        val accountPersistence = FakeAccountPersistence()
        val credentials = FakeCredentialStore()
        val accountRepository = OpenSubsonicAccountRepository(
            persistence = accountPersistence,
            credentials = credentials,
            idFactory = { "home" },
        )
        val account = accountRepository.save(
            OpenSubsonicAccountDraft(
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
                secretReplacement = "fixture-key",
            ),
        )
        val providerKey = "opensubsonic:${account.accountId}"
        val store = InMemoryCatalogStore()
        val provider = MutableSnapshotProvider(
            providerKey = providerKey,
            snapshot = snapshot(track("server-track-1", "Persistent Song", trackNumber = 1)),
        )
        val ids = ArrayDeque(listOf("recording-1", "release-1", "asset-1"))
        CanonicalRemoteCatalogSync.sync(provider, store) { ids.removeFirst() }

        accountRepository.forget()

        assertNull(accountRepository.current())
        assertTrue(store.recordings.containsKey("recording-1"))
        assertTrue(store.releases.containsKey("release-1"))
        val cached = CanonicalLibraryBrowseRepository(store).observe(providerKey).first()
        assertEquals("Persistent Song", cached.tracks.single().title)
    }

    private fun snapshot(vararg tracks: ProviderTrack) = ProviderLibrarySnapshot(
        tracks = tracks.toList(),
        complete = true,
    )

    private fun track(
        externalTrackId: String,
        title: String,
        artist: String = "Radiohead",
        durationMs: Long = 240_000L,
        trackNumber: Int?,
        artworkUri: String = "opensubsonic://artwork/$externalTrackId",
        album: ProviderAlbum = ProviderAlbum(
            externalAlbumId = "server-album",
            title = "Canonical Album",
            artistDisplay = artist,
            externalArtistId = "server-artist",
            musicBrainzReleaseId = null,
            artworkUri = "opensubsonic://artwork/server-album",
        ),
    ) = ProviderTrack(
        externalTrackId = externalTrackId,
        title = title,
        artistDisplay = artist,
        externalArtistId = "server-artist",
        durationMs = durationMs,
        trackNumber = trackNumber,
        musicBrainzRecordingId = null,
        album = album,
        assetUri = "opensubsonic://asset/$externalTrackId",
        artworkUri = artworkUri,
        dateAddedSeconds = 1L,
        contentType = "audio/flac",
        suffix = "flac",
        bitRateKbps = 900,
        bitDepth = 24,
        samplingRateHz = 96_000,
    )

    private class MutableSnapshotProvider(
        override val providerKey: String,
        var snapshot: ProviderLibrarySnapshot,
    ) : RemoteCatalogProvider {
        var failReads = false
        var libraryCalls = 0

        override suspend fun health() = ProviderHealth(reachable = !failReads)

        override suspend fun capabilities() = ProviderCapabilities(
            librarySync = true,
            healthCheck = true,
            directPlay = true,
            serverTranscoding = true,
            apiKeyAuthentication = true,
        )

        override suspend fun librarySnapshot(): ProviderLibrarySnapshot {
            libraryCalls += 1
            if (failReads) error("network unavailable")
            return snapshot
        }
    }

    private class InMemoryCatalogStore : CanonicalCatalogSyncStore, CanonicalLibraryBrowseStore {
        val recordings = linkedMapOf<String, RecordingEntity>()
        val releases = linkedMapOf<String, ReleaseEntity>()
        private val releaseTracks = linkedMapOf<String, ReleaseTrackEntity>()
        private val assets = linkedMapOf<String, MediaAssetEntity>()
        private val identities = linkedMapOf<String, ExternalIdentityEntity>()
        private val revision = MutableStateFlow(0L)

        override fun observeTrackAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>> =
            revision.map { buildTrackRows(providerKey) }

        override suspend fun mediaAsset(provider: String, sourceKey: String): MediaAssetEntity? =
            assets[assetKey(provider, sourceKey)]

        override suspend fun entityIdForExternalIdentity(
            provider: String,
            entityType: String,
            externalId: String,
        ): String? = identities[identityKey(provider, entityType, externalId)]?.entityId

        override suspend fun upsertRecording(recording: RecordingEntity) {
            recordings[recording.id] = recording
            touch()
        }

        override suspend fun upsertRelease(release: ReleaseEntity) {
            releases[release.id] = release
            touch()
        }

        override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) {
            releaseTracks[releaseTrackKey(releaseTrack.releaseId, releaseTrack.recordingId)] = releaseTrack
            touch()
        }

        override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) {
            assets[assetKey(mediaAsset.provider, mediaAsset.sourceKey)] = mediaAsset
            touch()
        }

        override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) {
            identities[identityKey(identity.provider, identity.entityType, identity.externalId)] = identity
            touch()
        }

        override suspend fun mediaAssetSourceKeys(provider: String): List<String> = assets.values
            .filter { it.provider == provider }
            .map { it.sourceKey }

        override suspend fun deleteMediaAsset(provider: String, sourceKey: String) {
            assets.remove(assetKey(provider, sourceKey))
            touch()
        }

        private fun buildTrackRows(providerKey: String): List<CanonicalBrowseAssetRow> = assets.values
            .asSequence()
            .filter { it.provider == providerKey }
            .mapNotNull { asset ->
                val recording = recordings[asset.recordingId] ?: return@mapNotNull null
                CanonicalBrowseAssetRow(
                    recordingId = recording.id,
                    mediaAssetId = asset.id,
                    providerKey = asset.provider,
                    title = recording.title,
                    artistDisplay = recording.artistDisplay,
                    durationMs = recording.durationMs,
                    artworkUri = asset.artworkUri,
                )
            }
            .toList()

        private fun touch() {
            revision.value = revision.value + 1L
        }
    }

    private class FakeAccountPersistence : OpenSubsonicAccountPersistence {
        var record: OpenSubsonicAccountRecord? = null

        override fun read(): OpenSubsonicAccountRecord? = record

        override fun write(record: OpenSubsonicAccountRecord) {
            this.record = record
        }

        override fun clear() {
            record = null
        }
    }

    private class FakeCredentialStore : OpenSubsonicCredentialStore {
        private val values = mutableMapOf<String, String>()

        override fun read(accountId: String): String? = values[accountId]

        override fun write(accountId: String, credential: String) {
            values[accountId] = credential
        }

        override fun clear(accountId: String) {
            values.remove(accountId)
        }
    }

    private companion object {
        fun assetKey(provider: String, sourceKey: String) = "$provider|$sourceKey"
        fun identityKey(provider: String, entityType: String, externalId: String) =
            "$provider|$entityType|$externalId"
        fun releaseTrackKey(releaseId: String, recordingId: String) = "$releaseId|$recordingId"
    }
}

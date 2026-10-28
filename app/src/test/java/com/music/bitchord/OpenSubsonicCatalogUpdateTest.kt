package com.music.bitchord

import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import com.music.bitchord.data.catalog.provider.CanonicalCatalogSyncStore
import com.music.bitchord.data.catalog.provider.CanonicalReleaseTrackEdge
import com.music.bitchord.data.catalog.provider.CanonicalRemoteCatalogSync
import com.music.bitchord.data.catalog.provider.ProviderAlbum
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.ProviderTrack
import com.music.bitchord.data.catalog.provider.RemoteCatalogProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicCatalogUpdateTest {

    @Test
    fun resyncUpdatesMetadataWhileKeepingCanonicalAndAssetIdsStable() = runBlocking {
        val store = UpdateStore().apply {
            recordings["recording-1"] = RecordingEntity(
                id = "recording-1",
                title = "Old title",
                artistDisplay = "Radiohead",
                durationMs = 380_000L,
            )
            releases["release-1"] = ReleaseEntity(
                id = "release-1",
                title = "OK Computer",
                artistDisplay = "Radiohead",
                artworkUri = null,
            )
            identities[key("opensubsonic:home", "recording", "song-9")] = "recording-1"
            identities[key("opensubsonic:home", "release", "album-1")] = "release-1"
            assets[assetKey("opensubsonic:home", "song-9")] = MediaAssetEntity(
                id = "asset-1",
                recordingId = "recording-1",
                provider = "opensubsonic:home",
                sourceKey = "song-9",
                uri = "opensubsonic://asset/old",
                path = null,
                dateAddedSeconds = 1L,
                dateModifiedSeconds = null,
                artworkUri = null,
            )
        }
        val provider = SnapshotProvider(
            ProviderLibrarySnapshot(
                tracks = listOf(
                    ProviderTrack(
                        externalTrackId = "song-9",
                        title = "Paranoid Android",
                        artistDisplay = "Radiohead",
                        externalArtistId = "artist-3",
                        durationMs = 383_000L,
                        trackNumber = 2,
                        musicBrainzRecordingId = null,
                        album = ProviderAlbum(
                            externalAlbumId = "album-1",
                            title = "OK Computer",
                            artistDisplay = "Radiohead",
                            externalArtistId = "artist-3",
                            musicBrainzReleaseId = null,
                            artworkUri = "opensubsonic://artwork/new",
                        ),
                        assetUri = "opensubsonic://asset/new",
                        artworkUri = "opensubsonic://artwork/new",
                        dateAddedSeconds = 2L,
                        contentType = "audio/flac",
                        suffix = "flac",
                        bitRateKbps = 921,
                        bitDepth = 24,
                        samplingRateHz = 96_000,
                    ),
                ),
                complete = true,
            ),
        )

        val result = CanonicalRemoteCatalogSync.sync(
            provider = provider,
            store = store,
            idFactory = { error("Stable provider identities must prevent new canonical IDs") },
        )

        assertTrue(result is com.music.bitchord.data.catalog.provider.ProviderSyncResult.Success)
        assertEquals("Paranoid Android", store.recordings["recording-1"]?.title)
        assertEquals(383_000L, store.recordings["recording-1"]?.durationMs)
        assertEquals("release-1", store.releaseTracks.single().releaseId)
        assertEquals("recording-1", store.releaseTracks.single().recordingId)
        assertEquals(
            setOf(CanonicalReleaseTrackEdge("release-1", "recording-1")),
            store.ownedReleaseTrackEdges,
        )
        val updatedAsset = store.assets[assetKey("opensubsonic:home", "song-9")]
        requireNotNull(updatedAsset)
        assertEquals("asset-1", updatedAsset.id)
        assertEquals("recording-1", updatedAsset.recordingId)
        assertEquals("opensubsonic://asset/new", updatedAsset.uri)
        assertEquals(2L, updatedAsset.dateAddedSeconds)

        // An authoritative complete empty snapshot expires only this
        // provider's active edge ownership; canonical identities remain.
        CanonicalRemoteCatalogSync.applySnapshot(
            providerKey = "opensubsonic:home",
            snapshot = ProviderLibrarySnapshot(emptyList(), complete = true),
            store = store,
        )
        assertEquals(emptySet<CanonicalReleaseTrackEdge>(), store.ownedReleaseTrackEdges)
        assertEquals("recording-1", store.identities[key("opensubsonic:home", "recording", "song-9")])
    }

    private class SnapshotProvider(
        private val snapshot: ProviderLibrarySnapshot,
    ) : RemoteCatalogProvider {
        override val providerKey: String = "opensubsonic:home"
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

    private class UpdateStore : CanonicalCatalogSyncStore {
        val recordings = linkedMapOf<String, RecordingEntity>()
        val releases = linkedMapOf<String, ReleaseEntity>()
        val releaseTracks = linkedSetOf<ReleaseTrackEntity>()
        val ownedReleaseTrackEdges = linkedSetOf<CanonicalReleaseTrackEdge>()
        val assets = linkedMapOf<String, MediaAssetEntity>()
        val identities = linkedMapOf<String, String>()

        override suspend fun mediaAsset(provider: String, sourceKey: String): MediaAssetEntity? =
            assets[assetKey(provider, sourceKey)]

        override suspend fun entityIdForExternalIdentity(
            provider: String,
            entityType: String,
            externalId: String,
        ): String? = identities[key(provider, entityType, externalId)]


        override suspend fun musicBrainzRecordingIdsForEntity(recordingId: String): List<String> =
            identities.entries
                .filter { (key, owner) -> key.startsWith("musicbrainz|recording|") && owner == recordingId }
                .map { (key, _) -> key.removePrefix("musicbrainz|recording|") }

        override suspend fun claimMusicBrainzRecordingIdentity(mbid: String, recordingId: String): Boolean {
            val identityKey = "musicbrainz|recording|$mbid"
            val owner = identities[identityKey]
            if (musicBrainzRecordingIdsForEntity(recordingId).any { it != mbid } ||
                (owner != null && owner != recordingId)
            ) return false
            if (owner == null) identities[identityKey] = recordingId
            return true
        }

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

        override suspend fun reconcileReleaseTrackSources(
            providerKey: String, edges: List<CanonicalReleaseTrackEdge>,
        ) {
            require(providerKey == "opensubsonic:home")
            ownedReleaseTrackEdges.clear()
            ownedReleaseTrackEdges.addAll(edges)
        }

        override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) {
            assets[assetKey(mediaAsset.provider, mediaAsset.sourceKey)] = mediaAsset
        }

        override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) {
            identities[key(identity.provider, identity.entityType, identity.externalId)] = identity.entityId
        }

        override suspend fun mediaAssetSourceKeys(provider: String): List<String> = assets.values
            .filter { asset -> asset.provider == provider }
            .map(MediaAssetEntity::sourceKey)

        override suspend fun deleteMediaAsset(provider: String, sourceKey: String) {
            assets.remove(assetKey(provider, sourceKey))
        }
    }

    companion object {
        private fun key(provider: String, entityType: String, externalId: String) =
            "$provider|$entityType|$externalId"

        private fun assetKey(provider: String, sourceKey: String) = "$provider|$sourceKey"
    }
}

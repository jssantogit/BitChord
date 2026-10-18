package com.music.bitchord

import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import com.music.bitchord.data.catalog.provider.CanonicalCatalogSyncStore
import com.music.bitchord.data.catalog.provider.CanonicalRemoteCatalogSync
import com.music.bitchord.data.catalog.provider.ProviderAlbum
import com.music.bitchord.data.catalog.provider.ProviderArtist
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.ProviderTrack
import com.music.bitchord.data.catalog.provider.RemoteCatalogProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalReleaseArtistCreditsContractTest {

    @Test
    fun releaseCreditsPreserveProviderOrderAndRemainStableAcrossResync() = runBlocking {
        val store = ReleaseCreditStore()
        var sequence = 0
        val provider = SnapshotProvider(
            ProviderLibrarySnapshot(
                tracks = listOf(
                    ProviderTrack(
                        externalTrackId = "song-1",
                        title = "Collaboration",
                        artistDisplay = "Artist A & Artist B",
                        externalArtistId = "artist-a",
                        durationMs = 180_000L,
                        trackNumber = 1,
                        musicBrainzRecordingId = null,
                        album = ProviderAlbum(
                            externalAlbumId = "album-1",
                            title = "Album",
                            artistDisplay = "Artist B & Artist A",
                            externalArtistId = "artist-b",
                            musicBrainzReleaseId = null,
                            artworkUri = null,
                            artists = listOf(
                                ProviderArtist("artist-b", "Artist B", "mb-artist-b"),
                                ProviderArtist("artist-a", "Artist A", "mb-artist-a"),
                            ),
                        ),
                        assetUri = "opensubsonic://asset/song-1",
                        artworkUri = null,
                        dateAddedSeconds = null,
                        contentType = "audio/flac",
                        suffix = "flac",
                        bitRateKbps = null,
                        bitDepth = null,
                        samplingRateHz = null,
                        artists = listOf(
                            ProviderArtist("artist-a", "Artist A", "mb-artist-a"),
                            ProviderArtist("artist-b", "Artist B", "mb-artist-b"),
                        ),
                    ),
                ),
                complete = true,
            ),
        )

        CanonicalRemoteCatalogSync.sync(provider, store) { "generated-${++sequence}" }
        val releaseId = store.identities.getValue("opensubsonic:home|release|album-1")
        val firstCredits = store.releaseCredits.getValue(releaseId).toList()
        val generatedAfterFirstSync = sequence

        CanonicalRemoteCatalogSync.sync(provider, store) {
            error("Resync of stable Recording/Release/Artist aliases must not allocate a new canonical id")
        }

        assertEquals(generatedAfterFirstSync, sequence)
        assertEquals(firstCredits, store.releaseCredits.getValue(releaseId))
        assertEquals(listOf(0, 1), firstCredits.map(ReleaseArtistCreditEntity::position))
        assertEquals(
            listOf("Artist B", "Artist A"),
            firstCredits.map { credit -> store.artists.getValue(credit.artistId).name },
        )
        assertEquals(2, store.artists.size)
    }

    private class SnapshotProvider(
        private val snapshot: ProviderLibrarySnapshot,
    ) : RemoteCatalogProvider {
        override val providerKey = "opensubsonic:home"
        override suspend fun health() = ProviderHealth(reachable = true)
        override suspend fun capabilities() = ProviderCapabilities(true, true, true, true, true)
        override suspend fun librarySnapshot() = snapshot
    }

    private class ReleaseCreditStore : CanonicalCatalogSyncStore {
        val recordings = linkedMapOf<String, RecordingEntity>()
        val releases = linkedMapOf<String, ReleaseEntity>()
        val releaseTracks = linkedSetOf<ReleaseTrackEntity>()
        val artists = linkedMapOf<String, ArtistEntity>()
        val recordingCredits = linkedMapOf<String, List<RecordingArtistCreditEntity>>()
        val releaseCredits = linkedMapOf<String, List<ReleaseArtistCreditEntity>>()
        val assets = linkedMapOf<String, MediaAssetEntity>()
        val identities = linkedMapOf<String, String>()

        override suspend fun mediaAsset(provider: String, sourceKey: String) = assets["$provider|$sourceKey"]

        override suspend fun entityIdForExternalIdentity(
            provider: String,
            entityType: String,
            externalId: String,
        ) = identities["$provider|$entityType|$externalId"]


        override suspend fun musicBrainzRecordingIdsForEntity(recordingId: String): List<String> =
            identities.entries.filter { (key, owner) ->
                key.startsWith("musicbrainz|recording|") && owner == recordingId
            }.map { (key, _) -> key.removePrefix("musicbrainz|recording|") }

        override suspend fun claimMusicBrainzRecordingIdentity(mbid: String, recordingId: String): Boolean {
            val key = "musicbrainz|recording|$mbid"
            val owner = identities[key]
            if (musicBrainzRecordingIdsForEntity(recordingId).any { it != mbid } ||
                (owner != null && owner != recordingId)
            ) return false
            if (owner == null) identities[key] = recordingId
            return true
        }

        override suspend fun upsertRecording(recording: RecordingEntity) {
            recordings[recording.id] = recording
        }

        override suspend fun upsertRelease(release: ReleaseEntity) {
            releases[release.id] = release
        }

        override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) {
            releaseTracks += releaseTrack
        }

        override suspend fun upsertArtist(artist: ArtistEntity) {
            artists[artist.id] = artist
        }

        override suspend fun replaceRecordingArtistCredits(
            recordingId: String,
            credits: List<RecordingArtistCreditEntity>,
        ) {
            recordingCredits[recordingId] = credits.toList()
        }

        override suspend fun replaceReleaseArtistCredits(
            releaseId: String,
            credits: List<ReleaseArtistCreditEntity>,
        ) {
            releaseCredits[releaseId] = credits.toList()
        }

        override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) {
            assets["${mediaAsset.provider}|${mediaAsset.sourceKey}"] = mediaAsset
        }

        override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) {
            identities["${identity.provider}|${identity.entityType}|${identity.externalId}"] = identity.entityId
        }

        override suspend fun mediaAssetSourceKeys(provider: String) = assets.values
            .filter { it.provider == provider }
            .map(MediaAssetEntity::sourceKey)

        override suspend fun deleteMediaAsset(provider: String, sourceKey: String) {
            assets.remove("$provider|$sourceKey")
        }
    }
}

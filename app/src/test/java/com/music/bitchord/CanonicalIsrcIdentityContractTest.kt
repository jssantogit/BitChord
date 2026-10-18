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
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.ProviderTrack
import com.music.bitchord.data.catalog.provider.RemoteCatalogProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalIsrcIdentityContractTest {

    @Test
    fun newIsrcIsPersistedAndResyncIsIdempotent() = runBlocking {
        val store = Store()
        var sequence = 0
        val provider = provider("opensubsonic:home", track("song-1", isrcs = listOf(ISRC_A)))

        val first = CanonicalRemoteCatalogSync.sync(provider, store) { "generated-${++sequence}" }
        val recordingId = store.identity("opensubsonic:home", "recording", "song-1")!!
        val assetId = store.assets.getValue("opensubsonic:home|song-1").id
        val generatedAfterFirst = sequence
        val second = CanonicalRemoteCatalogSync.sync(provider, store) { "generated-${++sequence}" }

        assertEquals(recordingId, store.identity("isrc", "recording", ISRC_A))
        assertEquals(recordingId, store.identity("opensubsonic:home", "recording", "song-1"))
        assertEquals(assetId, store.assets.getValue("opensubsonic:home|song-1").id)
        assertEquals(generatedAfterFirst, sequence)
        assertEquals(0, (first as ProviderSyncResult.Success).identityConflicts)
        assertEquals(0, (second as ProviderSyncResult.Success).identityConflicts)
    }

    @Test
    fun newProviderReusesCanonicalRecordingByUnambiguousKnownIsrc() = runBlocking {
        val store = Store()
        var sequence = 0
        CanonicalRemoteCatalogSync.sync(
            provider("opensubsonic:a", track("a-song", isrcs = listOf(ISRC_A))),
            store,
        ) { "generated-${++sequence}" }
        val firstRecording = store.identity("opensubsonic:a", "recording", "a-song")!!

        CanonicalRemoteCatalogSync.sync(
            provider("opensubsonic:b", track("b-song", isrcs = listOf(ISRC_A))),
            store,
        ) { "generated-${++sequence}" }

        assertEquals(firstRecording, store.identity("opensubsonic:b", "recording", "b-song"))
        assertEquals(firstRecording, store.assets.getValue("opensubsonic:b|b-song").recordingId)
        assertEquals(1, store.recordings.size)
    }

    @Test
    fun twoKnownIsrcsForSameRecordingMayReuseIt() = runBlocking {
        val store = Store()
        store.recordings["r1"] = RecordingEntity("r1", "Existing", "Artist", 1L)
        store.claim("isrc", "recording", ISRC_A, "r1")
        store.claim("isrc", "recording", ISRC_B, "r1")

        CanonicalRemoteCatalogSync.sync(
            provider("opensubsonic:new", track("song", isrcs = listOf(ISRC_A, ISRC_B))),
            store,
        ) { "new-id" }

        assertEquals("r1", store.identity("opensubsonic:new", "recording", "song"))
    }

    @Test
    fun divergentKnownIsrcClaimsNeverMergeOrOverwrite() = runBlocking {
        val store = Store()
        store.recordings["r1"] = RecordingEntity("r1", "One", "Artist", 1L)
        store.recordings["r2"] = RecordingEntity("r2", "Two", "Artist", 1L)
        store.claim("isrc", "recording", ISRC_A, "r1")
        store.claim("isrc", "recording", ISRC_B, "r2")

        val result = CanonicalRemoteCatalogSync.sync(
            provider("opensubsonic:new", track("song", isrcs = listOf(ISRC_A, ISRC_B))),
            store,
        ) { "r3" } as ProviderSyncResult.Success

        val selected = store.identity("opensubsonic:new", "recording", "song")!!
        assertNotEquals("r1", selected)
        assertNotEquals("r2", selected)
        assertEquals("r1", store.identity("isrc", "recording", ISRC_A))
        assertEquals("r2", store.identity("isrc", "recording", ISRC_B))
        assertTrue(result.identityConflicts > 0)
        assertEquals(setOf("r1", "r2", "r3"), store.recordings.keys)
    }

    @Test
    fun providerAliasStaysStableWhenIncomingIsrcConflicts() = runBlocking {
        val store = Store()
        store.recordings["stable"] = RecordingEntity("stable", "Stable", "Artist", 1L)
        store.recordings["other"] = RecordingEntity("other", "Other", "Artist", 1L)
        store.claim("opensubsonic:home", "recording", "song", "stable")
        store.claim("isrc", "recording", ISRC_A, "other")

        val result = CanonicalRemoteCatalogSync.sync(
            provider("opensubsonic:home", track("song", isrcs = listOf(ISRC_A))),
            store,
        ) { "asset-stable" } as ProviderSyncResult.Success

        assertEquals("stable", store.identity("opensubsonic:home", "recording", "song"))
        assertEquals("other", store.identity("isrc", "recording", ISRC_A))
        assertTrue(result.identityConflicts > 0)
    }

    @Test
    fun trustedMusicBrainzMappingWinsConflictingIsrcMapping() = runBlocking {
        val store = Store()
        store.recordings["mb-recording"] = RecordingEntity("mb-recording", "MB", "Artist", 1L)
        store.recordings["isrc-recording"] = RecordingEntity("isrc-recording", "ISRC", "Artist", 1L)
        store.claim("musicbrainz", "recording", MBID_A, "mb-recording")
        store.claim("isrc", "recording", ISRC_A, "isrc-recording")

        val result = CanonicalRemoteCatalogSync.sync(
            provider(
                "opensubsonic:new",
                track("song", mbid = MBID_A, isrcs = listOf(ISRC_A)),
            ),
            store,
        ) { "asset-mb" } as ProviderSyncResult.Success

        assertEquals("mb-recording", store.identity("opensubsonic:new", "recording", "song"))
        assertEquals("isrc-recording", store.identity("isrc", "recording", ISRC_A))
        assertTrue(result.identityConflicts > 0)
    }

    @Test
    fun staleAssetPruningDoesNotEraseHistoricalIsrcEvidence() = runBlocking {
        val store = Store()
        var sequence = 0
        CanonicalRemoteCatalogSync.sync(
            provider("opensubsonic:home", track("song", isrcs = listOf(ISRC_A))),
            store,
        ) { "generated-${++sequence}" }
        val recordingId = store.identity("isrc", "recording", ISRC_A)

        CanonicalRemoteCatalogSync.sync(
            SnapshotProvider("opensubsonic:home", ProviderLibrarySnapshot(emptyList(), complete = true)),
            store,
        ) { error("stale pruning must not allocate identity") }

        assertTrue(store.assets.isEmpty())
        assertEquals(recordingId, store.identity("isrc", "recording", ISRC_A))
    }

    private fun provider(providerKey: String, track: ProviderTrack) = SnapshotProvider(
        providerKey,
        ProviderLibrarySnapshot(listOf(track), complete = true),
    )

    private fun track(
        id: String,
        mbid: String? = null,
        isrcs: List<String> = emptyList(),
    ) = ProviderTrack(
        externalTrackId = id,
        title = "Track $id",
        artistDisplay = "Artist",
        externalArtistId = null,
        durationMs = 180_000L,
        trackNumber = 1,
        musicBrainzRecordingId = mbid,
        album = null,
        assetUri = "opensubsonic://asset/$id",
        artworkUri = null,
        dateAddedSeconds = null,
        contentType = "audio/flac",
        suffix = "flac",
        bitRateKbps = null,
        bitDepth = null,
        samplingRateHz = null,
        artists = emptyList(),
        isrcs = isrcs,
    )

    private class SnapshotProvider(
        override val providerKey: String,
        private val snapshot: ProviderLibrarySnapshot,
    ) : RemoteCatalogProvider {
        override suspend fun health() = ProviderHealth(reachable = true)
        override suspend fun capabilities() = ProviderCapabilities(true, true, true, true, true)
        override suspend fun librarySnapshot() = snapshot
    }

    private class Store : CanonicalCatalogSyncStore {
        val recordings = linkedMapOf<String, RecordingEntity>()
        val releases = linkedMapOf<String, ReleaseEntity>()
        val releaseTracks = linkedSetOf<ReleaseTrackEntity>()
        val artists = linkedMapOf<String, ArtistEntity>()
        val recordingCredits = linkedMapOf<String, List<RecordingArtistCreditEntity>>()
        val releaseCredits = linkedMapOf<String, List<ReleaseArtistCreditEntity>>()
        val assets = linkedMapOf<String, MediaAssetEntity>()
        val identities = linkedMapOf<String, String>()

        fun claim(provider: String, entityType: String, externalId: String, entityId: String) {
            identities[key(provider, entityType, externalId)] = entityId
        }

        fun identity(provider: String, entityType: String, externalId: String): String? =
            identities[key(provider, entityType, externalId)]

        override suspend fun mediaAsset(provider: String, sourceKey: String) = assets["$provider|$sourceKey"]
        override suspend fun entityIdForExternalIdentity(provider: String, entityType: String, externalId: String) =
            identity(provider, entityType, externalId)

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

        override suspend fun upsertRecording(recording: RecordingEntity) { recordings[recording.id] = recording }
        override suspend fun upsertRelease(release: ReleaseEntity) { releases[release.id] = release }
        override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) { releaseTracks += releaseTrack }
        override suspend fun upsertArtist(artist: ArtistEntity) { artists[artist.id] = artist }
        override suspend fun replaceRecordingArtistCredits(recordingId: String, credits: List<RecordingArtistCreditEntity>) {
            recordingCredits[recordingId] = credits.toList()
        }
        override suspend fun replaceReleaseArtistCredits(releaseId: String, credits: List<ReleaseArtistCreditEntity>) {
            releaseCredits[releaseId] = credits.toList()
        }
        override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) {
            assets["${mediaAsset.provider}|${mediaAsset.sourceKey}"] = mediaAsset
        }
        override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) {
            identities[key(identity.provider, identity.entityType, identity.externalId)] = identity.entityId
        }
        override suspend fun mediaAssetSourceKeys(provider: String) = assets.values
            .filter { it.provider == provider }
            .map(MediaAssetEntity::sourceKey)
        override suspend fun deleteMediaAsset(provider: String, sourceKey: String) {
            assets.remove("$provider|$sourceKey")
        }

        private fun key(provider: String, entityType: String, externalId: String) =
            "$provider|$entityType|$externalId"
    }

    companion object {
        private const val ISRC_A = "USS1Z9900001"
        private const val ISRC_B = "GBABC2400001"
        private const val MBID_A = "11111111-1111-1111-1111-111111111111"
    }
}

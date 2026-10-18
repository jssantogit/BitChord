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
import com.music.bitchord.data.catalog.provider.ProviderArtist
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderLibrarySnapshot
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.ProviderTrack
import com.music.bitchord.data.catalog.provider.RemoteCatalogProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalArtistCreditsContractTest {

    @Test
    fun sameMusicBrainzArtistAcrossDifferentProviderAliasesUsesOneCanonicalArtist() = runBlocking {
        val store = ArtistStore()
        var sequence = 0
        val ids = { "generated-${++sequence}" }

        CanonicalRemoteCatalogSync.sync(
            provider = SnapshotProvider(
                providerKey = "opensubsonic:home",
                snapshot = ProviderLibrarySnapshot(
                    tracks = listOf(
                        track(
                            songId = "song-home",
                            recordingMbid = "recording-home",
                            artists = listOf(
                                ProviderArtist(
                                    externalArtistId = "artist-home",
                                    name = "Radiohead",
                                    musicBrainzArtistId = "artist-mbid-radiohead",
                                ),
                            ),
                        ),
                    ),
                    complete = true,
                ),
            ),
            store = store,
            idFactory = ids,
        )
        CanonicalRemoteCatalogSync.sync(
            provider = SnapshotProvider(
                providerKey = "opensubsonic:other",
                snapshot = ProviderLibrarySnapshot(
                    tracks = listOf(
                        track(
                            songId = "song-other",
                            recordingMbid = "recording-other",
                            artists = listOf(
                                ProviderArtist(
                                    externalArtistId = "artist-other",
                                    name = "Radiohead",
                                    musicBrainzArtistId = "artist-mbid-radiohead",
                                ),
                            ),
                        ),
                    ),
                    complete = true,
                ),
            ),
            store = store,
            idFactory = ids,
        )

        val musicBrainzArtist = store.identities[key("musicbrainz", "artist", "artist-mbid-radiohead")]
        requireNotNull(musicBrainzArtist)
        assertEquals(
            musicBrainzArtist,
            store.identities[key("opensubsonic:home", "artist", "artist-home")],
        )
        assertEquals(
            musicBrainzArtist,
            store.identities[key("opensubsonic:other", "artist", "artist-other")],
        )
        assertEquals(1, store.artists.size)
    }

    @Test
    fun sameDisplayNameWithDifferentIdentityEvidenceDoesNotMergeArtists() = runBlocking {
        val store = ArtistStore()
        var sequence = 0

        CanonicalRemoteCatalogSync.sync(
            provider = SnapshotProvider(
                providerKey = "opensubsonic:one",
                snapshot = ProviderLibrarySnapshot(
                    tracks = listOf(
                        track(
                            songId = "song-one",
                            recordingMbid = "recording-one",
                            artists = listOf(ProviderArtist("artist-one", "Alex", "mb-artist-one")),
                        ),
                    ),
                    complete = true,
                ),
            ),
            store = store,
            idFactory = { "id-${++sequence}" },
        )
        CanonicalRemoteCatalogSync.sync(
            provider = SnapshotProvider(
                providerKey = "opensubsonic:two",
                snapshot = ProviderLibrarySnapshot(
                    tracks = listOf(
                        track(
                            songId = "song-two",
                            recordingMbid = "recording-two",
                            artists = listOf(ProviderArtist("artist-two", "Alex", "mb-artist-two")),
                        ),
                    ),
                    complete = true,
                ),
            ),
            store = store,
            idFactory = { "id-${++sequence}" },
        )

        val first = store.identities[key("musicbrainz", "artist", "mb-artist-one")]
        val second = store.identities[key("musicbrainz", "artist", "mb-artist-two")]
        requireNotNull(first)
        requireNotNull(second)
        assertNotEquals(first, second)
        assertEquals(2, store.artists.size)
        assertEquals(listOf("Alex", "Alex"), store.artists.values.map(ArtistEntity::name))
    }

    @Test
    fun providerArtistAliasAndOrderedCreditsStayStableAcrossResync() = runBlocking {
        val store = ArtistStore()
        var sequence = 0
        val provider = SnapshotProvider(
            providerKey = "opensubsonic:home",
            snapshot = ProviderLibrarySnapshot(
                tracks = listOf(
                    track(
                        songId = "song-1",
                        recordingMbid = null,
                        artistDisplay = "Artist A & Artist B",
                        artists = listOf(
                            ProviderArtist("artist-a", "Artist A", null),
                            ProviderArtist("artist-b", "Artist B", null),
                        ),
                    ),
                ),
                complete = true,
            ),
        )

        CanonicalRemoteCatalogSync.sync(provider, store) { "id-${++sequence}" }
        val generatedAfterFirstSync = sequence
        val recordingId = store.identities[key("opensubsonic:home", "recording", "song-1")]
        requireNotNull(recordingId)
        val firstCredits = store.recordingCredits.getValue(recordingId).toList()

        CanonicalRemoteCatalogSync.sync(provider, store) { "id-${++sequence}" }

        assertEquals(generatedAfterFirstSync, sequence)
        assertEquals(firstCredits, store.recordingCredits.getValue(recordingId))
        assertEquals(listOf(0, 1), firstCredits.map(RecordingArtistCreditEntity::position))
        assertEquals(
            listOf("Artist A", "Artist B"),
            firstCredits.map { credit -> store.artists.getValue(credit.artistId).name },
        )
        assertEquals(2, store.artists.size)
    }

    @Test
    fun missingStructuredEvidenceLeavesArtistDisplayOnly() = runBlocking {
        val store = ArtistStore()
        var sequence = 0

        CanonicalRemoteCatalogSync.sync(
            provider = SnapshotProvider(
                providerKey = "opensubsonic:home",
                snapshot = ProviderLibrarySnapshot(
                    tracks = listOf(
                        track(
                            songId = "song-display-only",
                            recordingMbid = null,
                            artistDisplay = "A & B feat. C",
                            artists = emptyList(),
                            externalArtistId = null,
                        ),
                    ),
                    complete = true,
                ),
            ),
            store = store,
            idFactory = { "id-${++sequence}" },
        )

        val recordingId = store.identities.getValue(
            key("opensubsonic:home", "recording", "song-display-only"),
        )
        assertEquals("A & B feat. C", store.recordings.getValue(recordingId).artistDisplay)
        assertTrue(store.artists.isEmpty())
        assertTrue(store.recordingCredits[recordingId].isNullOrEmpty())
    }

    @Test
    fun outageAndStaleAvailabilityNeverEraseArtistIdentityOrCredits() = runBlocking {
        val store = ArtistStore()
        var sequence = 0
        val firstProvider = SnapshotProvider(
            providerKey = "opensubsonic:home",
            snapshot = ProviderLibrarySnapshot(
                tracks = listOf(
                    track(
                        songId = "song-1",
                        recordingMbid = null,
                        artists = listOf(ProviderArtist("artist-1", "Artist", null)),
                    ),
                ),
                complete = true,
            ),
        )
        CanonicalRemoteCatalogSync.sync(firstProvider, store) { "id-${++sequence}" }
        val recordingId = store.identities.getValue(key("opensubsonic:home", "recording", "song-1"))
        val artistId = store.identities.getValue(key("opensubsonic:home", "artist", "artist-1"))
        val creditsBefore = store.recordingCredits.getValue(recordingId).toList()

        val outage = CanonicalRemoteCatalogSync.sync(
            provider = FailingProvider("opensubsonic:home"),
            store = store,
            idFactory = { error("Outage must not allocate identities") },
        )
        assertTrue(outage is ProviderSyncResult.Failure)
        assertEquals(artistId, store.identities[key("opensubsonic:home", "artist", "artist-1")])
        assertEquals(creditsBefore, store.recordingCredits[recordingId])

        CanonicalRemoteCatalogSync.sync(
            provider = SnapshotProvider(
                providerKey = "opensubsonic:home",
                snapshot = ProviderLibrarySnapshot(tracks = emptyList(), complete = true),
            ),
            store = store,
            idFactory = { error("Pruning stale availability must not allocate identities") },
        )

        assertTrue(store.assets.isEmpty())
        assertEquals(artistId, store.identities[key("opensubsonic:home", "artist", "artist-1")])
        assertEquals(creditsBefore, store.recordingCredits[recordingId])
        assertTrue(store.artists.containsKey(artistId))
    }

    @Test
    fun forgettingAccountDoesNotTouchCanonicalArtistState() = runBlocking {
        val store = ArtistStore()
        var sequence = 0
        CanonicalRemoteCatalogSync.sync(
            SnapshotProvider(
                "opensubsonic:home",
                ProviderLibrarySnapshot(
                    tracks = listOf(
                        track(
                            songId = "song-1",
                            recordingMbid = null,
                            artists = listOf(ProviderArtist("artist-1", "Artist", null)),
                        ),
                    ),
                    complete = true,
                ),
            ),
            store,
        ) { "id-${++sequence}" }
        val before = store.artistSnapshot()

        val persistence = MemoryAccountPersistence(
            OpenSubsonicAccountRecord(
                accountId = "home",
                serverUrl = "https://music.example.test",
                authMode = OpenSubsonicAuthMode.API_KEY,
            ),
        )
        val credentials = MemoryCredentialStore(mutableMapOf("home" to "secret"))
        OpenSubsonicAccountRepository(persistence, credentials).forget()

        assertEquals(before, store.artistSnapshot())
        assertEquals(null, persistence.read())
        assertTrue(credentials.values.isEmpty())
    }

    private fun track(
        songId: String,
        recordingMbid: String?,
        artistDisplay: String = "Radiohead",
        artists: List<ProviderArtist>,
        externalArtistId: String? = artists.firstOrNull()?.externalArtistId,
    ) = ProviderTrack(
        externalTrackId = songId,
        title = "Track $songId",
        artistDisplay = artistDisplay,
        externalArtistId = externalArtistId,
        durationMs = 180_000L,
        trackNumber = 1,
        musicBrainzRecordingId = recordingMbid,
        album = null,
        assetUri = "opensubsonic://asset/$songId",
        artworkUri = null,
        dateAddedSeconds = null,
        contentType = "audio/flac",
        suffix = "flac",
        bitRateKbps = null,
        bitDepth = null,
        samplingRateHz = null,
        artists = artists,
    )

    private class SnapshotProvider(
        override val providerKey: String,
        private val snapshot: ProviderLibrarySnapshot,
    ) : RemoteCatalogProvider {
        override suspend fun health() = ProviderHealth(reachable = true)
        override suspend fun capabilities() = ProviderCapabilities(true, true, true, true, true)
        override suspend fun librarySnapshot() = snapshot
    }

    private class FailingProvider(
        override val providerKey: String,
    ) : RemoteCatalogProvider {
        override suspend fun health() = ProviderHealth(reachable = false)
        override suspend fun capabilities() = ProviderCapabilities(true, true, true, false, true)
        override suspend fun librarySnapshot(): ProviderLibrarySnapshot = error("offline")
    }

    private class ArtistStore : CanonicalCatalogSyncStore {
        val recordings = linkedMapOf<String, RecordingEntity>()
        val releases = linkedMapOf<String, ReleaseEntity>()
        val releaseTracks = linkedSetOf<ReleaseTrackEntity>()
        val artists = linkedMapOf<String, ArtistEntity>()
        val recordingCredits = linkedMapOf<String, List<RecordingArtistCreditEntity>>()
        val releaseCredits = linkedMapOf<String, List<ReleaseArtistCreditEntity>>()
        val assets = linkedMapOf<String, MediaAssetEntity>()
        val identities = linkedMapOf<String, String>()

        override suspend fun mediaAsset(provider: String, sourceKey: String) = assets[assetKey(provider, sourceKey)]
        override suspend fun entityIdForExternalIdentity(provider: String, entityType: String, externalId: String) =
            identities[key(provider, entityType, externalId)]
        override suspend fun upsertRecording(recording: RecordingEntity) { recordings[recording.id] = recording }
        override suspend fun upsertRelease(release: ReleaseEntity) { releases[release.id] = release }
        override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) { releaseTracks += releaseTrack }
        override suspend fun upsertArtist(artist: ArtistEntity) { artists[artist.id] = artist }
        override suspend fun replaceRecordingArtistCredits(
            recordingId: String,
            credits: List<RecordingArtistCreditEntity>,
        ) { recordingCredits[recordingId] = credits.toList() }
        override suspend fun replaceReleaseArtistCredits(
            releaseId: String,
            credits: List<ReleaseArtistCreditEntity>,
        ) { releaseCredits[releaseId] = credits.toList() }
        override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) {
            assets[assetKey(mediaAsset.provider, mediaAsset.sourceKey)] = mediaAsset
        }
        override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) {
            identities[key(identity.provider, identity.entityType, identity.externalId)] = identity.entityId
        }
        override suspend fun mediaAssetSourceKeys(provider: String) = assets.values
            .filter { it.provider == provider }
            .map(MediaAssetEntity::sourceKey)
        override suspend fun deleteMediaAsset(provider: String, sourceKey: String) {
            assets.remove(assetKey(provider, sourceKey))
        }

        fun artistSnapshot() = Triple(
            artists.toMap(),
            recordingCredits.mapValues { it.value.toList() },
            identities.filterKeys { it.contains("|artist|") }.toMap(),
        )
    }

    private class MemoryAccountPersistence(
        private var value: OpenSubsonicAccountRecord?,
    ) : OpenSubsonicAccountPersistence {
        override fun read() = value
        override fun write(record: OpenSubsonicAccountRecord) { value = record }
        override fun clear() { value = null }
    }

    private class MemoryCredentialStore(
        val values: MutableMap<String, String>,
    ) : OpenSubsonicCredentialStore {
        override fun read(accountId: String) = values[accountId]
        override fun write(accountId: String, credential: String) { values[accountId] = credential }
        override fun clear(accountId: String) { values.remove(accountId) }
    }

    companion object {
        private fun key(provider: String, entityType: String, externalId: String) =
            "$provider|$entityType|$externalId"
        private fun assetKey(provider: String, sourceKey: String) = "$provider|$sourceKey"
    }
}

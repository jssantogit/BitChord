package com.music.bitchord.data.catalog.provider

import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import com.music.bitchord.data.catalog.identity.IsrcNormalizer
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal data class ProviderCapabilities(
    val librarySync: Boolean,
    val healthCheck: Boolean,
    val directPlay: Boolean,
    val serverTranscoding: Boolean,
    val apiKeyAuthentication: Boolean,
)

internal data class ProviderHealth(
    val reachable: Boolean,
    val serverType: String? = null,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val openSubsonic: Boolean = false,
    val detail: String? = null,
)

/** Structured artist identity evidence supplied by a provider. */
internal data class ProviderArtist(
    val externalArtistId: String,
    val name: String,
    val musicBrainzArtistId: String? = null,
)

internal data class ProviderAlbum(
    val externalAlbumId: String,
    val title: String,
    val artistDisplay: String?,
    val externalArtistId: String?,
    val musicBrainzReleaseId: String?,
    val artworkUri: String?,
    val artists: List<ProviderArtist> = emptyList(),
)

internal data class ProviderTrack(
    val externalTrackId: String,
    val title: String,
    val artistDisplay: String,
    val externalArtistId: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val musicBrainzRecordingId: String?,
    val album: ProviderAlbum?,
    val assetUri: String,
    val artworkUri: String?,
    val dateAddedSeconds: Long?,
    val contentType: String?,
    val suffix: String?,
    val bitRateKbps: Int?,
    val bitDepth: Int?,
    val samplingRateHz: Int?,
    val artists: List<ProviderArtist> = emptyList(),
    /** Strong Recording-level external identity evidence, never playback identity. */
    val isrcs: List<String> = emptyList(),
)

internal data class ProviderLibrarySnapshot(
    val tracks: List<ProviderTrack>,
    val complete: Boolean,
)

internal interface RemoteCatalogProvider {
    /** Account-scoped key, e.g. `opensubsonic:home`; never a canonical music ID. */
    val providerKey: String

    suspend fun health(): ProviderHealth

    suspend fun capabilities(): ProviderCapabilities

    /**
     * Returns a complete provider-side library snapshot or throws. Implementations
     * must not turn a partial/network-failed read into a complete empty snapshot.
     */
    suspend fun librarySnapshot(): ProviderLibrarySnapshot
}

/** Small persistence seam so provider mapping/sync stays unit-testable without Room. */
internal interface CanonicalCatalogSyncStore {
    suspend fun mediaAsset(provider: String, sourceKey: String): MediaAssetEntity?

    suspend fun entityIdForExternalIdentity(
        provider: String,
        entityType: String,
        externalId: String,
    ): String?


    /**
     * Strong Recording MBIDs are exclusive, unlike multi-valued ISRCs.
     * Implementations must read the actual claims; silently assuming none is unsafe.
     */
    suspend fun musicBrainzRecordingIdsForEntity(recordingId: String): List<String>

    /** Atomic, insert-only claim. False means conflicting MBID or Recording owner. */
    suspend fun claimMusicBrainzRecordingIdentity(mbid: String, recordingId: String): Boolean

    suspend fun upsertRecording(recording: RecordingEntity)

    suspend fun upsertRelease(release: ReleaseEntity)

    suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity)

    /**
     * Legacy test stores that never receive structured artist evidence may omit these
     * methods. They fail fast rather than silently discarding canonical Artist state.
     */
    suspend fun upsertArtist(artist: ArtistEntity) {
        error("CanonicalCatalogSyncStore does not support Artist persistence")
    }

    suspend fun replaceRecordingArtistCredits(
        recordingId: String,
        credits: List<RecordingArtistCreditEntity>,
    ) {
        error("CanonicalCatalogSyncStore does not support Recording artist credits")
    }

    suspend fun replaceReleaseArtistCredits(
        releaseId: String,
        credits: List<ReleaseArtistCreditEntity>,
    ) {
        error("CanonicalCatalogSyncStore does not support Release artist credits")
    }

    suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity)

    suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity)

    suspend fun mediaAssetSourceKeys(provider: String): List<String>

    suspend fun deleteMediaAsset(provider: String, sourceKey: String)
}

internal sealed interface ProviderSyncResult {
    data class Success(
        val syncedAssets: Int,
        val removedAssets: Int,
        val identityConflicts: Int,
    ) : ProviderSyncResult

    data class Failure(
        val reason: String,
    ) : ProviderSyncResult
}

/**
 * Provider-agnostic canonical sync rules.
 *
 * A provider source ID is persisted only as ExternalIdentity/MediaAsset.sourceKey.
 * Canonical Recording/Release/Artist IDs are generated locally unless an already-known
 * canonical mapping (including MusicBrainz/ISRC evidence) can be reused. Display names
 * are never used as identity evidence.
 */
internal object CanonicalRemoteCatalogSync {
    private const val RECORDING_ENTITY_TYPE = "recording"
    private const val RELEASE_ENTITY_TYPE = "release"
    private const val ARTIST_ENTITY_TYPE = "artist"
    private const val MUSICBRAINZ_PROVIDER = "musicbrainz"
    private const val ISRC_PROVIDER = "isrc"

    suspend fun sync(
        provider: RemoteCatalogProvider,
        store: CanonicalCatalogSyncStore,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): ProviderSyncResult {
        val snapshot = try {
            provider.librarySnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The store has not been touched yet. A transient provider outage must
            // therefore leave the previously synced offline catalog unchanged.
            return ProviderSyncResult.Failure("Provider library snapshot failed")
        }

        return try {
            applySnapshot(
                providerKey = provider.providerKey,
                snapshot = snapshot,
                store = store,
                idFactory = idFactory,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ProviderSyncResult.Failure("Canonical catalog apply failed")
        }
    }

    suspend fun applySnapshot(
        providerKey: String,
        snapshot: ProviderLibrarySnapshot,
        store: CanonicalCatalogSyncStore,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): ProviderSyncResult.Success {
        var identityConflicts = 0
        val seenSourceKeys = LinkedHashSet<String>(snapshot.tracks.size)

        for (track in snapshot.tracks) {
            val sourceKey = track.externalTrackId.trim()
            if (sourceKey.isEmpty()) continue
            seenSourceKeys += sourceKey

            val existingAsset = store.mediaAsset(providerKey, sourceKey)
            val providerRecordingId = store.entityIdForExternalIdentity(
                provider = providerKey,
                entityType = RECORDING_ENTITY_TYPE,
                externalId = sourceKey,
            )
            val musicBrainzRecordingId = track.musicBrainzRecordingId.cleanId()
            val musicBrainzRecordingEntityId = musicBrainzRecordingId?.let { mbid ->
                store.entityIdForExternalIdentity(
                    provider = MUSICBRAINZ_PROVIDER,
                    entityType = RECORDING_ENTITY_TYPE,
                    externalId = mbid,
                )
            }
            val normalizedIsrcs = IsrcNormalizer.normalizeDistinct(track.isrcs)
            val isrcRecordingEntityIds = normalizedIsrcs.mapNotNull { isrc ->
                store.entityIdForExternalIdentity(
                    provider = ISRC_PROVIDER,
                    entityType = RECORDING_ENTITY_TYPE,
                    externalId = isrc,
                )
            }.distinct()
            val unambiguousIsrcRecordingId = isrcRecordingEntityIds.singleOrNull()
            // A new, unclaimed MBID cannot inherit an ISRC owner's different
            // trusted MBID. Existing provider aliases/assets are still stable.
            val compatibleIsrcRecordingId = if (
                musicBrainzRecordingId != null &&
                musicBrainzRecordingEntityId == null &&
                unambiguousIsrcRecordingId != null
            ) {
                unambiguousIsrcRecordingId.takeIf { candidate ->
                    store.musicBrainzRecordingIdsForEntity(candidate).all { it == musicBrainzRecordingId }
                }
            } else {
                unambiguousIsrcRecordingId
            }

            // Existing provider/asset state keeps resync stable. A trusted MBID is
            // stronger than ISRC. ISRC is consulted only when its known claims agree.
            val recordingId = providerRecordingId
                ?: existingAsset?.recordingId
                ?: musicBrainzRecordingEntityId
                ?: compatibleIsrcRecordingId
                ?: idFactory()

            store.upsertRecording(
                RecordingEntity(
                    id = recordingId,
                    title = track.title.ifBlank { sourceKey },
                    artistDisplay = track.artistDisplay.ifBlank { "Unknown Artist" },
                    durationMs = track.durationMs,
                ),
            )
            store.upsertExternalIdentity(
                ExternalIdentityEntity(
                    provider = providerKey,
                    entityType = RECORDING_ENTITY_TYPE,
                    externalId = sourceKey,
                    entityId = recordingId,
                ),
            )
            // Never use the generic upsert for a trusted Recording MBID:
            // it can create a second MBID on this Recording or transfer a key.
            if (musicBrainzRecordingId != null &&
                !store.claimMusicBrainzRecordingIdentity(musicBrainzRecordingId, recordingId)
            ) {
                identityConflicts += 1
            }
            for (isrc in normalizedIsrcs) {
                // Never transfer an existing ISRC claim. Conflict evidence is
                // counted while the historical mapping remains untouched.
                identityConflicts += persistExternalEvidenceIfUnclaimed(
                    store = store,
                    provider = ISRC_PROVIDER,
                    entityType = RECORDING_ENTITY_TYPE,
                    externalId = isrc,
                    canonicalEntityId = recordingId,
                )
            }

            if (track.artists.isNotEmpty()) {
                val resolved = resolveArtists(
                    providerKey = providerKey,
                    providerArtists = track.artists,
                    store = store,
                    idFactory = idFactory,
                )
                identityConflicts += resolved.identityConflicts
                if (resolved.artistIds.isNotEmpty()) {
                    store.replaceRecordingArtistCredits(
                        recordingId = recordingId,
                        credits = resolved.artistIds.mapIndexed { position, artistId ->
                            RecordingArtistCreditEntity(
                                recordingId = recordingId,
                                position = position,
                                artistId = artistId,
                            )
                        },
                    )
                }
            }

            val album = track.album
            if (album != null && album.externalAlbumId.isNotBlank()) {
                val externalAlbumId = album.externalAlbumId.trim()
                val providerReleaseId = store.entityIdForExternalIdentity(
                    provider = providerKey,
                    entityType = RELEASE_ENTITY_TYPE,
                    externalId = externalAlbumId,
                )
                val musicBrainzReleaseId = album.musicBrainzReleaseId.cleanId()
                val musicBrainzReleaseEntityId = musicBrainzReleaseId?.let { mbid ->
                    store.entityIdForExternalIdentity(
                        provider = MUSICBRAINZ_PROVIDER,
                        entityType = RELEASE_ENTITY_TYPE,
                        externalId = mbid,
                    )
                }
                val releaseId = providerReleaseId
                    ?: musicBrainzReleaseEntityId
                    ?: idFactory()

                store.upsertRelease(
                    ReleaseEntity(
                        id = releaseId,
                        title = album.title.ifBlank { externalAlbumId },
                        artistDisplay = album.artistDisplay,
                        artworkUri = album.artworkUri ?: track.artworkUri,
                    ),
                )
                store.upsertExternalIdentity(
                    ExternalIdentityEntity(
                        provider = providerKey,
                        entityType = RELEASE_ENTITY_TYPE,
                        externalId = externalAlbumId,
                        entityId = releaseId,
                    ),
                )
                identityConflicts += persistExternalEvidenceIfUnclaimed(
                    store = store,
                    provider = MUSICBRAINZ_PROVIDER,
                    entityType = RELEASE_ENTITY_TYPE,
                    externalId = musicBrainzReleaseId,
                    canonicalEntityId = releaseId,
                )
                if (album.artists.isNotEmpty()) {
                    val resolved = resolveArtists(
                        providerKey = providerKey,
                        providerArtists = album.artists,
                        store = store,
                        idFactory = idFactory,
                    )
                    identityConflicts += resolved.identityConflicts
                    if (resolved.artistIds.isNotEmpty()) {
                        store.replaceReleaseArtistCredits(
                            releaseId = releaseId,
                            credits = resolved.artistIds.mapIndexed { position, artistId ->
                                ReleaseArtistCreditEntity(
                                    releaseId = releaseId,
                                    position = position,
                                    artistId = artistId,
                                )
                            },
                        )
                    }
                }
                // Do not delete other ReleaseTrack rows here. A Recording may be
                // present on multiple canonical releases and those associations
                // can come from other providers/local evidence.
                store.upsertReleaseTrack(
                    ReleaseTrackEntity(
                        releaseId = releaseId,
                        recordingId = recordingId,
                        trackNumber = track.trackNumber,
                    ),
                )
            }

            val mediaAssetId = existingAsset?.id ?: idFactory()
            store.upsertMediaAsset(
                MediaAssetEntity(
                    id = mediaAssetId,
                    recordingId = recordingId,
                    provider = providerKey,
                    sourceKey = sourceKey,
                    uri = track.assetUri,
                    path = null,
                    dateAddedSeconds = track.dateAddedSeconds,
                    dateModifiedSeconds = null,
                    artworkUri = track.artworkUri ?: track.album?.artworkUri,
                ),
            )
        }

        var removedAssets = 0
        if (snapshot.complete) {
            val existingSourceKeys = store.mediaAssetSourceKeys(providerKey)
            for (existingSourceKey in existingSourceKeys) {
                if (existingSourceKey !in seenSourceKeys) {
                    // Availability disappeared. Keep every canonical identity,
                    // Artist/credit relation and provider alias; prune only availability.
                    val staleAsset = store.mediaAsset(providerKey, existingSourceKey)
                    val existingAlias = store.entityIdForExternalIdentity(
                        provider = providerKey,
                        entityType = RECORDING_ENTITY_TYPE,
                        externalId = existingSourceKey,
                    )
                    if (existingAlias == null && staleAsset != null) {
                        store.upsertExternalIdentity(
                            ExternalIdentityEntity(
                                provider = providerKey,
                                entityType = RECORDING_ENTITY_TYPE,
                                externalId = existingSourceKey,
                                entityId = staleAsset.recordingId,
                            ),
                        )
                    }
                    store.deleteMediaAsset(providerKey, existingSourceKey)
                    removedAssets += 1
                }
            }
        }

        return ProviderSyncResult.Success(
            syncedAssets = seenSourceKeys.size,
            removedAssets = removedAssets,
            identityConflicts = identityConflicts,
        )
    }

    private suspend fun resolveArtists(
        providerKey: String,
        providerArtists: List<ProviderArtist>,
        store: CanonicalCatalogSyncStore,
        idFactory: () -> String,
    ): ResolvedArtists {
        var conflicts = 0
        val artistIds = ArrayList<String>(providerArtists.size)

        for (providerArtist in providerArtists) {
            val externalArtistId = providerArtist.externalArtistId.cleanId() ?: continue
            val providerArtistEntityId = store.entityIdForExternalIdentity(
                provider = providerKey,
                entityType = ARTIST_ENTITY_TYPE,
                externalId = externalArtistId,
            )
            val musicBrainzArtistId = providerArtist.musicBrainzArtistId.cleanId()
            val musicBrainzArtistEntityId = musicBrainzArtistId?.let { mbid ->
                store.entityIdForExternalIdentity(
                    provider = MUSICBRAINZ_PROVIDER,
                    entityType = ARTIST_ENTITY_TYPE,
                    externalId = mbid,
                )
            }
            val artistId = providerArtistEntityId
                ?: musicBrainzArtistEntityId
                ?: idFactory()

            store.upsertArtist(
                ArtistEntity(
                    id = artistId,
                    name = providerArtist.name.trim().ifEmpty { "Unknown Artist" },
                ),
            )
            store.upsertExternalIdentity(
                ExternalIdentityEntity(
                    provider = providerKey,
                    entityType = ARTIST_ENTITY_TYPE,
                    externalId = externalArtistId,
                    entityId = artistId,
                ),
            )
            conflicts += persistExternalEvidenceIfUnclaimed(
                store = store,
                provider = MUSICBRAINZ_PROVIDER,
                entityType = ARTIST_ENTITY_TYPE,
                externalId = musicBrainzArtistId,
                canonicalEntityId = artistId,
            )
            artistIds += artistId
        }

        return ResolvedArtists(
            artistIds = artistIds,
            identityConflicts = conflicts,
        )
    }

    private suspend fun persistExternalEvidenceIfUnclaimed(
        store: CanonicalCatalogSyncStore,
        provider: String,
        entityType: String,
        externalId: String?,
        canonicalEntityId: String,
    ): Int {
        val id = externalId ?: return 0
        val existingEntityId = store.entityIdForExternalIdentity(
            provider = provider,
            entityType = entityType,
            externalId = id,
        )
        return when {
            existingEntityId == null -> {
                store.upsertExternalIdentity(
                    ExternalIdentityEntity(
                        provider = provider,
                        entityType = entityType,
                        externalId = id,
                        entityId = canonicalEntityId,
                    ),
                )
                0
            }
            existingEntityId == canonicalEntityId -> 0
            else -> 1 // evidence conflict: reconciliation belongs to a later slice
        }
    }

    private data class ResolvedArtists(
        val artistIds: List<String>,
        val identityConflicts: Int,
    )

    private fun String?.cleanId(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}

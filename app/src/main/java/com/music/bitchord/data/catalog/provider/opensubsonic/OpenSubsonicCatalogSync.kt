package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
import io.ktor.client.plugins.ResponseException
import androidx.room.withTransaction
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalProviderRow
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingArtistCreditEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.ReleaseArtistCreditEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.ReleaseTrackEntity
import com.music.bitchord.data.catalog.provider.CanonicalCatalogSyncStore
import com.music.bitchord.data.catalog.provider.CanonicalRemoteCatalogSync
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android/Room boundary for the OpenSubsonic vertical slice.
 *
 * Network enumeration completes before the Room transaction starts. Therefore a
 * timeout/auth/server failure cannot turn a previously synced library into an
 * empty provider snapshot or partially prune remote MediaAssets.
 */
internal object OpenSubsonicCatalogSync {
    /**
     * In-process generation per account-scoped provider. A newer invocation
     * invalidates an older pending network snapshot before it can reach Room.
     * Room itself remains durable across restarts; no in-flight coroutine
     * survives process death to apply after reopen.
     */
    private data class ProviderApplyGate(
        val latestGeneration: AtomicLong = AtomicLong(),
        val applyMutex: Mutex = Mutex(),
    )
    private val applyGates = ConcurrentHashMap<String, ProviderApplyGate>()

    suspend fun sync(
        context: Context,
        provider: OpenSubsonicProvider,
        idFactory: () -> String = { UUID.randomUUID().toString() },
        accountIsCurrent: () -> Boolean = { true },
    ): ProviderSyncResult = syncInternal(provider, idFactory, accountIsCurrent) {
        CanonicalCatalogDatabase.get(context)
    }

    /**
     * Test seam: the same real provider transport and Room transaction as production.
     * Never replaces the singleton database; callers pass an isolated Room instance.
     */
    internal suspend fun sync(
        database: CanonicalCatalogDatabase,
        provider: OpenSubsonicProvider,
        idFactory: () -> String = { UUID.randomUUID().toString() },
        accountIsCurrent: () -> Boolean = { true },
    ): ProviderSyncResult = syncInternal(provider, idFactory, accountIsCurrent) { database }

    private suspend fun syncInternal(
        provider: OpenSubsonicProvider,
        idFactory: () -> String,
        accountIsCurrent: () -> Boolean,
        databaseProvider: () -> CanonicalCatalogDatabase,
    ): ProviderSyncResult {
        val gate = applyGates.computeIfAbsent(provider.providerKey) { ProviderApplyGate() }
        val generation = gate.latestGeneration.incrementAndGet()
        val snapshot = try {
            provider.librarySnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (protocol: OpenSubsonicApiException) {
            // Subsonic status codes are finite protocol categories. Never echo
            // server-generated error text, which may contain credentials or URLs.
            return when (protocol.code) {
                20, 30 -> ProviderSyncResult.Failure("OpenSubsonic protocol unsupported")
                40 -> ProviderSyncResult.Failure("OpenSubsonic authentication failed")
                50 -> ProviderSyncResult.Failure("OpenSubsonic permission denied")
                else -> ProviderSyncResult.Failure("OpenSubsonic library snapshot failed")
            }
        } catch (response: ResponseException) {
            return when (response.response.status.value) {
                401 -> ProviderSyncResult.Failure("OpenSubsonic authentication failed")
                403 -> ProviderSyncResult.Failure("OpenSubsonic permission denied")
                else -> ProviderSyncResult.Failure("OpenSubsonic library snapshot failed")
            }
        } catch (_: Throwable) {
            return ProviderSyncResult.Failure("OpenSubsonic library snapshot failed")
        }

        return try {
            gate.applyMutex.withLock {
                // Serialize writes but permit concurrent remote reads. Never
                // let a late stale enumeration replace a newer committed one.
                if (generation != gate.latestGeneration.get() || !accountIsCurrent()) {
                    return@withLock ProviderSyncResult.Failure("OpenSubsonic snapshot superseded")
                }
                val database = databaseProvider()
                database.withTransaction {
                    if (generation != gate.latestGeneration.get() || !accountIsCurrent()) {
                        error("OpenSubsonic snapshot superseded before Room apply")
                    }
                    val result = CanonicalRemoteCatalogSync.applySnapshot(
                        providerKey = provider.providerKey,
                        snapshot = snapshot,
                        store = RoomCanonicalCatalogSyncStore(database.catalogDao()),
                        idFactory = idFactory,
                    )
                    // Never persist partial/auth-failed/stale or cancelled snapshots.
                    // Remote evidence and canonical assets commit atomically.
                    OpenSubsonicRemoteRatingPersistence.apply(
                        dao = database.catalogDao(),
                        providerKey = provider.providerKey,
                        scopeId = provider.ratingScopeId,
                        generation = generation,
                        snapshot = snapshot,
                    )
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Room rolls the transaction back on failure, preserving the last
            // complete provider snapshot.
            ProviderSyncResult.Failure("OpenSubsonic catalog transaction failed")
        }
    }

    /** Reads the last successful Room snapshot without contacting the server. */
    suspend fun cachedTracks(
        context: Context,
        provider: OpenSubsonicProvider,
    ): List<CanonicalProviderRow> = CanonicalCatalogDatabase
        .get(context)
        .catalogDao()
        .providerRows(provider.providerKey)
}

private class RoomCanonicalCatalogSyncStore(
    private val dao: CanonicalCatalogDao,
) : CanonicalCatalogSyncStore {
    override suspend fun mediaAsset(provider: String, sourceKey: String): MediaAssetEntity? =
        dao.mediaAsset(provider, sourceKey)

    override suspend fun entityIdForExternalIdentity(
        provider: String,
        entityType: String,
        externalId: String,
    ): String? = dao.entityIdForExternalIdentity(provider, entityType, externalId)


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

    override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) =
        dao.upsertReleaseTrack(releaseTrack)

    override suspend fun upsertArtist(artist: ArtistEntity) = dao.upsertArtist(artist)

    override suspend fun replaceRecordingArtistCredits(
        recordingId: String,
        credits: List<RecordingArtistCreditEntity>,
    ) {
        // A provider resync may seed previously empty canonical credits, but is
        // NEVER authority to replace credits already owned by the canonical
        // catalog (including credits promoted separately by an explicit command).
        // This read and optional insert run under syncInternal's Room transaction.
        if (dao.recordingArtistCreditIds(listOf(recordingId)).isNotEmpty()) return
        dao.replaceRecordingArtistCredits(recordingId, credits)
    }

    override suspend fun replaceReleaseArtistCredits(
        releaseId: String,
        credits: List<ReleaseArtistCreditEntity>,
    ) {
        // Release credits have the same immutable-on-resync ownership guarantee.
        if (dao.releaseArtistCredits(releaseId).isNotEmpty()) return
        dao.replaceReleaseArtistCredits(releaseId, credits)
    }

    override suspend fun upsertMediaAsset(mediaAsset: MediaAssetEntity) = dao.upsertMediaAsset(mediaAsset)

    override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) =
        dao.upsertExternalIdentity(identity)

    override suspend fun mediaAssetSourceKeys(provider: String): List<String> =
        dao.mediaAssetSourceKeys(provider)

    override suspend fun deleteMediaAsset(provider: String, sourceKey: String) =
        dao.deleteMediaAsset(provider, sourceKey)
}

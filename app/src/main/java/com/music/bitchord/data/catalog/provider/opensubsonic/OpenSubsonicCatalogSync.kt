package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
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
import kotlinx.coroutines.CancellationException

/**
 * Android/Room boundary for the OpenSubsonic vertical slice.
 *
 * Network enumeration completes before the Room transaction starts. Therefore a
 * timeout/auth/server failure cannot turn a previously synced library into an
 * empty provider snapshot or partially prune remote MediaAssets.
 */
internal object OpenSubsonicCatalogSync {
    suspend fun sync(
        context: Context,
        provider: OpenSubsonicProvider,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): ProviderSyncResult {
        val snapshot = try {
            provider.librarySnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return ProviderSyncResult.Failure("OpenSubsonic library snapshot failed")
        }

        val database = CanonicalCatalogDatabase.get(context)
        return try {
            database.withTransaction {
                CanonicalRemoteCatalogSync.applySnapshot(
                    providerKey = provider.providerKey,
                    snapshot = snapshot,
                    store = RoomCanonicalCatalogSyncStore(database.catalogDao()),
                    idFactory = idFactory,
                )
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

    override suspend fun upsertRecording(recording: RecordingEntity) = dao.upsertRecording(recording)

    override suspend fun upsertRelease(release: ReleaseEntity) = dao.upsertRelease(release)

    override suspend fun upsertReleaseTrack(releaseTrack: ReleaseTrackEntity) =
        dao.upsertReleaseTrack(releaseTrack)

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

    override suspend fun upsertExternalIdentity(identity: ExternalIdentityEntity) =
        dao.upsertExternalIdentity(identity)

    override suspend fun mediaAssetSourceKeys(provider: String): List<String> =
        dao.mediaAssetSourceKeys(provider)

    override suspend fun deleteMediaAsset(provider: String, sourceKey: String) =
        dao.deleteMediaAsset(provider, sourceKey)
}

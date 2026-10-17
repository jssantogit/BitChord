package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.model.Song

/**
 * Playback-owned identity for one musical recording.
 *
 * This deliberately does not reuse Song.videoId: provider ids and playable
 * URIs are aliases/renditions, while this id survives choosing a different
 * asset for the same recording.
 */
@JvmInline
value class CanonicalQueueIdentity(val recordingId: String) {
    init {
        require(recordingId.isNotBlank()) { "recordingId must not be blank" }
    }
}

/** Minimal rendition projection required by the current playback edge. */
data class PlayableMediaAsset(
    val recordingId: String,
    val mediaAssetId: String,
    val uri: String,
    val path: String? = null,
)

/** Read-only catalog seam owned by playback so resolver tests stay headless. */
fun interface PlaybackCatalog {
    suspend fun playableAssets(): List<PlayableMediaAsset>
}

/**
 * Resolves canonical queue identity to a concrete playable rendition.
 *
 * Provider discovery/fallback remains outside this Slice. The first
 * implementation intentionally proves only the Room-backed local asset path.
 */
interface PlaybackResolver {
    suspend fun identify(song: Song): CanonicalQueueIdentity?
    suspend fun resolve(identity: CanonicalQueueIdentity): PlayableMediaAsset?
}

class CanonicalPlaybackResolver(
    private val catalog: PlaybackCatalog,
) : PlaybackResolver {

    override suspend fun identify(song: Song): CanonicalQueueIdentity? {
        val localUri = song.localUri?.takeIf(String::isNotBlank)
        val localPath = song.localPath?.takeIf(String::isNotBlank)
        if (localUri == null && localPath == null) return null

        val asset = catalog.playableAssets().firstOrNull { candidate ->
            (localUri != null && candidate.uri == localUri) ||
                (localPath != null && candidate.path == localPath)
        } ?: return null
        return CanonicalQueueIdentity(asset.recordingId)
    }

    override suspend fun resolve(identity: CanonicalQueueIdentity): PlayableMediaAsset? =
        catalog.playableAssets().firstOrNull { it.recordingId == identity.recordingId }
}

/**
 * Android production adapter over the existing Slice 1 catalog.
 *
 * It intentionally reuses the existing localRows read model instead of adding
 * a DAO/schema contract while the vertical slice is being proven. A later
 * optimization may replace this scan with a targeted query without changing
 * PlaybackResolver.
 */
internal class RoomPlaybackCatalog(context: Context) : PlaybackCatalog {
    private val database = CanonicalCatalogDatabase.get(context.applicationContext)

    override suspend fun playableAssets(): List<PlayableMediaAsset> =
        database.catalogDao()
            .localRows(
                localProvider = LOCAL_MEDIASTORE_PROVIDER,
                youtubeProvider = YOUTUBE_PROVIDER,
                recordingEntityType = RECORDING_ENTITY_TYPE,
            )
            .map { row ->
                PlayableMediaAsset(
                    recordingId = row.recordingId,
                    mediaAssetId = row.mediaAssetId,
                    uri = row.uri,
                    path = row.path,
                )
            }

    private companion object {
        const val LOCAL_MEDIASTORE_PROVIDER = "local-mediastore"
        const val YOUTUBE_PROVIDER = "youtube"
        const val RECORDING_ENTITY_TYPE = "recording"
    }
}

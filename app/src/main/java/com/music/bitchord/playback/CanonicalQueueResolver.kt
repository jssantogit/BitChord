package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.model.Song

/** Durable queue identity. Only [recordingId] identifies the musical work. */
data class CanonicalQueueIdentity(
    val recordingId: String,
    val mediaAssetId: String? = null,
    val providerKey: String? = null,
) {
    init {
        require(recordingId.isNotBlank()) { "recordingId must not be blank" }
        require(mediaAssetId?.isNotBlank() != false) { "mediaAssetId must not be blank" }
        require(providerKey?.isNotBlank() != false) { "providerKey must not be blank" }
        require((mediaAssetId == null) == (providerKey == null)) {
            "mediaAssetId and providerKey must either both be present or both be absent"
        }
    }
}

internal data class CanonicalPlayableAsset(
    val identity: CanonicalQueueIdentity,
    val uri: String,
    val path: String? = null,
)

/** Playback-owned catalog seam. It never exposes sourceKey or provider auth. */
internal interface CanonicalQueueResolver {
    suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?>
    suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>): List<CanonicalPlayableAsset?>
}

internal fun androidCanonicalQueueResolver(context: Context): CanonicalQueueResolver =
    RoomCanonicalQueueResolver(context.applicationContext)

/**
 * Local identity is discovered from the existing canonical catalog in one batch.
 * Remote logical identities are self-describing and are reconstructed without
 * touching account state, Keystore or the network.
 */
internal class RoomCanonicalQueueResolver(context: Context) : CanonicalQueueResolver {
    private val dao = CanonicalCatalogDatabase.get(context).catalogDao()

    override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> {
        val localRows = if (songs.any { it.canonicalRemoteRequest() == null && (it.localUri != null || it.localPath != null) }) {
            dao.localRows(LOCAL_PROVIDER, YOUTUBE_PROVIDER, RECORDING_ENTITY_TYPE)
        } else {
            emptyList()
        }
        val byUri = localRows.associateBy { it.uri }
        val byPath = localRows.mapNotNull { row -> row.path?.let { it to row } }.toMap()
        return songs.map { song ->
            song.canonicalRemoteRequest()?.let { request ->
                return@map CanonicalQueueIdentity(
                    recordingId = request.recordingId,
                    mediaAssetId = request.mediaAssetId,
                    providerKey = request.providerKey,
                )
            }
            val row = song.localUri?.let(byUri::get) ?: song.localPath?.let(byPath::get)
            row?.let {
                CanonicalQueueIdentity(
                    recordingId = it.recordingId,
                    mediaAssetId = it.mediaAssetId,
                    providerKey = LOCAL_PROVIDER,
                )
            }
        }
    }

    override suspend fun resolveAll(
        identities: List<CanonicalQueueIdentity?>,
    ): List<CanonicalPlayableAsset?> {
        val needsLocal = identities.any { it?.providerKey == LOCAL_PROVIDER }
        val localRows = if (needsLocal) {
            dao.localRows(LOCAL_PROVIDER, YOUTUBE_PROVIDER, RECORDING_ENTITY_TYPE)
        } else {
            emptyList()
        }
        val localByAsset = localRows.associateBy { it.mediaAssetId }
        return identities.map { identity ->
            identity ?: return@map null
            val provider = identity.providerKey
            val assetId = identity.mediaAssetId
            when {
                provider == LOCAL_PROVIDER && assetId != null -> {
                    val row = localByAsset[assetId]
                    if (row == null || row.recordingId != identity.recordingId) null
                    else CanonicalPlayableAsset(identity, row.uri, row.path)
                }
                provider != null && assetId != null -> {
                    val request = runCatching {
                        CanonicalRemotePlaybackRequest(
                            recordingId = identity.recordingId,
                            mediaAssetId = assetId,
                            providerKey = provider,
                        )
                    }.getOrNull() ?: return@map null
                    CanonicalPlayableAsset(identity, CanonicalRemotePlaybackUri.encode(request))
                }
                else -> null
            }
        }
    }

    private fun Song.canonicalRemoteRequest(): CanonicalRemotePlaybackRequest? =
        sequenceOf(localUri, videoId)
            .filterNotNull()
            .mapNotNull(CanonicalRemotePlaybackUri::decode)
            .firstOrNull()

    companion object {
        internal const val LOCAL_PROVIDER = "local-mediastore"
        private const val YOUTUBE_PROVIDER = "youtube"
        private const val RECORDING_ENTITY_TYPE = "recording"
    }
}

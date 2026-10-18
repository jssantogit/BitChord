package com.music.bitchord.playback

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Safe identity crossing UI/session/queue boundaries for canonical remote playback. */
internal data class CanonicalRemotePlaybackRequest(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
) {
    init {
        require(recordingId.isNotBlank()) { "recordingId must not be blank" }
        require(mediaAssetId.isNotBlank()) { "mediaAssetId must not be blank" }
        require(providerKey.isNotBlank()) { "providerKey must not be blank" }
    }
}

/** Metadata safe to expose to Media3 controllers and persist with the logical queue item. */
internal data class CanonicalRemotePlaybackMetadata(
    val title: String,
    val artistDisplay: String,
)

/** Logical credential-free URI. Provider-native locators never cross this boundary. */
internal object CanonicalRemotePlaybackUri {
    private const val SCHEME = "bitchord"
    private const val AUTHORITY = "canonical"
    private const val RECORDING_ID = "recordingId"
    private const val MEDIA_ASSET_ID = "mediaAssetId"
    private const val PROVIDER_KEY = "providerKey"

    fun encode(request: CanonicalRemotePlaybackRequest): String = buildString {
        append("$SCHEME://$AUTHORITY?")
        append(RECORDING_ID).append('=').append(encodeComponent(request.recordingId))
        append('&').append(MEDIA_ASSET_ID).append('=').append(encodeComponent(request.mediaAssetId))
        append('&').append(PROVIDER_KEY).append('=').append(encodeComponent(request.providerKey))
    }

    fun decode(value: String): CanonicalRemotePlaybackRequest? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || uri.authority != AUTHORITY) return null
        val parameters = uri.rawQuery
            ?.split('&')
            ?.filter(String::isNotBlank)
            ?.mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                val key = parts.getOrNull(0)?.let(::decodeComponent) ?: return@mapNotNull null
                key to parts.getOrNull(1)?.let(::decodeComponent).orEmpty()
            }
            ?.toMap()
            ?: return null
        return runCatching {
            CanonicalRemotePlaybackRequest(
                recordingId = parameters[RECORDING_ID].orEmpty(),
                mediaAssetId = parameters[MEDIA_ASSET_ID].orEmpty(),
                providerKey = parameters[PROVIDER_KEY].orEmpty(),
            )
        }.getOrNull()
    }

    fun isCanonical(value: String): Boolean = decode(value) != null

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

internal fun canonicalRemoteMediaItem(
    request: CanonicalRemotePlaybackRequest,
    metadata: CanonicalRemotePlaybackMetadata,
): MediaItem {
    val logicalUri = CanonicalRemotePlaybackUri.encode(request)
    return MediaItem.Builder()
        .setMediaId(request.recordingId)
        .setUri(logicalUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setArtist(metadata.artistDisplay)
                // Queue persistence round-trips MediaItems through Song. Keep only the
                // credential-free logical URI in that existing safe persistence field.
                .setExtras(bundleOf(EXTRA_LOCAL_URI to logicalUri))
                .build(),
        )
        .build()
}

internal fun MediaItem.isCanonicalRemotePlayback(): Boolean =
    localConfiguration?.uri?.toString()?.let(CanonicalRemotePlaybackUri::isCanonical) == true

internal const val ACTION_PLAY_CANONICAL_REMOTE = "com.music.bitchord.action.PLAY_CANONICAL_REMOTE"
internal const val EXTRA_CANONICAL_RECORDING_ID = "canonicalRecordingId"
internal const val EXTRA_CANONICAL_MEDIA_ASSET_ID = "canonicalMediaAssetId"
internal const val EXTRA_CANONICAL_PROVIDER_KEY = "canonicalProviderKey"
internal const val EXTRA_CANONICAL_TITLE = "canonicalTitle"
internal const val EXTRA_CANONICAL_ARTIST = "canonicalArtist"

internal fun canonicalRemotePlaybackBundle(
    request: CanonicalRemotePlaybackRequest,
    metadata: CanonicalRemotePlaybackMetadata,
): Bundle = bundleOf(
    EXTRA_CANONICAL_RECORDING_ID to request.recordingId,
    EXTRA_CANONICAL_MEDIA_ASSET_ID to request.mediaAssetId,
    EXTRA_CANONICAL_PROVIDER_KEY to request.providerKey,
    EXTRA_CANONICAL_TITLE to metadata.title,
    EXTRA_CANONICAL_ARTIST to metadata.artistDisplay,
)

internal fun canonicalRemotePlaybackFromBundle(
    bundle: Bundle,
): Pair<CanonicalRemotePlaybackRequest, CanonicalRemotePlaybackMetadata>? = runCatching {
    CanonicalRemotePlaybackRequest(
        recordingId = bundle.getString(EXTRA_CANONICAL_RECORDING_ID).orEmpty(),
        mediaAssetId = bundle.getString(EXTRA_CANONICAL_MEDIA_ASSET_ID).orEmpty(),
        providerKey = bundle.getString(EXTRA_CANONICAL_PROVIDER_KEY).orEmpty(),
    ) to CanonicalRemotePlaybackMetadata(
        title = bundle.getString(EXTRA_CANONICAL_TITLE).orEmpty(),
        artistDisplay = bundle.getString(EXTRA_CANONICAL_ARTIST).orEmpty(),
    )
}.getOrNull()

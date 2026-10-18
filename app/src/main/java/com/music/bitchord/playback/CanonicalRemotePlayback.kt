package com.music.bitchord.playback

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessFailure
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicMediaAccessResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRuntimeAudioRequest
import java.io.IOException
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

/**
 * Logical credential-free URI. Provider-native locators never cross this boundary.
 */
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

/**
 * Playback-side router. It knows only which provider owns the opaque request;
 * authentication and provider-native source identity remain provider-owned.
 */
internal class CanonicalRemotePlaybackResolver(
    private val openSubsonicResolve: suspend (mediaAssetId: String) -> OpenSubsonicMediaAccessResult,
) {
    suspend fun resolve(logicalUri: String): OpenSubsonicRuntimeAudioRequest {
        val request = CanonicalRemotePlaybackUri.decode(logicalUri)
            ?: throw IOException("Invalid canonical remote playback request")
        if (!request.providerKey.startsWith(OPENSUBSONIC_PROVIDER_PREFIX)) {
            throw IOException("Canonical remote playback provider unsupported")
        }

        return when (val result = openSubsonicResolve(request.mediaAssetId)) {
            is OpenSubsonicMediaAccessResult.Failure -> throw safeFailure(result.reason)
            is OpenSubsonicMediaAccessResult.Ready -> {
                val runtime = result.request
                if (
                    runtime.recordingId != request.recordingId ||
                    runtime.mediaAssetId != request.mediaAssetId ||
                    runtime.providerKey != request.providerKey
                ) {
                    throw IOException("Canonical remote playback identity mismatch")
                }
                runtime
            }
        }
    }

    private fun safeFailure(reason: OpenSubsonicMediaAccessFailure): IOException = IOException(
        when (reason) {
            OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND -> "Remote media asset unavailable"
            OpenSubsonicMediaAccessFailure.UNSUPPORTED_PROVIDER -> "Remote playback provider unsupported"
            OpenSubsonicMediaAccessFailure.ACCOUNT_NOT_CONFIGURED -> "Remote playback account unavailable"
            OpenSubsonicMediaAccessFailure.ACCOUNT_MISMATCH -> "Remote playback account mismatch"
            OpenSubsonicMediaAccessFailure.CREDENTIAL_UNAVAILABLE -> "Remote playback credential unavailable"
            OpenSubsonicMediaAccessFailure.INVALID_LOCATOR -> "Remote media locator unavailable"
        },
    )

    private companion object {
        const val OPENSUBSONIC_PROVIDER_PREFIX = "opensubsonic:"
    }
}

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

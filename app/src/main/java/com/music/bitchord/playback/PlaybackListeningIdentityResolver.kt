package com.music.bitchord.playback

import com.music.bitchord.data.listening.PlaybackStart
import com.music.bitchord.data.listening.SelectionIntent
import com.music.bitchord.data.listening.SelectionOrigin
import java.net.URI

/**
 * The only playback identity allowed to cross into first-party listening history.
 * Provider-native locators and authenticated transport data deliberately have no
 * representation here.
 */
internal data class CanonicalPlaybackIdentity(
    val recordingId: String,
    val mediaAssetId: String?,
    val provider: String,
)

internal fun CanonicalPlaybackIdentity.toPlaybackStart(
    selectionIntent: SelectionIntent,
    selectionOrigin: SelectionOrigin,
): PlaybackStart = PlaybackStart(
    recordingId = recordingId,
    mediaAssetId = mediaAssetId,
    assetProvider = provider,
    selectionIntent = selectionIntent,
    selectionOrigin = selectionOrigin,
)

/**
 * Resolves live playback facts to an already-proven canonical Recording.
 *
 * Priority is deliberate:
 * 1. the credential-free canonical MediaItem URI;
 * 2. an already indexed local MediaAsset;
 * 3. a proven legacy YouTube ExternalIdentity.
 *
 * Invalid canonical URIs fail closed rather than falling through to a provider
 * or legacy identifier. This class never asks a provider what a song "really"
 * is and never receives sourceKey/auth material as output.
 */
internal class PlaybackListeningIdentityResolver(
    private val resolveLocalAsset: suspend (sourceKey: String) -> CanonicalPlaybackIdentity?,
    private val resolveLegacyYoutubeAlias: suspend (externalId: String) -> CanonicalPlaybackIdentity?,
) {
    suspend fun resolve(
        canonicalUri: String?,
        localSourceKey: String?,
        legacyYoutubeId: String?,
    ): CanonicalPlaybackIdentity? {
        canonicalUri?.takeIf(String::isNotBlank)?.let { value ->
            CanonicalRemotePlaybackUri.decode(value)?.let { request ->
                return CanonicalPlaybackIdentity(
                    recordingId = request.recordingId,
                    mediaAssetId = request.mediaAssetId,
                    provider = request.providerKey,
                )
            }
            if (isCanonicalAuthority(value)) return null
        }

        localSourceKey?.takeIf(String::isNotBlank)?.let { sourceKey ->
            resolveLocalAsset(sourceKey)?.let { return it }
        }

        val externalId = legacyYoutubeId?.takeIf(String::isNotBlank) ?: return null
        return resolveLegacyYoutubeAlias(externalId)
    }

    private fun isCanonicalAuthority(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("bitchord", ignoreCase = true) && uri.authority == "canonical"
    }
}

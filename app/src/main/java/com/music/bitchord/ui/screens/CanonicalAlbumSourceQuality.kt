package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.ui.components.SourceQuality
import java.util.Locale

/**
 * Catalog *source* quality, not the negotiated playback stream.
 *
 * Infer only from explicit MediaStore MIME or provider-advertised codec fields.
 * Bitrate by itself never proves a codec; file paths, artwork, release names
 * and other providers' versions must not be used as quality evidence.
 */
internal fun canonicalAssetSourceQuality(asset: CanonicalBrowseMediaAsset): SourceQuality? {
    val mime = asset.audioContentType?.substringBefore(';')
        ?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
    val suffix = asset.audioSuffix?.trim()?.lowercase(Locale.ROOT)
        ?.removePrefix(".")?.takeIf(String::isNotEmpty)
    // A declared but unsupported MIME overrides ambiguous suffix guesses.
    val format = (if (mime != null) when (mime) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/wav", "audio/wave", "audio/x-wav", "audio/vnd.wave" -> "wav"
        "audio/alac" -> "alac"
        "audio/aiff", "audio/x-aiff" -> "aiff"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/aac", "audio/aacp", "audio/mp4a-latm" -> "aac"
        "audio/opus" -> "opus"
        "audio/eac3-joc" -> "dolby-atmos"
        else -> null
    } else when (suffix) {
        "flac", "wav", "alac", "aiff", "aif" -> suffix
        "mp3", "aac", "opus" -> suffix
        else -> null
    }) ?: return null

    if (format == "dolby-atmos") return SourceQuality.DOLBY_ATMOS
    if (format in setOf("flac", "wav", "alac", "aiff", "aif")) {
        val bits = asset.audioBitDepth
        val hz = asset.audioSamplingRateHz
        return if (bits != null && bits >= 24 && hz != null && hz > 48_000) {
            SourceQuality.HI_RES_LOSSLESS
        } else SourceQuality.LOSSLESS
    }

    // Only a known lossy format at a measured/advertised high bitrate.
    // A contradictory 24/96 lossy header is not evidence of Hi-Res.
    if (asset.audioBitDepth != null && asset.audioBitDepth >= 24 &&
        asset.audioSamplingRateHz != null && asset.audioSamplingRateHz > 48_000) return null
    return if ((asset.audioBitrateBps ?: 0L) >= 256_000L) {
        SourceQuality.HIGH_QUALITY
    } else null
}

/**
 * A release badge describes the whole *visible, provider-scoped* album.
 * If one recording is unknown, or any selected-source copy disagrees,
 * hide the badge rather than advertising only the best single recording.
 */
internal fun canonicalAlbumSourceQuality(
    tracks: List<CanonicalBrowseTrack>,
    selectedProviderKey: String? = null,
): SourceQuality? {
    if (tracks.isEmpty()) return null
    var unanimous: SourceQuality? = null
    for (track in tracks) {
        val relevant = track.availableAssets.filter {
            selectedProviderKey == null || it.providerKey == selectedProviderKey
        }
        if (relevant.isEmpty()) return null
        for (asset in relevant) {
            val observed = canonicalAssetSourceQuality(asset) ?: return null
            if (unanimous == null) unanimous = observed
            else if (unanimous != observed) return null
        }
    }
    return unanimous
}

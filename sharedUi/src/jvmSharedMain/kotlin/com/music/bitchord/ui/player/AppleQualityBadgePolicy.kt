package com.music.bitchord.ui.player

/**
 * A visual classification based on the decoder's observed codec/bitrate, not
 * a catalog claim, a user preference, or a stream the resolver hoped to obtain.
 * Unknown is intentionally distinct from lossless (and from a measured lossy tier).
 */
internal enum class AppleQualityBadgeKind {
    NONE, LOADING, LOSSLESS, HI_RES, DOLBY_ATMOS, HIGH, MEDIUM,
}

internal fun appleQualityBadgeKind(
    measuredLossless: Boolean?,
    measuredHiRes: Boolean,
    measuredAtmos: Boolean,
    measuredHighQuality: Boolean,
    measuredMediumQuality: Boolean,
    resolving: Boolean,
): AppleQualityBadgeKind = when {
    measuredAtmos -> AppleQualityBadgeKind.DOLBY_ATMOS
    measuredLossless == true && measuredHiRes -> AppleQualityBadgeKind.HI_RES
    measuredLossless == true -> AppleQualityBadgeKind.LOSSLESS
    resolving && measuredLossless == null -> AppleQualityBadgeKind.LOADING
    measuredHighQuality -> AppleQualityBadgeKind.HIGH
    measuredMediumQuality -> AppleQualityBadgeKind.MEDIUM
    else -> AppleQualityBadgeKind.NONE
}

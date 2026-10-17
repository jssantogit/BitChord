package com.music.bitchord.data.catalog

/**
 * Minimal offline-browse projection for any provider-backed MediaAsset.
 * It deliberately contains canonical Recording metadata plus availability data,
 * not provider IDs masquerading as canonical IDs.
 */
internal data class CanonicalProviderRow(
    val recordingId: String,
    val mediaAssetId: String,
    val provider: String,
    val sourceKey: String,
    val title: String,
    val artistDisplay: String,
    val durationMs: Long?,
    val artworkUri: String?,
    val uri: String,
    val dateAddedSeconds: Long?,
)

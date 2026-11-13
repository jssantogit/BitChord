package com.music.bitchord.ui.screens

/** A MusicBrainz recording is an exact UUID, never a title, URL, videoId or MediaAsset.
 * Callers must already hold a Recording MBID obtained from a trusted catalog response.
 */
private val CATALOG_RECORDING_MBID = Regex(
    """^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"""
)

internal fun isVerifiedCatalogRecordingId(id: String): Boolean =
    CATALOG_RECORDING_MBID.matches(id)

/** A metadata-only Recording may be SAVED to a normal playlist but NEVER queued
 * until playback independently resolves a verified playable MediaAsset. */
internal fun planMetadataOnlyPlaylistMembers(
    existing: List<String>,
    recordingId: String,
): List<String>? {
    if (!isVerifiedCatalogRecordingId(recordingId) ||
        existing.size >= 50_000 && recordingId !in existing
    ) return null
    return if (recordingId in existing) existing else existing + recordingId
}

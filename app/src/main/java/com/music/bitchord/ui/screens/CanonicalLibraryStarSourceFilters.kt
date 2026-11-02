package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.visibleStarValues

/**
 * One provider-neutral Library projection for Songs, Albums and Artists.
 * The only identities used here are Catalog RecordingId and MediaAssetId.
 * Source selection narrows eligible renditions, never relabels a recording.
 * Missing/invalid account-scoped rating data is UNKNOWN, never five stars.
 */
internal fun filterCanonicalLibraryTracks(
    tracks: List<CanonicalBrowseTrack>,
    sourceKey: String?,
    stars: Int?,
    ratings: CanonicalRatingDisplaySnapshot,
): List<CanonicalBrowseTrack> {
    require(stars == null || stars in 1..5)
    return tracks.mapNotNull { track ->
        val available = track.availableAssets.filter {
            sourceKey == null || it.providerKey == sourceKey
        }
        if (available.isEmpty()) return@mapNotNull null
        if (stars != null && !matchesCanonicalLibraryStar(track.recordingId, available, ratings, stars)) {
            return@mapNotNull null
        }
        track.copy(availableAssets = available)
    }
}

private fun matchesCanonicalLibraryStar(
    recordingId: String,
    assets: List<CanonicalBrowseMediaAsset>,
    ratings: CanonicalRatingDisplaySnapshot,
    stars: Int,
): Boolean {
    val localValue = ratings.byRecordingId[recordingId]?.localValue
    if (localValue == stars.toDouble()) return true
    return assets.any { asset ->
        val rating: RatingDisplay = ratings.byMediaAssetId[asset.mediaAssetId]
            ?: return@any false
        rating.localValue == stars.toDouble() || rating.remoteValue == stars
    }
}

/** An album/artist survives the rating filter only with proven matching members. */
internal fun canonicalLibraryEntityHasMatchingTracks(
    memberRecordingIds: Collection<String>,
    visibleRecordingIds: Set<String>,
): Boolean = memberRecordingIds.any { it in visibleRecordingIds }

/**
 * The row's stars must follow the same scoped MediaAssets as source/rating
 * filtering. A Recording-local score is valid across its sources; remote notes
 * only belong to exact eligible MediaAsset IDs. Multiple distinct remote notes
 * stay visible as separate strips rather than being arbitrarily merged.
 */
internal fun canonicalLibraryVisibleRatings(
    track: CanonicalBrowseTrack,
    snapshot: CanonicalRatingDisplaySnapshot,
): List<RatingDisplay> {
    val local = snapshot.byRecordingId[track.recordingId]?.localValue
    val remote = track.availableAssets.mapNotNull {
        snapshot.byMediaAssetId[it.mediaAssetId]?.remoteValue
    }.distinct()
    if (remote.size <= 1) {
        val combined = RatingDisplay(localValue = local, remoteValue = remote.singleOrNull())
        return if (combined.visibleStarValues().isEmpty()) emptyList() else listOf(combined)
    }
    return listOfNotNull(local?.let { RatingDisplay(localValue = it) }) +
        remote.map { RatingDisplay(remoteValue = it) }
}

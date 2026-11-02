package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.visibleStarValues
import com.music.bitchord.data.settings.LocalMusicSort
import java.util.Locale

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
        track.copy(
            availableAssets = available,
            artworkUri = if (sourceKey == null) track.artworkUri else
                available.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) },
            dateAddedSeconds = available.mapNotNull { it.dateAddedSeconds }.maxOrNull(),
            dateModifiedSeconds = available.mapNotNull { it.dateModifiedSeconds }.maxOrNull(),
        )
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

/**
 * For uncredited artists the canonical Recording's own display string is a
 * browse-only grouping key; it must never become a new ArtistId. Source and
 * star filters run first, so groups cannot leak unavailable renditions. Unlike
 * database search helpers capped at 100, Library browse includes every group.
 */
internal fun canonicalLibraryDisplayArtistNames(
    tracks: List<CanonicalBrowseTrack>,
    creditedNamesLowercase: Set<String>,
): List<String> = tracks.asSequence()
    .map { it.artistDisplay }
    .filter { it.isNotBlank() && it.lowercase() !in creditedNamesLowercase }
    .distinct()
    .sortedWith(String.CASE_INSENSITIVE_ORDER)
    .toList()

/** Keep the native LocalFolder sort semantics on the unified canonical list.
 * Unknown source timestamps stay last; they are never invented from sync time.
 */
internal fun sortCanonicalUnifiedTracks(
    tracks: List<CanonicalBrowseTrack>,
    sort: LocalMusicSort,
): List<CanonicalBrowseTrack> {
    val alphabetic = compareBy<CanonicalBrowseTrack> { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.recordingId }
    return when (sort) {
        LocalMusicSort.TITLE_ASC -> tracks.sortedWith(alphabetic)
        LocalMusicSort.TITLE_DESC -> tracks.sortedWith(alphabetic.reversed())
        LocalMusicSort.DATE_ADDED -> tracks.sortedWith(
            compareBy<CanonicalBrowseTrack> { it.dateAddedSeconds == null }
                .thenByDescending { it.dateAddedSeconds }
                .then(alphabetic),
        )
        LocalMusicSort.DATE_MODIFIED -> tracks.sortedWith(
            compareBy<CanonicalBrowseTrack> { it.dateModifiedSeconds == null }
                .thenByDescending { it.dateModifiedSeconds }
                .then(alphabetic),
        )
    }
}

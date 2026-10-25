package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.my_ratings_local_source
import com.music.bitchord.sharedui.resources.my_ratings_remote_source
import org.jetbrains.compose.resources.stringResource

/** Provenance is *not* merged into a single number. In particular, starred != 5. */
enum class RatingDisplayKind { NO_RATING, RATED, CONFLICT, UNAVAILABLE }

data class RatingDisplay(
    val localValue: Double? = null,
    val remoteValue: Int? = null,
    val remoteAvailable: Boolean = true,
) {
    val kind: RatingDisplayKind
        get() = when {
            localValue != null && remoteValue != null && localValue != remoteValue.toDouble() ->
                RatingDisplayKind.CONFLICT
            localValue != null || remoteValue != null -> RatingDisplayKind.RATED
            !remoteAvailable -> RatingDisplayKind.UNAVAILABLE
            else -> RatingDisplayKind.NO_RATING
        }
}

/**
 * Icon-only strip for every surface. Equal notes from both origins are one
 * strip; disagreeing local/remote notes remain two distinct strips. Details
 * about those origins are available through accessibility, not visible text.
 */
fun RatingDisplay.visibleStarValues(): List<Double> {
    if (kind == RatingDisplayKind.NO_RATING || kind == RatingDisplayKind.UNAVAILABLE) {
        return emptyList()
    }
    return when {
        localValue != null && remoteValue != null && localValue == remoteValue.toDouble() ->
            listOf(localValue)
        else -> listOfNotNull(localValue, remoteValue?.toDouble())
    }
}

/**
 * One immutable, reactive batch snapshot for all visible rows and Now Playing.
 * No IO, resolver calls or Room collection may occur inside an individual row.
 * Only canonical/verified song keys are eligible; titles never identify songs.
 */
data class CanonicalRatingDisplaySnapshot(
    val bySongKey: Map<String, RatingDisplay> = emptyMap(),
    val byMediaAssetId: Map<String, RatingDisplay> = emptyMap(),
    val byRecordingId: Map<String, RatingDisplay> = emptyMap(),
    // Canonical asset identity remains available even for an un-rated track.
    // Unlike a remote rating, this is a verified catalog reference, never a
    // provider ID, an artist/title heuristic or a credential-bearing URL.
    val recordingIdBySongKey: Map<String, String> = emptyMap(),
) {
    fun forSong(song: Song): RatingDisplay? =
        bySongKey[song.videoId]
            ?: song.localUri?.let(bySongKey::get)
            ?: song.localPath?.let(bySongKey::get)

    fun recordingIdForSong(song: Song): String? =
        recordingIdBySongKey[song.videoId]
            ?: song.localUri?.let(recordingIdBySongKey::get)
            ?: song.localPath?.let(recordingIdBySongKey::get)
}

val LocalCanonicalRatingDisplay = compositionLocalOf { CanonicalRatingDisplaySnapshot() }

/** Compact visual evidence, separate from the existing explicit local rating editor. */
@Composable
fun CanonicalRatingStars(
    rating: RatingDisplay?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    val values = rating?.visibleStarValues().orEmpty()
    if (values.isEmpty()) return

    val localName = stringResource(Res.string.my_ratings_local_source)
    val remoteName = stringResource(Res.string.my_ratings_remote_source)
    val description = listOfNotNull(
        rating?.localValue?.let { "$it/5 · $localName" },
        rating?.remoteValue?.let { "$it/5 · $remoteName" },
    ).joinToString("; ")
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        horizontalAlignment = Alignment.End,
    ) {
        values.forEach { value -> RatingStarsLine(value, color) }
    }
}

@Composable
private fun RatingStarsLine(value: Double, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { index ->
            Icon(
                imageVector = when {
                    value >= index + 1.0 -> Icons.Rounded.Star
                    value >= index + 0.5 -> Icons.Rounded.StarHalf
                    else -> Icons.Rounded.StarBorder
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
fun CanonicalSongRatingStars(
    song: Song,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    val rating = LocalCanonicalRatingDisplay.current.forSong(song)
    // Before the first *native* rating, the three-dot song menu is the only
    // entry to the local editor. Remote evidence stays visible but read-only.
    // Once Room supplies a local score, touching these stars may edit it.
    val nativeEdit = onClick?.takeIf { rating?.localValue != null }
    CanonicalRatingStars(rating, modifier, color, onClick = nativeEdit)
}

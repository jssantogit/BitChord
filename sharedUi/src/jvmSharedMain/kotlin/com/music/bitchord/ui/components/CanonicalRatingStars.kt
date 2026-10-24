package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
 * One immutable, reactive batch snapshot for all visible rows and Now Playing.
 * No IO, resolver calls or Room collection may occur inside an individual row.
 * Only canonical/verified song keys are eligible; titles never identify songs.
 */
data class CanonicalRatingDisplaySnapshot(
    val bySongKey: Map<String, RatingDisplay> = emptyMap(),
    val byMediaAssetId: Map<String, RatingDisplay> = emptyMap(),
    val byRecordingId: Map<String, RatingDisplay> = emptyMap(),
) {
    fun forSong(song: Song): RatingDisplay? =
        bySongKey[song.videoId]
            ?: song.localUri?.let(bySongKey::get)
            ?: song.localPath?.let(bySongKey::get)
}

val LocalCanonicalRatingDisplay = compositionLocalOf { CanonicalRatingDisplaySnapshot() }

/** Compact visual evidence, separate from the existing explicit local rating editor. */
@Composable
fun CanonicalRatingStars(
    rating: RatingDisplay?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (rating == null || rating.kind == RatingDisplayKind.NO_RATING ||
        rating.kind == RatingDisplayKind.UNAVAILABLE) return

    val localName = stringResource(Res.string.my_ratings_local_source)
    val remoteName = stringResource(Res.string.my_ratings_remote_source)
    val local = rating.localValue?.let { value ->
        val display = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        "★ $display/5 · $localName"
    }
    val remote = rating.remoteValue?.let { "★ $it/5 · $remoteName" }
    val description = listOfNotNull(local, remote).joinToString("; ")
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
    ) {
        // Different values get different lines so both origins remain readable
        // at large font scales; equal values keep the narrow one-line variant.
        if (rating.localValue != null && rating.remoteValue != null &&
            rating.localValue == rating.remoteValue.toDouble()
        ) {
            Text(
                "★ ${rating.remoteValue}/5 · $localName · $remoteName",
                color = color,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            listOfNotNull(local, remote).forEach { label ->
                Text(
                    text = label,
                    color = color,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun CanonicalSongRatingStars(
    song: Song,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val rating = LocalCanonicalRatingDisplay.current.forSong(song)
    CanonicalRatingStars(rating, modifier, color)
}

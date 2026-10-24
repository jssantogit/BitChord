package com.music.bitchord.ui.components

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
    val local = rating.localValue
    val remote = rating.remoteValue
    val description = listOfNotNull(
        local?.let { "$local/5 · $localName" },
        remote?.let { "$remote/5 · $remoteName" },
    ).joinToString("; ")
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
    ) {
        // One line when origins agree, otherwise two independent rated lines.
        if (local != null && remote != null && local == remote.toDouble()) {
            RatingStarsLine(local, "$localName · $remoteName", color)
        } else {
            local?.let { RatingStarsLine(it, localName, color) }
            remote?.let { RatingStarsLine(it.toDouble(), remoteName, color) }
        }
    }
}

@Composable
private fun RatingStarsLine(value: Double, source: String, color: Color) {
    val numeric = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
        Text(
            text = "$numeric/5 · $source",
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

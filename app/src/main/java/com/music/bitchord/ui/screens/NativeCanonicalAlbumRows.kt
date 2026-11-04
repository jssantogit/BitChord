package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.NUMBERED_ROW_DIVIDER_INSET
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * A release track's number belongs to its verified running order, not its
 * position after user search, sorting or source filtering.
 */
internal fun nativeCanonicalAlbumRowNumber(
    recordingId: String,
    visibleIndex: Int,
    originalTrackNumbers: Map<String, Int>,
): Int = originalTrackNumbers[recordingId] ?: (visibleIndex + 1)

/**
 * Shared native album track rows between root Library albums and albums opened
 * inside an artist. The visual and actions stay in CanonicalTrackRow; this
 * compositor owns only the numbering, divider, stable RecordingId key and
 * callback slots. It does not use Song, legacy browseId or YouTube videoId.
 */
internal fun LazyListScope.nativeCanonicalAlbumTrackRows(
    tracks: List<CanonicalBrowseTrack>,
    artworkLoader: OpenSubsonicArtworkLoader,
    palette: ArtworkPalette,
    trackNumbers: Map<String, Int>,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
) {
    itemsIndexed(
        items = tracks,
        key = { _, track -> track.recordingId },
    ) { index, track ->
        CanonicalTrackRow(
            track = track,
            artworkLoader = artworkLoader,
            onClick = { onTrackPlay(track) },
            onEditRating = { onTrackEditRating(track) },
            onOptions = { onTrackOptions(track) },
            albumTrackNumber = nativeCanonicalAlbumRowNumber(
                track.recordingId, index, trackNumbers,
            ),
            palette = palette,
        )
        if (index < tracks.lastIndex) {
            HorizontalDivider(
                modifier = Modifier.padding(start = NUMBERED_ROW_DIVIDER_INSET),
                thickness = 0.5.dp,
                color = palette.divider,
            )
        }
    }
}

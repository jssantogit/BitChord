package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtSize
import com.music.bitchord.data.catalog.metadata.RoomMusicBrainzCoverArtStore
import com.music.bitchord.ui.loadCanonicalCoversProgressively
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.NativeCompactOverflowMenu
import com.music.bitchord.ui.components.NativeCompactMenuItem
import com.music.bitchord.ui.theme.ArtworkPalette

/** Only a confirmed Release MBID can supply the front for a recording.
 * A title/artist match would risk showing the wrong edition's cover. */
internal fun nativePublicTopCoverReleaseIds(
    rows: List<CanonicalCatalogSearchRow>,
): List<String> = rows.asSequence()
    .filter { it.artworkUri.isNullOrBlank() }
    .mapNotNull { it.coverReleaseMbid }
    .filter(String::isNotBlank)
    .distinct()
    .take(10)
    .toList()

/**
 * Online rank and Library tracks share the native track row geometry. The
 * online adapter never promotes a Recording MBID into a playable asset or
 * invents a three-dot/rating capability without a Library recording.
 */
@Composable
internal fun NativeArtistPublicTopSection(
    artistMbid: String,
    rows: List<CanonicalCatalogSearchRow>,
    palette: ArtworkPalette,
    sampled: Boolean = false,
    onRecordingOpen: (CanonicalCatalogSearchRow) -> Unit,
    onRecordingHold: ((CanonicalCatalogSearchRow) -> Unit)? = null,
) {
    val exact = rows.filter {
        it.kind == CanonicalSearchKind.RECORDING &&
            it.artistScopeMbid?.equals(artistMbid, ignoreCase = true) == true
    }
    val context = LocalContext.current.applicationContext
    val coverArt = remember(context) {
        CoverArtArchive(
            store = RoomMusicBrainzCoverArtStore(
                CanonicalCatalogDatabase.get(context).catalogDao(),
            ),
        )
    }
    val coverIds = nativePublicTopCoverReleaseIds(exact)
    val recoveredCovers by produceState(
        initialValue = emptyMap<String, String>(), key1 = coverIds,
    ) {
        if (coverIds.isEmpty()) return@produceState
        loadCanonicalCoversProgressively(
            keys = coverIds,
            maxConcurrent = 2,
            fetch = { edition ->
                withContext(Dispatchers.IO) {
                    try {
                        coverArt.findCover(edition, null, CoverArtSize.THUMBNAIL)
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { null }
                }
            },
            publish = { batch -> value = value + batch },
        )
    }
    NativeArtistTracksShelf(
        title = stringResource(R.string.top_songs),
        tracks = exact,
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        itemKey = { it.musicBrainzId },
    ) { recording ->
        val context = LocalContext.current
        var menuExpanded by remember(recording.musicBrainzId) { mutableStateOf(false) }
        NativeDetailTrackRowLayout(
            // A public Recording MBID is not a playable RecordingId. Unlike
            // the old metadata-only navigation there is no tap destination.
            onClick = if (recording.hasCatalogedAsset || recording.hasVerifiedLocalAsset)
                ({ onRecordingOpen(recording) }) else null,
            onLongPress = {
                if (onRecordingHold != null) onRecordingHold(recording)
                else menuExpanded = true
            },
            onMore = null,
            modifier = Modifier.testTag("artist-top-row-${recording.musicBrainzId}"),
            horizontalInset = 0.dp,
            leading = {
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.size(52.dp).clip(shape)
                        .background(palette.onBackground.copy(alpha = 0.08f)),
                ) {
                    CoverPlaceholder(Icons.Rounded.MusicNote, Modifier.fillMaxSize())
                    (recording.artworkUri?.takeIf(String::isNotBlank)
                        ?: recoveredCovers[recording.coverReleaseMbid])?.let { art ->
                        AsyncImage(
                            model = art.artworkAt(ROW_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
            title = {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                Text(
                    text = recording.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            rating = {},
            showRatingSlot = false,
            actions = {
                Box {
                    // Only clients without a global lifted-menu host keep
                    // this legacy-compatible metadata fallback.
                    if (onRecordingHold == null) NativeCompactOverflowMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        plateTint = palette.elevated,
                    ) {
                        NativeCompactMenuItem(
                            label = "Ver no MusicBrainz",
                            icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Link,
                            onClick = {
                                menuExpanded = false
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://musicbrainz.org/recording/${recording.musicBrainzId}"),
                                )
                                runCatching { context.startActivity(intent) }
                            },
                        )
                    }
                }
            },
        )
    }
}

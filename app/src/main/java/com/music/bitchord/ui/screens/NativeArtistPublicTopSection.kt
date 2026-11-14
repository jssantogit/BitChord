package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.Modifier
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
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.data.catalog.metadata.appleApprovedBadgeForStoreUrl

/**
 * Artist-top artwork must be certified by the exact Apple edition resolver,
 * not discovered through CAA, ListenBrainz image hints or fuzzy titles.
 */
internal fun nativePublicTopVerifiedAppleArtwork(
    row: CanonicalCatalogSearchRow,
): String? {
    val approved = appleApprovedBadgeForStoreUrl(row.appleStoreUrl)
    return row.artworkUri?.takeIf {
        safeAppleArtwork(it) != null &&
            approved != null && approved == row.appleStoreBadgeUrl
    }
}

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
) {
    val exact = rows.filter {
        it.kind == CanonicalSearchKind.RECORDING &&
            it.artistScopeMbid?.equals(artistMbid, ignoreCase = true) == true
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
            horizontalInset = 0.dp,
            leading = {
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.size(52.dp).clip(shape)
                        .background(palette.onBackground.copy(alpha = 0.08f)),
                ) {
                    CoverPlaceholder(Icons.Rounded.MusicNote, Modifier.fillMaxSize())
                    nativePublicTopVerifiedAppleArtwork(recording)?.let { art ->
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
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    val storeUrl = recording.appleStoreUrl
                    val badgeUrl = recording.appleStoreBadgeUrl
                    if (storeUrl != null && badgeUrl != null &&
                        nativePublicTopVerifiedAppleArtwork(recording) != null
                    ) {
                        AsyncImage(
                            model = badgeUrl,
                            contentDescription = "Abrir edição na loja Apple",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.width(86.dp).height(28.dp)
                                .clickable {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW, Uri.parse(storeUrl),
                                    )
                                    runCatching { context.startActivity(intent) }
                                },
                        )
                    }
                    Box {
                    RowMoreButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("artist-top-more-${recording.musicBrainzId}"),
                        tint = palette.onBackgroundVariant,
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Ver no MusicBrainz") },
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
                }
            },
        )
    }
}

package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.apple_search_view_details
import com.music.bitchord.ui.components.HeldContextMenu
import com.music.bitchord.ui.components.HeldItem
import com.music.bitchord.ui.components.NativeCompactMenuDivider
import com.music.bitchord.ui.components.NativeCompactMenuItem
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/** Public catalog metadata is not an audio source, regardless of a Room hit. */
internal fun canonicalSearchHeldQueueAllowed(@Suppress("UNUSED_PARAMETER") row: CanonicalCatalogSearchRow): Boolean =
    false

internal fun canonicalSearchHeldCanAddToPlaylist(kind: CanonicalSearchKind): Boolean =
    kind == CanonicalSearchKind.RECORDING || kind == CanonicalSearchKind.RELEASE ||
        kind == CanonicalSearchKind.RELEASE_GROUP

internal fun canonicalSearchHeldShowsDownload(kind: CanonicalSearchKind): Boolean =
    canonicalSearchHeldCanAddToPlaylist(kind)

/** Search uses long press only: no trailing ⋯ and no playback/queue actions. */
@Composable
internal fun CanonicalSearchHeldMenus(
    catalogRow: CanonicalCatalogSearchRow?,
    catalogOrigin: HeldItem?,
    publicPlaylist: CanonicalPublicPlaylistSearchRow?,
    publicPlaylistOrigin: HeldItem?,
    hazeState: HazeState,
    bottomChromeClearance: Dp = 100.dp,
    hostProvidesBlur: Boolean = false,
    onDismissCatalog: () -> Unit,
    onDismissPublicPlaylist: () -> Unit,
    onOpenCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onOpenPublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onAddCatalogToPlaylist: (CanonicalCatalogSearchRow) -> Unit,
    onAddPublicPlaylistToPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onRateCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onRatePublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onPinCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onPinPublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onShareCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onSharePublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onDownloadCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onDownloadPublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
) {
    val details = stringResource(Res.string.apple_search_view_details)
    HeldContextMenu(
        item = catalogRow,
        held = catalogOrigin,
        hazeState = hazeState,
        bottomChromeClearance = bottomChromeClearance,
        hostProvidesBlur = hostProvidesBlur,
        onDismiss = onDismissCatalog,
        onPreviewClick = { row -> onDismissCatalog(); onOpenCatalog(row) },
    ) { row ->
        NativeCompactMenuItem(
            label = details, icon = Icons.Rounded.Info,
            onClick = { onDismissCatalog(); onOpenCatalog(row) },
        )
        if (canonicalSearchHeldCanAddToPlaylist(row.kind)) {
            NativeCompactMenuItem(
                label = "Adicionar à playlist",
                icon = Icons.Rounded.PlaylistAdd,
                onClick = { onDismissCatalog(); onAddCatalogToPlaylist(row) },
            )
        }
        if (row.kind != CanonicalSearchKind.RECORDING) {
            NativeCompactMenuItem(
                label = "Editar avaliação", icon = PlaylistEditorIcons.RatingPlain,
                onClick = { onDismissCatalog(); onRateCatalog(row) },
            )
            NativeCompactMenuItem(
                label = "Fixar", icon = BitChordIcons.Pin,
                onClick = { onDismissCatalog(); onPinCatalog(row) },
            )
        }
        NativeCompactMenuItem(
            label = "Compartilhar", icon = Icons.Rounded.Share,
            onClick = { onDismissCatalog(); onShareCatalog(row) },
        )
        if (canonicalSearchHeldShowsDownload(row.kind)) {
            NativeCompactMenuDivider()
            NativeCompactMenuItem(
                label = "Baixar", icon = Icons.Rounded.Download,
                onClick = { onDismissCatalog(); onDownloadCatalog(row) },
            )
        }
    }
    HeldContextMenu(
        item = publicPlaylist,
        held = publicPlaylistOrigin,
        hazeState = hazeState,
        bottomChromeClearance = bottomChromeClearance,
        hostProvidesBlur = hostProvidesBlur,
        onDismiss = onDismissPublicPlaylist,
        onPreviewClick = { row -> onDismissPublicPlaylist(); onOpenPublicPlaylist(row) },
    ) { row ->
        NativeCompactMenuItem(
            label = details, icon = Icons.Rounded.Info,
            onClick = { onDismissPublicPlaylist(); onOpenPublicPlaylist(row) },
        )
        NativeCompactMenuItem(
            label = "Adicionar à playlist", icon = Icons.Rounded.PlaylistAdd,
            onClick = { onDismissPublicPlaylist(); onAddPublicPlaylistToPlaylist(row) },
        )
        NativeCompactMenuItem(
            label = "Editar avaliação", icon = PlaylistEditorIcons.RatingPlain,
            onClick = { onDismissPublicPlaylist(); onRatePublicPlaylist(row) },
        )
        NativeCompactMenuItem(
            label = "Fixar", icon = BitChordIcons.Pin,
            onClick = { onDismissPublicPlaylist(); onPinPublicPlaylist(row) },
        )
        NativeCompactMenuItem(
            label = "Compartilhar", icon = Icons.Rounded.Share,
            onClick = { onDismissPublicPlaylist(); onSharePublicPlaylist(row) },
        )
        NativeCompactMenuDivider()
        NativeCompactMenuItem(
            label = "Baixar", icon = Icons.Rounded.Download,
            onClick = { onDismissPublicPlaylist(); onDownloadPublicPlaylist(row) },
        )
    }
}

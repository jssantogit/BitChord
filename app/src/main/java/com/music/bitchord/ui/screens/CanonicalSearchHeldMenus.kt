package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.apple_search_view_details
import com.music.bitchord.sharedui.resources.smart_collection_batch_queue
import com.music.bitchord.ui.components.HeldContextMenu
import com.music.bitchord.ui.components.HeldItem
import com.music.bitchord.ui.components.NativeCompactMenuItem
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/** A search MBID without an available audio asset must never offer queue commands. */
internal fun canonicalSearchHeldQueueAllowed(row: CanonicalCatalogSearchRow): Boolean =
    row.kind == CanonicalSearchKind.RECORDING && row.hasCatalogedAsset

/** Lift Search's captured native row; do not route its MBID into the legacy browse menu. */
@Composable
internal fun CanonicalSearchHeldMenus(
    catalogRow: CanonicalCatalogSearchRow?,
    catalogOrigin: HeldItem?,
    publicPlaylist: CanonicalPublicPlaylistSearchRow?,
    publicPlaylistOrigin: HeldItem?,
    hazeState: HazeState,
    onDismissCatalog: () -> Unit,
    onDismissPublicPlaylist: () -> Unit,
    onOpenCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onQueueCatalog: (String) -> Unit,
    onOpenPublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
) {
    val detailsLabel = stringResource(Res.string.apple_search_view_details)
    val queueLabel = stringResource(Res.string.smart_collection_batch_queue)

    HeldContextMenu(
        item = catalogRow,
        held = catalogOrigin,
        hazeState = hazeState,
        onDismiss = onDismissCatalog,
        onPreviewClick = { row ->
            onDismissCatalog()
            onOpenCatalog(row)
        },
    ) { row ->
        NativeCompactMenuItem(label = detailsLabel, onClick = {
            onDismissCatalog()
            onOpenCatalog(row)
        })
        if (canonicalSearchHeldQueueAllowed(row)) {
            NativeCompactMenuItem(label = queueLabel, onClick = {
                onDismissCatalog()
                onQueueCatalog(row.musicBrainzId)
            })
        }
    }
    HeldContextMenu(
        item = publicPlaylist,
        held = publicPlaylistOrigin,
        hazeState = hazeState,
        onDismiss = onDismissPublicPlaylist,
        onPreviewClick = { row ->
            onDismissPublicPlaylist()
            onOpenPublicPlaylist(row)
        },
    ) { row ->
        NativeCompactMenuItem(label = detailsLabel, onClick = {
            onDismissPublicPlaylist()
            onOpenPublicPlaylist(row)
        })
    }
}

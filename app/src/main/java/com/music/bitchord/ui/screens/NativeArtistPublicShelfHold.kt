package com.music.bitchord.ui.screens

/**
 * Preserve the exact verified catalog row while the reusable card captures its
 * own origin for the existing HeldContextMenu. Metadata is never converted to
 * a local playable asset for the sake of a long-press menu.
 */
internal fun nativeArtistPublicShelfHoldAction(
    row: CanonicalCatalogSearchRow,
    onHold: ((CanonicalCatalogSearchRow) -> Unit)?,
): (() -> Unit)? = onHold?.let { action -> { action(row) } }

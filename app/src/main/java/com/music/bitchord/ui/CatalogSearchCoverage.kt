package com.music.bitchord.ui

import com.music.bitchord.data.model.SearchFilter

/**
 * A result in a different tab is not a hit for the selected search filter.
 * Vendor-neutral: use this for both Apple native products and MusicBrainz MBIDs.
 * It never attempts to compare or convert provider-native identifiers.
 */
internal fun catalogSearchHasUsableRows(
    filter: SearchFilter,
    artists: Boolean,
    albums: Boolean,
    songs: Boolean,
): Boolean = when (filter) {
    SearchFilter.ARTISTS -> artists
    SearchFilter.ALBUMS -> albums
    SearchFilter.SONGS -> songs
    SearchFilter.ALL -> artists || albums || songs
    else -> false
}

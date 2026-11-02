package com.music.bitchord

/**
 * Library→playlist entry is a pushed route, not an independent tab.
 * A visible wizard always takes priority over its parent playlist list.
 * Keep the route logic shared with the Android regression tests so a
 * branch reconciliation cannot silently bring back the legacy list.
 */
internal fun canonicalPlaylistRoute(
    showBuilder: Boolean,
    showLibrary: Boolean,
): String? = when {
    showBuilder -> "smart_playlist_builder"
    showLibrary -> "canonical_playlist_library"
    else -> null
}

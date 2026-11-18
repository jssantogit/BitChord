package com.music.bitchord

/**
 * The global FrostedTopBar outlives AnimatedContent pages and keeps some
 * navigation state alive for Back. A remembered source flag is NOT permission
 * to draw another page's search, rating, sort or overflow buttons.
 *
 * Route precedence must match BitChordApp.pageRouteKey. This deliberately
 * models only the toolbar owner, never the playback or navigation source.
 */
internal enum class NativeTopBarRouteOwner {
    UNIFIED_LIBRARY,
    PLAYLIST_LIBRARY,
    PUBLIC_PLAYLIST,
    RECENT_ADDITIONS,
    CATALOG_DETAIL,
    OTHER,
}

internal fun nativeTopBarRouteOwner(
    routeKey: String,
    activeDetailBrowseId: String?,
): NativeTopBarRouteOwner = when {
    routeKey == "unified_library" -> NativeTopBarRouteOwner.UNIFIED_LIBRARY
    routeKey == "canonical_playlist_library" -> NativeTopBarRouteOwner.PLAYLIST_LIBRARY
    routeKey.startsWith("public_playlist:") -> NativeTopBarRouteOwner.PUBLIC_PLAYLIST
    routeKey == "local_recent_additions" -> NativeTopBarRouteOwner.RECENT_ADDITIONS
    activeDetailBrowseId != null && routeKey == activeDetailBrowseId ->
        NativeTopBarRouteOwner.CATALOG_DETAIL
    else -> NativeTopBarRouteOwner.OTHER
}

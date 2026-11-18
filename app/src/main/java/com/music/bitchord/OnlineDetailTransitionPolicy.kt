package com.music.bitchord

/**
 * Search uses a legacy list composition while native catalog detail pages
 * own a separate page shell. Crossfading both leaves a ghost search frame on
 * the artist/album/playlist. Only these typed destinations need an immediate
 * outgoing exit; root tabs and local detail transitions are unchanged.
 */
internal fun onlineDetailRequiresImmediateExit(routeKey: String): Boolean =
    routeKey.startsWith("catalog:ARTIST:") ||
        routeKey.startsWith("catalog:RELEASE:") ||
        routeKey.startsWith("catalog:RELEASE_GROUP:") ||
        routeKey.startsWith("public_playlist:")

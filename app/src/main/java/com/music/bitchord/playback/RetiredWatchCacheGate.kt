package com.music.bitchord.playback

import android.net.Uri

/**
 * Legacy video/watch identifiers are archival only. When the cache layer sits
 * outside ResolvingDataSource, old spans may satisfy a request before the
 * resolver's HistoricalMediaAssetUnavailable rejection is reached.
 *
 * Route those logical requests around the cache, so the existing resolver
 * can deny them even when historical bytes are still on disk. This must not
 * change local file, canonical remote or source-backed extension routing.
 */
internal fun isRetiredWatchCacheRequest(uri: Uri): Boolean =
    uri.scheme.equals("bitchord", ignoreCase = true) &&
        uri.isHierarchical &&
        uri.authority != "source" &&
        uri.authority != "canonical" &&
        uri.getQueryParameter("v") != null

/** Only BitChord's own logical source URI may enter SourceResolver.
 * HTTP CDN resources, even on a host named "source", are media bytes and
 * must not be reclassified as catalogue requests.
 */
internal fun isLogicalSourcePlaybackUri(uri: Uri): Boolean =
    uri.scheme.equals("bitchord", ignoreCase = true) &&
        uri.isHierarchical &&
        uri.authority == "source"

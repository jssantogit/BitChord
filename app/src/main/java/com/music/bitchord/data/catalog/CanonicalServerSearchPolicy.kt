package com.music.bitchord.data.catalog

/**
 * A Server search hits only persisted, trusted remote media assets.
 * Public MusicBrainz identities and local-device files are not server
 * playback evidence; an opaque sourceKey or stream URL is never exposed.
 *
 * The SQL search uses matching prefixes, while this pure predicate is used
 * by tests and any future non-Room provider browsing adapter.
 */
internal fun isCatalogRemoteServerProvider(providerKey: String): Boolean =
    providerKey.startsWith("opensubsonic:") ||
        providerKey.startsWith("jellyfin:")

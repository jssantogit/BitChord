package com.music.bitchord.ui

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadLease

/**
 * An MBID artist/album page (including Room previews and pagination) is
 * MusicBrainz-owned editorial evidence, not a generic catalog search.
 *
 * A stored local media asset with the same MBID has separate PERSONAL_LIBRARY
 * ownership and is never erased or revoked by this boundary.
 */
internal fun canonicalMusicBrainzDetailLease(
    gate: ProviderPolicyGate?,
): ProviderReadLease? = ProviderReadLease.acquire(gate, listOf(
    ProviderCapability.EDITORIAL_DISCOGRAPHY to BuiltInProviderKeys.MUSICBRAINZ,
))

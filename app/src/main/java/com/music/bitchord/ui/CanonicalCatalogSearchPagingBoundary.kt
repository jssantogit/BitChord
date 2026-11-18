package com.music.bitchord.ui

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadLease

/** Native MusicBrainz search-page cursors belong to CATALOG_SEARCH only. */
internal fun canonicalCatalogSearchPagingLease(
    gate: ProviderPolicyGate?,
): ProviderReadLease? = ProviderReadLease.acquire(gate, listOf(
    ProviderCapability.CATALOG_SEARCH to BuiltInProviderKeys.MUSICBRAINZ,
))

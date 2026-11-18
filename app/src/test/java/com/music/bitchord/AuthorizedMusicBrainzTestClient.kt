package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.MusicBrainzRequestBudget
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.defaultMusicBrainzUserAgent
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import kotlinx.coroutines.delay

/**
 * Explicit test-only MusicBrainz policy for mocked network or bounded XML
 * transports under Robolectric. Production's missing-policy fail-closed
 * behavior remains unchanged, and gate/admission tests use their own policies.
 */
private val musicBrainzFixtureGate by lazy {
    ProviderCachedReadAccess.permissiveForUnitTests(
        capabilities = setOf(
            ProviderCapability.IDENTITY,
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
        ),
    ).currentGateOrNull()
}

internal fun authorizedMusicBrainzClient(
    transport: MusicBrainzTransport,
    rateLimiter: MusicBrainzRateLimiter = MusicBrainzRequestBudget.limiter,
    userAgent: String = defaultMusicBrainzUserAgent(),
    retryDelayMillis: suspend (Long) -> Unit = { delay(it) },
): MusicBrainzClient = MusicBrainzClient(
    transport = transport,
    rateLimiter = rateLimiter,
    userAgent = userAgent,
    retryDelayMillis = retryDelayMillis,
    admission = { musicBrainzFixtureGate },
)

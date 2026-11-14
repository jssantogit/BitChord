package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsArtistDiscoveryMode
import com.music.bitchord.data.catalog.metadata.discogs.discogsArtistDiscoveryMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscogsArtistDiscoveryModeTest {
    @Test fun noUserTokenStillPermitsSourceAnchoredPublicDiscovery() {
        assertEquals(DiscogsArtistDiscoveryMode.PUBLIC_ARTIST_LINK,
            discogsArtistDiscoveryMode(enabled = true, tokenConfigured = false))
    }

    @Test fun configuredTokenUsesAuthenticatedDiscoveryWithoutDuplicatingRequests() {
        assertEquals(DiscogsArtistDiscoveryMode.AUTHENTICATED_SEARCH,
            discogsArtistDiscoveryMode(enabled = true, tokenConfigured = true))
    }

    @Test fun disabledProviderDoesNotQueryEitherSource() {
        assertEquals(DiscogsArtistDiscoveryMode.DISABLED,
            discogsArtistDiscoveryMode(enabled = false, tokenConfigured = true))
        assertEquals(DiscogsArtistDiscoveryMode.DISABLED,
            discogsArtistDiscoveryMode(enabled = false, tokenConfigured = false))
    }
}

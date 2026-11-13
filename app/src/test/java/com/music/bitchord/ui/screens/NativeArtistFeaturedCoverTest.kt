package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class NativeArtistFeaturedCoverTest {
    private val groupId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test fun recentArtistHeroUsesVerifiedSavedFrontWithoutNetwork() = runBlocking {
        val group = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        var liveCalls = 0
        val url = nativeArtistFeaturedCoverFromCachedGroup(
            groupMbid = group,
            readVerified = { "https://archive.org/verified-250.jpg" },
            onlineLookup = { liveCalls++; null },
        )
        assertEquals("https://archive.org/verified-250.jpg", url)
        assertEquals(0, liveCalls)
    }

    @Test fun onlyVerifiedReleaseGroupIdentityCanStartFeaturedCoverFallback() {
        val verified = CanonicalCatalogSearchRow(
            musicBrainzId = groupId, kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "Verified release", subtitle = "Artist",
        )
        assertEquals(groupId, nativeArtistFeaturedReleaseCoverTarget(verified))
        assertNull(nativeArtistFeaturedReleaseCoverTarget(verified.copy(
            musicBrainzId = "not-an-mbid")))
        assertNull(nativeArtistFeaturedReleaseCoverTarget(verified.copy(
            kind = CanonicalSearchKind.ARTIST)))
        assertNull(nativeArtistFeaturedReleaseCoverTarget(verified.copy(
            kind = CanonicalSearchKind.RECORDING)))
    }
}

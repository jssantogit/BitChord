package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRange
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.ui.CanonicalSitewideDiscoveryState
import com.music.bitchord.ui.canonicalSitewideDiscoveryIsFresh
import com.music.bitchord.ui.canonicalSitewideDiscoveryExpiresAtMs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSitewideDiscoveryStateTest {
    @Test fun expiryUsesEarliestOfCacheTtlAndUpstreamAge() {
        val now = 1_800_000_000_000L
        val day = 24L * 60L * 60L * 1_000L
        val state = CanonicalSitewideDiscoveryState(
            range = ListenBrainzSitewideRange.WEEK,
            fetchedAtMs = now,
            upstreamUpdatedAtSeconds = (now - 7L * day) / 1_000L,
        )
        assertTrue(canonicalSitewideDiscoveryIsFresh(state, now))
        org.junit.Assert.assertEquals(
            now + 6L * 60L * 60L * 1_000L,
            canonicalSitewideDiscoveryExpiresAtMs(state),
        )
        val nearlyOutdated = state.copy(
            upstreamUpdatedAtSeconds = (now - 14L * day + 120_000L) / 1_000L,
        )
        org.junit.Assert.assertEquals(
            now + 120_000L,
            canonicalSitewideDiscoveryExpiresAtMs(nearlyOutdated),
        )
        org.junit.Assert.assertEquals(null, canonicalSitewideDiscoveryExpiresAtMs(
            state.copy(fetchedAtMs = null),
        ))
        org.junit.Assert.assertEquals(null, canonicalSitewideDiscoveryExpiresAtMs(
            state.copy(upstreamUpdatedAtSeconds = null),
        ))
    }

    @Test fun onlyVerifiedFreshWeeklyObservationsCanDriveExplore() {
        val example = CanonicalSitewideDiscoveryState(
            range = ListenBrainzSitewideRange.WEEK,
            recordings = listOf(ListenBrainzSitewideRecording(
                "13dd61c7-ce73-4e97-9f0c-9f0e53144411", 30)),
            fetchedAtMs = 1000L,
            upstreamUpdatedAtSeconds = 1L,
        )
        assertTrue(canonicalSitewideDiscoveryIsFresh(example, 1000L))
        assertTrue(canonicalSitewideDiscoveryIsFresh(example, 1000L + 100_000L))
        assertFalse(canonicalSitewideDiscoveryIsFresh(example, 999L))
        assertFalse(canonicalSitewideDiscoveryIsFresh(example,
            1000L + 6L * 60L * 60L * 1000L))
        assertFalse(canonicalSitewideDiscoveryIsFresh(example.copy(
            range = ListenBrainzSitewideRange.MONTH), 1100L))
        assertFalse(canonicalSitewideDiscoveryIsFresh(example.copy(
            fetchedAtMs = null), 1100L))
        assertFalse(canonicalSitewideDiscoveryIsFresh(example.copy(
            upstreamUpdatedAtSeconds = null), 1100L))
        assertFalse(canonicalSitewideDiscoveryIsFresh(example.copy(
            upstreamUpdatedAtSeconds = -1L), 1100L))
        val today = 1_800_000_000_000L
        assertFalse(canonicalSitewideDiscoveryIsFresh(example.copy(
            fetchedAtMs = today - 60_000L,
            upstreamUpdatedAtSeconds = (today - 20L * 24 * 60 * 60 * 1000) / 1000,
        ), today))
        assertTrue(canonicalSitewideDiscoveryIsFresh(example.copy(
            fetchedAtMs = today - 60_000L,
            upstreamUpdatedAtSeconds = (today - 7L * 24 * 60 * 60 * 1000) / 1000,
        ), today))
    }
}

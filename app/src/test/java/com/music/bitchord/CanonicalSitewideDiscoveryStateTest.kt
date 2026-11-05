package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRange
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.ui.CanonicalSitewideDiscoveryState
import com.music.bitchord.ui.canonicalSitewideDiscoveryIsFresh
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSitewideDiscoveryStateTest {
    @Test fun onlyVerifiedFreshWeeklyObservationsCanDriveExplore() {
        val example = CanonicalSitewideDiscoveryState(
            range = ListenBrainzSitewideRange.WEEK,
            recordings = listOf(ListenBrainzSitewideRecording(
                "13dd61c7-ce73-4e97-9f0c-9f0e53144411", 30)),
            fetchedAtMs = 1000L,
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
    }
}

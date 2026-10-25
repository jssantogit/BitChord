package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.ui.DiscoveryRequestEpoch
import com.music.bitchord.ui.LegacyDiscoveryRequest
import com.music.bitchord.ui.LegacyDiscoveryRequestGate
import com.music.bitchord.ui.shouldUseCanonicalDiscovery
import com.music.bitchord.ui.defaultDiscoverySearchSource
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.shouldPrefetchLegacyYouTubeAccountData
import com.music.bitchord.ui.isYouTubeSourceEnabled
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDiscoveryModeTest {
    @Test fun defaultAndEnabledYoutubeKeepLegacyDiscovery() {
        assertFalse(shouldUseCanonicalDiscovery(explicitlyEnabled = false, configs = emptyList()))
        assertFalse(shouldUseCanonicalDiscovery(
            explicitlyEnabled = false,
            configs = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true)),
        ))
    }

    @Test fun explicitOptInUsesCanonicalDiscoveryWithoutChangingSourceSettings() {
        assertTrue(shouldUseCanonicalDiscovery(explicitlyEnabled = true, configs = emptyList()))
        assertTrue(shouldUseCanonicalDiscovery(
            explicitlyEnabled = true,
            configs = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true)),
        ))
    }

    @Test fun disabledYoutubeUsesCanonicalDiscoveryEvenWithoutExplicitOptIn() {
        assertTrue(shouldUseCanonicalDiscovery(
            explicitlyEnabled = false,
            configs = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false)),
        ))
    }

    @Test fun disablingAnUnrelatedSourceDoesNotChangeDiscoveryMode() {
        assertFalse(shouldUseCanonicalDiscovery(
            explicitlyEnabled = false,
            configs = listOf(SourceConfig(kind = SourceKind.ADDON, enabled = false)),
        ))
    }

    @Test fun searchDefaultsToCatalogWhenOptedInOrYoutubeIsDisabled() {
        assertEquals(
            SearchSource.YOUTUBE,
            defaultDiscoverySearchSource(explicitlyEnabled = false, configs = emptyList()),
        )
        assertEquals(
            SearchSource.CANONICAL,
            defaultDiscoverySearchSource(explicitlyEnabled = true, configs = emptyList()),
        )
        assertEquals(
            SearchSource.CANONICAL,
            defaultDiscoverySearchSource(
                explicitlyEnabled = false,
                configs = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false)),
            ),
        )
    }

    @Test fun signedInAccountPrefetchSkipsCanonicalModeAndYoutubeOptOut() {
        val enabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        val disabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))

        assertTrue(shouldPrefetchLegacyYouTubeAccountData(
            signedIn = true, canonicalDiscoveryEnabled = false, configs = enabled,
        ))
        assertFalse(shouldPrefetchLegacyYouTubeAccountData(
            signedIn = true, canonicalDiscoveryEnabled = true, configs = enabled,
        ))
        assertFalse(shouldPrefetchLegacyYouTubeAccountData(
            signedIn = true, canonicalDiscoveryEnabled = false, configs = disabled,
        ))
        assertFalse(shouldPrefetchLegacyYouTubeAccountData(
            signedIn = false, canonicalDiscoveryEnabled = false, configs = enabled,
        ))
        assertTrue(isYouTubeSourceEnabled(enabled))
        assertFalse(isYouTubeSourceEnabled(disabled))
    }

    @Test fun canonicalHomeAndExploreDispatchMakesZeroLegacyCalls() = runBlocking {
        val called = mutableListOf<LegacyDiscoveryRequest>()
        val gate = LegacyDiscoveryRequestGate(useCanonicalDiscovery = true)

        LegacyDiscoveryRequest.entries.forEach { request ->
            gate.run(request) { called += request }
        }

        assertTrue(called.isEmpty())
    }

    @Test fun switchingBackToLegacyDiscoveryAllowsTheExistingEndpoints() = runBlocking {
        val called = mutableListOf<LegacyDiscoveryRequest>()
        val gate = LegacyDiscoveryRequestGate(useCanonicalDiscovery = false)

        LegacyDiscoveryRequest.entries.forEach { request ->
            gate.run(request) { called += request }
        }

        assertEquals(LegacyDiscoveryRequest.entries.toSet(), called.toSet())
    }

    @Test fun aResponseStartedBeforeModeChangeIsNoLongerCurrent() {
        val epoch = DiscoveryRequestEpoch()
        val startedBeforeSwitch = epoch.next()

        val startedAfterSwitch = epoch.invalidateAndNext()

        assertFalse(epoch.isCurrent(startedBeforeSwitch))
        assertTrue(epoch.isCurrent(startedAfterSwitch))
    }
}

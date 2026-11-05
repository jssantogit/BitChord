package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.ui.DiscoveryRequestEpoch
import com.music.bitchord.ui.LegacyDiscoveryRequest
import com.music.bitchord.ui.LegacyDiscoveryRequestGate
import com.music.bitchord.ui.runLegacyYoutubeBrowseIfEnabled
import com.music.bitchord.ui.shouldUseCanonicalDiscovery
import com.music.bitchord.ui.defaultDiscoverySearchSource
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.shouldPrefetchLegacyYouTubeAccountData
import com.music.bitchord.ui.isYouTubeSourceEnabled
import com.music.bitchord.ui.runLegacySpotifyMatchIfAllowed
import com.music.bitchord.ui.shouldRunLegacyYoutubeVersionLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDiscoveryModeTest {
    @Test fun canonicalDiscoveryDoesNotDependOnOldOptIn() {
        assertTrue(shouldUseCanonicalDiscovery(explicitlyEnabled = false, configs = emptyList()))
        assertTrue(shouldUseCanonicalDiscovery(
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
        assertTrue(shouldUseCanonicalDiscovery(
            explicitlyEnabled = false,
            configs = listOf(SourceConfig(kind = SourceKind.ADDON, enabled = false)),
        ))
    }

    @Test fun searchDefaultsToCatalogWhenOptedInOrYoutubeIsDisabled() {
        assertEquals(
            SearchSource.CANONICAL,
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

        assertFalse(shouldPrefetchLegacyYouTubeAccountData(
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
        assertFalse(isYouTubeSourceEnabled(enabled))
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

    @Test fun evenAnOldLegacyModeFlagCannotInvokeRemovedEndpoints() = runBlocking {
        val called = mutableListOf<LegacyDiscoveryRequest>()
        val gate = LegacyDiscoveryRequestGate(useCanonicalDiscovery = false)

        LegacyDiscoveryRequest.entries.forEach { request ->
            assertEquals(null, gate.run(request) { called += request; "unexpected" })
        }

        assertTrue(called.isEmpty())
    }

    @Test fun aResponseStartedBeforeModeChangeIsNoLongerCurrent() {
        val epoch = DiscoveryRequestEpoch()
        val startedBeforeSwitch = epoch.next()

        val startedAfterSwitch = epoch.invalidateAndNext()

        assertFalse(epoch.isCurrent(startedBeforeSwitch))
        assertTrue(epoch.isCurrent(startedAfterSwitch))
    }

    @Test fun canonicalModeNeverRunsLegacySpotifyYoutubeMatcherEvenWhenYoutubeProviderEnabled() = runBlocking {
        var calls = 0
        val result = runLegacySpotifyMatchIfAllowed(
            allowed = { false },
        ) {
            calls++
            "unexpected YT match"
        }
        assertEquals(null, result)
        assertEquals(0, calls)
    }

    @Test fun historicalSpotifyMatchingCannotCallADeletedBackend() = runBlocking {
        var calls = 0
        val result = runLegacySpotifyMatchIfAllowed(allowed = { true }) {
            calls++
            "unexpected-youtube-id"
        }
        assertEquals(null, result)
        assertEquals(0, calls)
    }

    @Test fun changingToCanonicalWhileMatchingDiscardsLegacyResult() = runBlocking {
        var eligible = true
        val result = runLegacySpotifyMatchIfAllowed(allowed = { eligible }) {
            eligible = false
            "stale-youtube-id"
        }
        assertEquals(null, result)
    }

    @Test fun canonicalModeAndExplicitOptOutBlockBackgroundYoutubeVersionProbes() {
        val enabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        val disabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(canonicalMode = true, configs = enabled))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(canonicalMode = true, configs = emptyList()))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(canonicalMode = false, configs = disabled))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(canonicalMode = false, configs = enabled))
        // An old install must not reactivate background YouTube discovery.
        assertFalse(shouldRunLegacyYoutubeVersionLookup(canonicalMode = false, configs = emptyList()))
    }

    @Test fun legacyBrowseNeverRunsEvenWhenAnOldFlagSaysEnabled() = runBlocking {
        var requests = 0
        for (enabled in listOf(false, true)) {
            val response = runLegacyYoutubeBrowseIfEnabled({ enabled }) {
                requests++
                "unexpected page"
            }
            assertEquals(null, response)
        }
        assertEquals(0, requests)
    }
}

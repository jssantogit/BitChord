package com.music.bitchord

import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.orderedCatalogProviders
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogProviderRoutingTest {
    private val musicBrainz = BuiltInProviderKeys.MUSICBRAINZ
    private val apple = BuiltInProviderKeys.ITUNES
    private val supported = setOf(musicBrainz, apple)

    private fun policy(
        mbSearch: Boolean,
        appleSearch: Boolean,
        appleFirst: Boolean,
        mbEditorial: Boolean = true,
        appleEditorial: Boolean = true,
    ) = ProviderCapabilityPolicy(
        descriptors = listOf(
            BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY),
            BuiltInProviderDescriptors.itunes(ProviderReadiness.READY),
        ),
        choices = mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, mbSearch, if (appleFirst) 1 else 0),
                ProviderChoice(apple, appleSearch, if (appleFirst) 0 else 1),
            ),
            ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(musicBrainz, mbEditorial, 0),
                ProviderChoice(apple, appleEditorial, 1),
            ),
        ),
    )

    @Test fun catalogFollowsPreferenceWithoutRewritingNativeProviderIdentity() {
        val gate = ProviderPolicyGate(policy(true, true, false))
        assertEquals(listOf(musicBrainz, apple), orderedCatalogProviders(
            gate, ProviderCapability.CATALOG_SEARCH, supported,
        ))
        gate.replace(policy(true, true, true))
        assertEquals(listOf(apple, musicBrainz), orderedCatalogProviders(
            gate, ProviderCapability.CATALOG_SEARCH, supported,
        ))
    }

    @Test fun disabledLastProviderCannotBeQueriedOrSuppliedByFallback() {
        val gate = ProviderPolicyGate(policy(false, false, true))
        assertEquals(emptyList<com.music.bitchord.data.providers.ProviderKey>(),
            orderedCatalogProviders(gate, ProviderCapability.CATALOG_SEARCH, supported))
        gate.replace(policy(true, false, true))
        assertEquals(listOf(musicBrainz),
            orderedCatalogProviders(gate, ProviderCapability.CATALOG_SEARCH, supported))
    }

    @Test fun searchAndEditorialCapabilitiesRemainIndependent() {
        val gate = ProviderPolicyGate(policy(
            mbSearch = false, appleSearch = true, appleFirst = false,
            mbEditorial = true, appleEditorial = false,
        ))
        assertEquals(listOf(apple), orderedCatalogProviders(
            gate, ProviderCapability.CATALOG_SEARCH, supported))
        assertEquals(listOf(musicBrainz), orderedCatalogProviders(
            gate, ProviderCapability.EDITORIAL_DISCOGRAPHY, supported))
    }

    @Test fun missingPolicyAndUnsupportedProvidersFailClosed() {
        assertEquals(emptyList<com.music.bitchord.data.providers.ProviderKey>(),
            orderedCatalogProviders(null, ProviderCapability.CATALOG_SEARCH, supported))
        val gate = ProviderPolicyGate(policy(true, true, false))
        assertEquals(emptyList<com.music.bitchord.data.providers.ProviderKey>(),
            orderedCatalogProviders(gate, ProviderCapability.CATALOG_SEARCH, emptySet()))
    }
}

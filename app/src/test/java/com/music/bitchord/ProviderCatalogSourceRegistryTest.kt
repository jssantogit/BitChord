package com.music.bitchord

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSettingsTab
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceHealth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogSourceRegistryTest {
    @Test fun navidromeAccountRegistersIndependentLibraryAndPlaybackCapabilities() {
        val key = ProviderKey("opensubsonic", "home")
        val entries = ProviderCapabilityRegistry.inventory(
            openSubsonicAccountId = "home",
            openSubsonicConfigured = true,
        ).filter { it.key == key }
        assertEquals(
            setOf(ProviderCapability.PERSONAL_LIBRARY, ProviderCapability.AUDIO_PLAYBACK),
            entries.map { it.capability }.toSet(),
        )
        assertTrue(entries.all { it.tab == ProviderSettingsTab.AUDIO_AND_EXTENSIONS })
        assertTrue(entries.all { it.readiness == ProviderReadiness.READY })
        assertTrue(ProviderCapabilityRegistry.inventory().none { it.key.providerId == "opensubsonic" })
        assertTrue(ProviderCapabilityRegistry.inventory(
            openSubsonicAccountId = "home", openSubsonicConfigured = false,
        ).filter { it.key == key }.all {
            it.readiness == ProviderReadiness.NEEDS_CONFIGURATION
        })

        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(entries)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors, mapOf(
            ProviderCapability.PERSONAL_LIBRARY to listOf(ProviderChoice(key, true, 0)),
            ProviderCapability.AUDIO_PLAYBACK to listOf(ProviderChoice(key, false, 0)),
        )))
        assertTrue(gate.open(ProviderCapability.PERSONAL_LIBRARY, key) != null)
        assertTrue(gate.open(ProviderCapability.AUDIO_PLAYBACK, key) == null)
    }

    @Test fun playbackSelectionRequiresExactExtensionInstanceTicket() {
        val a = ProviderKey("addon", "addon-one")
        val b = ProviderKey("addon", "addon-two")
        val descriptor = listOf(a, b).map { ProviderDescriptor(
            it, setOf(ProviderCapability.EXTENSION_PLAYBACK), ProviderReadiness.READY,
        ) }
        fun policy(enabledB: Boolean) = ProviderCapabilityPolicy(
            descriptor,
            mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(a, true, 0),
                ProviderChoice(b, enabledB, 1),
            )),
        )
        val entries = listOf("addon-one", "addon-two").map { SourceConfig(
            id = it, kind = SourceKind.ADDON,
            baseUrl = "https://example.org/manifest.json", enabled = true,
        ) }
        assertTrue(SourceRegistry.extensionEligibleConfigs(entries, null).isEmpty())
        val gate = ProviderPolicyGate(policy(true))
        assertEquals(entries, SourceRegistry.extensionEligibleConfigs(entries, gate))
        gate.replace(policy(false))
        assertEquals(listOf(entries.first()), SourceRegistry.extensionEligibleConfigs(entries, gate))
        assertTrue(SourceRegistry.extensionEligibleConfigs(listOf(entries.last()), gate).isEmpty())
    }

    @Test fun canonicalPriorityIsIndependentForPlaybackAndDownload() {
        val addon = SourceConfig(id = "addon-first", kind = SourceKind.ADDON,
            baseUrl = "https://addon.example", enabled = true)
        val module = SourceConfig(id = "module-second", kind = SourceKind.CUSTOM_MODULE,
            baseUrl = "https://module.example/index.json", enabled = true)
        val a = ProviderKey("addon", addon.id)
        val m = ProviderKey("custom_module", module.id)
        val descriptors = listOf(a, m).map { ProviderDescriptor(
            it, setOf(ProviderCapability.EXTENSION_PLAYBACK, ProviderCapability.DOWNLOAD),
            ProviderReadiness.READY,
        ) }
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors, mapOf(
            ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(m, true, 0), ProviderChoice(a, true, 1)),
            ProviderCapability.DOWNLOAD to listOf(
                ProviderChoice(a, true, 0), ProviderChoice(m, true, 1)),
        )))
        val legacyOrder = listOf(addon, module)
        assertEquals(listOf(module, addon), SourceRegistry.orderCandidatesByCapability(
            legacyOrder, gate, ProviderCapability.EXTENSION_PLAYBACK))
        assertEquals(listOf(addon, module), SourceRegistry.orderCandidatesByCapability(
            legacyOrder, gate, ProviderCapability.DOWNLOAD))
    }

    @Test fun providerPriorityTiesPreserveLegacyOrder() {
        val first = SourceConfig(id = "first", kind = SourceKind.ADDON,
            baseUrl = "https://one.example")
        val second = SourceConfig(id = "second", kind = SourceKind.ADDON,
            baseUrl = "https://two.example")
        // No ready provider ticket: eligibility is checked at selection time;
        // this ordering helper must not reshuffle a stored legacy list.
        assertEquals(listOf(second, first), SourceRegistry.orderCandidatesByCapability(
            listOf(second, first), null, ProviderCapability.EXTENSION_PLAYBACK))
    }

    @Test fun legacyProjectionCannotRebuildRetiredBackends() {
        val before = SourceRegistry.configs.value
        try {
            val jio = SourceConfig(id = "retired-jio", kind = SourceKind.JIOSAAVN, enabled = true)
            val yt = SourceConfig(id = "retired-yt", kind = SourceKind.YOUTUBE, enabled = true)
            SourceRegistry.applyProviderProjection(listOf(jio, yt))
            assertTrue(SourceRegistry.configs.value.isEmpty())
            assertTrue(SourceRegistry.instance(jio.id) == null)
            assertTrue(SourceRegistry.instance(yt.id) == null)
        } finally {
            SourceRegistry.applyProviderProjection(before)
        }
    }

    @Test fun retiredJioSaavnCannotBeSeededDisplayedOrSelectedFromLegacyPreferences() {
        val retired = SourceConfig(id = "old-jiosaavn", kind = SourceKind.JIOSAAVN, enabled = true)
        val userAddon = SourceConfig(
            id = "new-addon", kind = SourceKind.ADDON,
            baseUrl = "https://example.org/manifest.json", enabled = true,
        )
        val migrated = SourceRegistry.sourcesForInit(listOf(retired, userAddon), false)
        assertEquals(listOf(userAddon), migrated)
        assertTrue(SourceRegistry.sourcesForInit(emptyList(), false).isEmpty())
        assertTrue(SourceRegistry.displayConfigs(listOf(retired)).isEmpty())
        assertTrue(SourceRegistry.audioCandidateConfigs(listOf(retired)).isEmpty())
        assertTrue(runBlocking {
            SourceRegistry.probeCandidate(retired) is SourceHealth.Rejected
        })
    }

    @Test fun downloadWalkIsIndependentOfAddonPlaybackChoiceAndLegacyEnabledFlag() {
        val key = ProviderKey("addon", "download-only")
        fun policy(playback: Boolean, download: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key,
                setOf(ProviderCapability.EXTENSION_PLAYBACK, ProviderCapability.DOWNLOAD),
                ProviderReadiness.READY,
            )),
            choices = mapOf(
                ProviderCapability.EXTENSION_PLAYBACK to listOf(ProviderChoice(key, playback, 0)),
                ProviderCapability.DOWNLOAD to listOf(ProviderChoice(key, download, 0)),
            ),
        )
        val addon = SourceConfig(
            id = "download-only",
            kind = SourceKind.ADDON,
            baseUrl = "https://example.org/manifest.json",
            enabled = false, // Mirrors disabled legacy playback projection.
            allowDownloads = true,
        )
        val gate = ProviderPolicyGate(policy(playback = false, download = true))
        assertTrue(SourceRegistry.extensionEligibleConfigs(listOf(addon), gate).isEmpty())
        assertEquals(listOf(addon), SourceRegistry.downloadCandidateConfigs(listOf(addon), gate))
        gate.replace(policy(playback = true, download = false))
        assertTrue(SourceRegistry.downloadCandidateConfigs(listOf(addon), gate).isEmpty())
        assertEquals(listOf(addon), SourceRegistry.extensionEligibleConfigs(listOf(addon), gate))
        assertTrue(SourceRegistry.downloadCandidateConfigs(listOf(addon), null).isEmpty())
        assertTrue(SourceRegistry.downloadCandidateConfigs(
            listOf(addon.copy(baseUrl = "")), ProviderPolicyGate(policy(false, true)),
        ).isEmpty())
    }

    @Test fun addonDownloadRevocationPreservesPlaybackAndCannotUseLegacyEnabledFallback() {
        val key = ProviderKey("addon", "addon-one")
        val provider = ProviderDescriptor(
            key = key,
            capabilities = setOf(
                ProviderCapability.EXTENSION_PLAYBACK,
                ProviderCapability.DOWNLOAD,
            ),
            readiness = ProviderReadiness.READY,
        )
        fun policy(download: Boolean) = ProviderCapabilityPolicy(
            listOf(provider), mapOf(
                ProviderCapability.EXTENSION_PLAYBACK to listOf(ProviderChoice(key, true, 0)),
                ProviderCapability.DOWNLOAD to listOf(ProviderChoice(key, download, 0)),
            ),
        )
        val addon = SourceConfig(
            id = "addon-one",
            kind = SourceKind.ADDON,
            baseUrl = "https://example.org/manifest.json",
            enabled = true,
            allowDownloads = true,
        )
        val gate = ProviderPolicyGate(policy(false))
        assertTrue(SourceRegistry.downloadEligibleConfigs(listOf(addon), gate).isEmpty())
        assertTrue(gate.open(ProviderCapability.EXTENSION_PLAYBACK, key) != null)
        gate.replace(policy(true))
        assertEquals(listOf(addon), SourceRegistry.downloadEligibleConfigs(listOf(addon), gate))
        assertTrue(SourceRegistry.downloadEligibleConfigs(listOf(addon), null).isEmpty())
        assertTrue(SourceRegistry.downloadEligibleConfigs(
            listOf(addon.copy(allowDownloads = false)), gate,
        ).isEmpty())
    }
}

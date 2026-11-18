package com.music.bitchord

import android.content.Context
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderAuthorityCoordinator
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderCapabilitySession
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/** The catalog-216 integration branch must not depend on PR-217 scrobble hooks. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CatalogProviderEngineIntegrationTest {
    @Test fun actualAppleCatalogAndArtworkRemainIndependentlySwitchableAfterRestart(): Unit =
        runBlocking {
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
                "catalog-provider-integration-test", Context.MODE_PRIVATE,
            )
            prefs.edit().clear().commit()
            val descriptors = ProviderCapabilityRegistry.descriptorsFrom(
                ProviderCapabilityRegistry.inventory(
                    itunesImplemented = true,
                    discogsImplemented = true,
                    discogsTokenConfigured = false,
                ),
            )
            val first = ProviderCapabilitySession(ProviderPreferenceStore(prefs), descriptors)
            val coordinator = ProviderAuthorityCoordinator(first)
            assertTrue(coordinator.migrateOnce(
                lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
                itunesImplemented = true, discogsImplemented = true,
            ))
            assertTrue(first.gate.open(ProviderCapability.CATALOG_SEARCH,
                BuiltInProviderKeys.ITUNES) != null)
            assertTrue(first.gate.open(ProviderCapability.EDITORIAL_DISCOGRAPHY,
                BuiltInProviderKeys.ITUNES) != null)
            assertTrue(first.gate.open(ProviderCapability.RELEASE_ARTWORK,
                BuiltInProviderKeys.ITUNES) != null)
            assertNull(first.gate.open(ProviderCapability.EDITION_METADATA,
                BuiltInProviderKeys.DISCOGS))
            assertTrue(first.update(ProviderCapability.RELEASE_ARTWORK, listOf(
                ProviderChoice(BuiltInProviderKeys.ITUNES, false, 0),
            )))
            val restarted = ProviderCapabilitySession(ProviderPreferenceStore(prefs), descriptors)
            assertTrue(ProviderAuthorityCoordinator(restarted).migrateOnce(
                lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
                itunesImplemented = true, discogsImplemented = true,
            ))
            assertNull(restarted.gate.open(ProviderCapability.RELEASE_ARTWORK,
                BuiltInProviderKeys.ITUNES))
            assertTrue(restarted.gate.open(ProviderCapability.CATALOG_SEARCH,
                BuiltInProviderKeys.ITUNES) != null)
        }
}

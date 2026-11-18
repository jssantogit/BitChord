package com.music.bitchord

import com.music.bitchord.data.providers.CatalogEntryProvenance
import com.music.bitchord.data.providers.CatalogProvenanceFilter
import com.music.bitchord.data.providers.ProvenanceScopedItem
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogProvenanceFilterTest {
    private val musicBrainz = ProviderKey("musicbrainz")
    private val apple = ProviderKey("itunes")

    private fun gate(mbEnabled: Boolean, appleEnabled: Boolean) = ProviderPolicyGate(
        ProviderCapabilityPolicy(
            descriptors = listOf(
                ProviderDescriptor(
                    musicBrainz,
                    setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY),
                    ProviderReadiness.READY,
                ),
                ProviderDescriptor(
                    apple,
                    setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY),
                    ProviderReadiness.READY,
                ),
            ),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(
                    ProviderChoice(musicBrainz, mbEnabled, 0),
                    ProviderChoice(apple, appleEnabled, 1),
                ),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                    ProviderChoice(musicBrainz, mbEnabled, 0),
                    ProviderChoice(apple, appleEnabled, 1),
                ),
            ),
        ),
    )

    @Test fun disablingMusicBrainzHidesEditorialOnlyRowsButPreservesLocalAndCorroboratedMbidItems() {
        val localWithMbid = ProvenanceScopedItem(
            value = "local-magnetic",
            mbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c",
            provenances = setOf(
                CatalogEntryProvenance.LocalOrPersonalAsset,
                CatalogEntryProvenance.ProviderEditorial(musicBrainz, ProviderCapability.CATALOG_SEARCH),
            ),
        )
        val userRatedWithMbid = ProvenanceScopedItem(
            value = "rated-how-sweet",
            mbid = "9750354e-78df-4058-8758-b5a7a2eccf66",
            provenances = setOf(
                CatalogEntryProvenance.UserCurated,
                CatalogEntryProvenance.ProviderEditorial(musicBrainz, ProviderCapability.EDITORIAL_DISCOGRAPHY),
            ),
        )
        val appleCorroboratedWithMbid = ProvenanceScopedItem(
            value = "apple-super-real-me",
            mbid = "6b128dd7-e998-4edb-a4f5-400fef190445",
            provenances = setOf(
                CatalogEntryProvenance.ProviderEditorial(musicBrainz, ProviderCapability.CATALOG_SEARCH),
                CatalogEntryProvenance.ProviderEditorial(apple, ProviderCapability.CATALOG_SEARCH),
            ),
        )
        val musicBrainzOnly = ProvenanceScopedItem(
            value = "mb-only-hit",
            mbid = "11111111-2222-4333-8444-555555555555",
            provenances = setOf(
                CatalogEntryProvenance.ProviderEditorial(musicBrainz, ProviderCapability.CATALOG_SEARCH),
            ),
        )

        val items = listOf(localWithMbid, userRatedWithMbid, appleCorroboratedWithMbid, musicBrainzOnly)

        val visibleWhenMbOff = CatalogProvenanceFilter.filterVisible(
            items = items,
            gate = gate(mbEnabled = false, appleEnabled = true),
        )
        assertEquals(
            listOf("local-magnetic", "rated-how-sweet", "apple-super-real-me"),
            visibleWhenMbOff,
        )

        val visibleWhenAllEditorialOff = CatalogProvenanceFilter.filterVisible(
            items = items,
            gate = gate(mbEnabled = false, appleEnabled = false),
        )
        assertEquals(listOf("local-magnetic", "rated-how-sweet"), visibleWhenAllEditorialOff)

        // Fail-closed when gate is null: editorial-only entries are suppressed, personal items stay.
        val visibleWithoutGate = CatalogProvenanceFilter.filterVisible(items = items, gate = null)
        assertEquals(listOf("local-magnetic", "rated-how-sweet"), visibleWithoutGate)
        assertTrue("mb-only-hit" !in visibleWithoutGate)
    }
}

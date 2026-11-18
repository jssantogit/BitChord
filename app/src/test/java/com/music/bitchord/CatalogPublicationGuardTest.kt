package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.ui.catalogPublicationAllowed
import com.music.bitchord.ui.catalogSearchOrderIsCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPublicationGuardTest {
    private val provider = ProviderKey("test-catalog", "instance-one")
    private val sibling = ProviderKey("test-catalog", "instance-two")
    private fun policy(firstEnabled: Boolean, siblingEnabled: Boolean, reverse: Boolean = false) =
        ProviderCapabilityPolicy(
            listOf(provider, sibling).map { key ->
                ProviderDescriptor(key, setOf(ProviderCapability.CATALOG_SEARCH),
                    readiness = ProviderReadiness.READY)
            },
            mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(provider, firstEnabled, if (reverse) 1 else 0),
                ProviderChoice(sibling, siblingEnabled, if (reverse) 0 else 1),
            )),
        )

    @Test fun previewMustNotPublishAfterRevocationEvenIfHttpAlreadyCompleted() {
        val gate = ProviderPolicyGate(policy(true, true))
        val ticket = gate.open(ProviderCapability.CATALOG_SEARCH, provider)!!
        assertTrue(catalogPublicationAllowed(gate, ticket, true, true, true))
        gate.replace(policy(false, true))
        assertFalse(catalogPublicationAllowed(gate, ticket, true, true, true))
        assertTrue(gate.open(ProviderCapability.CATALOG_SEARCH, sibling) != null)
    }


    @Test fun lateCatalogPageCannotPublishAfterPriorityReorderWithoutRevokingTicket() {
        val gate = ProviderPolicyGate(policy(true, true))
        val ticket = gate.open(ProviderCapability.CATALOG_SEARCH, provider)!!
        val originalOrder = gate.ordered(ProviderCapability.CATALOG_SEARCH).map { it.key }
        assertTrue(catalogSearchOrderIsCurrent(provider, originalOrder, originalOrder))
        gate.replace(policy(true, true, reverse = true))
        val reordered = gate.ordered(ProviderCapability.CATALOG_SEARCH).map { it.key }
        // Priority-only changes preserve the original capability ticket, but
        // must invalidate any response from the previous display order.
        assertTrue(gate.accept(ticket))
        assertFalse(catalogSearchOrderIsCurrent(provider, originalOrder, reordered))
        assertTrue(catalogPublicationAllowed(gate, ticket, true, true, true))
    }

    @Test fun oldCatalogPagesAreRejectedWhenLastProviderDisabledOrMissing() {
        val originalOrder = listOf(provider, sibling)
        assertFalse(catalogSearchOrderIsCurrent(provider, originalOrder, listOf(sibling)))
        assertFalse(catalogSearchOrderIsCurrent(provider, originalOrder, emptyList()))
        assertFalse(catalogSearchOrderIsCurrent(ProviderKey("unregistered"), originalOrder, originalOrder))
        assertTrue(catalogSearchOrderIsCurrent(sibling, originalOrder, originalOrder))
    }

    @Test fun changingQueryOrVisibleProviderRejectsOldSnapshot() {
        val gate = ProviderPolicyGate(policy(true, true))
        val ticket = gate.open(ProviderCapability.CATALOG_SEARCH, provider)!!
        assertFalse(catalogPublicationAllowed(gate, ticket, false, true, true))
        assertFalse(catalogPublicationAllowed(gate, ticket, true, false, true))
        assertFalse(catalogPublicationAllowed(gate, ticket, true, true, false))
        assertFalse(catalogPublicationAllowed(null, ticket, true, true, true))
        assertFalse(catalogPublicationAllowed(gate, null, true, true, true))
    }
}

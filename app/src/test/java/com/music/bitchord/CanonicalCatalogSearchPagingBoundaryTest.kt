package com.music.bitchord

import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.ui.canonicalCatalogSearchPagingLease
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCatalogSearchPagingBoundaryTest {
    private val key = BuiltInProviderKeys.MUSICBRAINZ
    private fun policy(search: Boolean, editorial: Boolean) = ProviderCapabilityPolicy(
        descriptors = listOf(BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY)),
        choices = mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(key, search, 0)),
            ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(key, editorial, 0)),
        ),
    )

    @Test fun searchPagesDoNotBorrowAnEditorialOnlyGrant() {
        val gate = ProviderPolicyGate(policy(search = false, editorial = true))
        assertNull(canonicalCatalogSearchPagingLease(gate))
        gate.replace(policy(search = true, editorial = false))
        assertNotNull(canonicalCatalogSearchPagingLease(gate))
        assertNull(canonicalCatalogSearchPagingLease(null))
    }

    @Test fun revocationCancelsPagingWithoutCancellingAuthorizedDiscography() {
        val gate = ProviderPolicyGate(policy(search = true, editorial = true))
        val lease = canonicalCatalogSearchPagingLease(gate)!!
        val pageJob = Job()
        val unregister = lease.cancelJobOnRevoke(pageJob)
        gate.replace(policy(search = true, editorial = false))
        assertTrue(lease.isCurrent())
        assertTrue(pageJob.isActive)
        gate.replace(policy(search = false, editorial = false))
        assertFalse(lease.isCurrent())
        assertTrue(pageJob.isCancelled)
        unregister()
    }
}

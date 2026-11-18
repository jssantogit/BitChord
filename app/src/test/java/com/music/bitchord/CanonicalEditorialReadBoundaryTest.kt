package com.music.bitchord

import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.ui.canonicalMusicBrainzDetailLease
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEditorialReadBoundaryTest {
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private fun policy(editorial: Boolean, search: Boolean) = ProviderCapabilityPolicy(
        descriptors = listOf(
            BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY),
        ),
        choices = mapOf(
            ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(mb, editorial, 0),
            ),
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(mb, search, 0),
            ),
        ),
    )

    @Test fun detailsRequireEditorialAuthorizationButNotGeneralSearch() {
        val gate = ProviderPolicyGate(policy(editorial = true, search = false))
        assertNotNull(canonicalMusicBrainzDetailLease(gate))
        gate.replace(policy(editorial = false, search = true))
        assertNull(canonicalMusicBrainzDetailLease(gate))
        assertNull(canonicalMusicBrainzDetailLease(null))
    }

    @Test fun revokingEditorialCancelsTheActivePageButSearchChangesDoNot() {
        val gate = ProviderPolicyGate(policy(editorial = true, search = true))
        val lease = canonicalMusicBrainzDetailLease(gate)!!
        val job = Job()
        val unregister = lease.cancelJobOnRevoke(job)
        gate.replace(policy(editorial = true, search = false))
        assertTrue(lease.isCurrent())
        assertTrue(job.isActive)
        gate.replace(policy(editorial = false, search = false))
        assertFalse(lease.isCurrent())
        assertTrue(job.isCancelled)
        unregister()
    }
}

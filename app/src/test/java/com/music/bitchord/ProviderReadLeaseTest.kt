package com.music.bitchord

import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadLease
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderReadLeaseTest {
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val apple = BuiltInProviderKeys.ITUNES
    private val both = listOf(
        ProviderCapability.EDITORIAL_DISCOGRAPHY to mb,
        ProviderCapability.EDITORIAL_DISCOGRAPHY to apple,
    )
    private fun policy(mbEnabled: Boolean = true, appleEnabled: Boolean = true,
        appleFirst: Boolean = false,
    ) = ProviderCapabilityPolicy(
        listOf(BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY),
            BuiltInProviderDescriptors.itunes(ProviderReadiness.READY)),
        mapOf(ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
            ProviderChoice(mb, mbEnabled, if (appleFirst) 1 else 0),
            ProviderChoice(apple, appleEnabled, if (appleFirst) 0 else 1),
        )),
    )

    @Test fun noCompositeReadIsAllowedWhenEitherEvidenceSourceIsDisabled() {
        assertNull(ProviderReadLease.acquire(ProviderPolicyGate(policy(mbEnabled = false)), both))
        assertNull(ProviderReadLease.acquire(ProviderPolicyGate(policy(appleEnabled = false)), both))
        assertNull(ProviderReadLease.acquire(null, both))
        assertNull(ProviderReadLease.acquire(ProviderPolicyGate(policy()), emptyList()))
    }

    @Test fun revokingEitherEvidenceSourceCancelsTheJob() {
        for (revokeMb in listOf(true, false)) {
            val gate = ProviderPolicyGate(policy())
            val lease = ProviderReadLease.acquire(gate, both)!!
            val job = Job()
            val unregister = lease.cancelJobOnRevoke(job)
            gate.replace(policy(mbEnabled = !revokeMb, appleEnabled = revokeMb))
            assertFalse(lease.isCurrent())
            assertTrue(job.isCancelled)
            unregister()
        }
    }

    @Test fun reorderingEligibleSourcesPreservesTheirTickets() {
        val gate = ProviderPolicyGate(policy())
        val lease = ProviderReadLease.acquire(gate, both)!!
        val job = Job()
        val unregister = lease.cancelJobOnRevoke(job)
        gate.replace(policy(appleFirst = true))
        assertTrue(lease.isCurrent())
        assertTrue(job.isActive)
        unregister()
        job.cancel()
    }
}

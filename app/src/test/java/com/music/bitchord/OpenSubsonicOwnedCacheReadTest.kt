package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicOwnedCacheRead
import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicCapabilityKey
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicOwnedCacheReadTest {
    private val accountOne = openSubsonicCapabilityKey("server-one")
    private val accountTwo = openSubsonicCapabilityKey("server-two")
    private fun policy(one: Boolean = true, two: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(accountOne, accountTwo).map {
                ProviderDescriptor(
                    it, setOf(ProviderCapability.PERSONAL_LIBRARY),
                    readiness = ProviderReadiness.READY,
                )
            },
            choices = mapOf(ProviderCapability.PERSONAL_LIBRARY to listOf(
                ProviderChoice(accountOne, one, 0),
                ProviderChoice(accountTwo, two, 1),
            )),
        )

    @Test fun optOutOfOneServerNeverBorrowsPermissionFromItsSibling() = runBlocking {
        val gate = ProviderPolicyGate(policy(one = false))
        var queried = false
        val blocked = openSubsonicOwnedCacheRead(
            accountId = "server-one", unavailable = emptyList<String>(),
            gateProvider = { gate }, realmIsCurrent = { true },
        ) {
            queried = true
            listOf("private-one")
        }
        assertFalse(queried)
        assertTrue(blocked.isEmpty())
        val allowed = openSubsonicOwnedCacheRead(
            accountId = "server-two", unavailable = emptyList<String>(),
            gateProvider = { gate }, realmIsCurrent = { true },
        ) { listOf("private-two") }
        assertEquals(listOf("private-two"), allowed)
    }

    @Test fun revocationCancelsRoomReadRatherThanPublishingItsOldSnapshot() = runBlocking {
        val gate = ProviderPolicyGate(policy())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val pending = async {
            openSubsonicOwnedCacheRead(
                accountId = "server-one", unavailable = emptyList<String>(),
                gateProvider = { gate }, realmIsCurrent = { true },
            ) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
        }
        withTimeout(2_000) { entered.await() }
        gate.replace(policy(one = false))
        withTimeout(2_000) { cancelled.await() }
        assertTrue(withTimeout(2_000) { pending.await() }.isEmpty())
    }

    @Test fun serverRealmRotationDuringRoomReadInvalidatesResult() = runBlocking {
        val gate = ProviderPolicyGate(policy())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sameRealm = true
        val pending = async {
            openSubsonicOwnedCacheRead(
                accountId = "server-one", unavailable = emptyList<String>(),
                gateProvider = { gate }, realmIsCurrent = { sameRealm },
            ) {
                entered.complete(Unit)
                release.await()
                listOf("stale-server-data")
            }
        }
        withTimeout(2_000) { entered.await() }
        sameRealm = false
        release.complete(Unit)
        assertTrue(withTimeout(2_000) { pending.await() }.isEmpty())
    }

    @Test fun missingAccountOrGateFailsClosedWithoutRoomAccess() = runBlocking {
        val gate = ProviderPolicyGate(policy())
        var calls = 0
        for ((realm, policyGate) in listOf(
            false to gate,
            true to null,
        )) {
            val rows = openSubsonicOwnedCacheRead(
                accountId = "server-one", unavailable = emptyList<String>(),
                gateProvider = { policyGate }, realmIsCurrent = { realm },
            ) {
                calls++
                listOf("should-not-appear")
            }
            assertTrue(rows.isEmpty())
        }
        assertEquals(0, calls)
    }
}

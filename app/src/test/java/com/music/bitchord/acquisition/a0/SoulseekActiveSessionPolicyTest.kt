package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulseekActiveSessionPolicyTest {
    private val soulseek = ProviderKey("soulseek")
    private val other = ProviderKey("other-download")
    private fun policy(soulseekEnabled: Boolean, otherEnabled: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(soulseek, other).map {
                ProviderDescriptor(it, setOf(ProviderCapability.DOWNLOAD), ProviderReadiness.READY)
            },
            choices = mapOf(ProviderCapability.DOWNLOAD to listOf(
                ProviderChoice(soulseek, soulseekEnabled, 0),
                ProviderChoice(other, otherEnabled, 1),
            )),
        )

    @After fun cleanUp() { SoulseekActiveSession.bindDownloadPermission(null) }

    @Test fun changingSavedCredentialsClosesOldTransportBeforeReadinessRefresh() {
        val gate = ProviderPolicyGate(policy(true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val oldSocket = Socket()
        val generation = SoulseekActiveSession.generation()
        assertTrue(SoulseekActiveSession.register(oldSocket, generation))
        var readinessRefreshed = false

        SoulseekActiveSession.onCredentialsChanged(configured = false) { configured ->
            assertFalse(configured)
            assertTrue(oldSocket.isClosed)
            assertFalse(SoulseekActiveSession.isCurrent(generation))
            val lateSocket = Socket()
            assertFalse(SoulseekActiveSession.register(
                lateSocket, SoulseekActiveSession.generation()))
            assertTrue(lateSocket.isClosed)
            readinessRefreshed = true
            true
        }
        assertTrue(readinessRefreshed)
    }

    @Test fun credentialSaveRefreshesReadinessWithoutReusingPriorTicket() {
        val gate = ProviderPolicyGate(policy(true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val former = SoulseekActiveSession.generation()
        var refreshed = false
        SoulseekActiveSession.onCredentialsChanged(configured = true) { configured ->
            assertTrue(configured)
            assertFalse(SoulseekActiveSession.isCurrent(former))
            refreshed = true
            true
        }
        assertTrue(refreshed)
        // A fresh manual-search admission must explicitly bind a new ticket.
        val lateSocket = Socket()
        assertFalse(SoulseekActiveSession.register(
            lateSocket, SoulseekActiveSession.generation()))
        assertTrue(lateSocket.isClosed)
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
    }

    @Test fun manualPilotMustBindDownloadBeforeAnySocketOrSearch() {
        val denied = ProviderPolicyGate(policy(false))
        assertTrue(SoulseekActiveSession.admitManualSession(denied) == null)
        val premature = Socket()
        assertFalse(SoulseekActiveSession.register(
            premature, SoulseekActiveSession.generation()))
        assertTrue(premature.isClosed)
        assertTrue(SoulseekActiveSession.admitManualSession(null) == null)

        val allowed = ProviderPolicyGate(policy(true))
        val session = SoulseekActiveSession.admitManualSession(allowed)
        assertTrue(session != null)
        val socket = Socket()
        assertTrue(SoulseekActiveSession.register(socket, requireNotNull(session)))
        allowed.replace(policy(false))
        assertTrue(socket.isClosed)
        assertFalse(SoulseekActiveSession.isCurrent(session))
        assertTrue(SoulseekActiveSession.admitManualSession(allowed) == null)
    }

    @Test fun revocationClosesSearchListenerAndDirectAndIndirectPeerSockets() {
        val gate = ProviderPolicyGate(policy(true))
        val session = requireNotNull(SoulseekActiveSession.admitManualSession(gate))
        val listener = ServerSocket(0)
        val direct = Socket()
        val indirect = Socket()
        try {
            assertTrue(SoulseekActiveSession.registerListener(listener, session))
            assertTrue(SoulseekActiveSession.register(direct, session))
            assertTrue(SoulseekActiveSession.register(indirect, session))
            gate.replace(policy(false))
            assertTrue(listener.isClosed)
            assertTrue(direct.isClosed)
            assertTrue(indirect.isClosed)
            assertFalse(SoulseekActiveSession.isCurrent(session))
            val lateListener = ServerSocket(0)
            assertFalse(SoulseekActiveSession.registerListener(
                lateListener, SoulseekActiveSession.generation()))
            assertTrue(lateListener.isClosed)
        } finally {
            listener.close()
            direct.close()
            indirect.close()
        }
    }

    @Test fun revokingUnrelatedDownloadProviderDoesNotCloseSoulseekListener() {
        val gate = ProviderPolicyGate(policy(true, true))
        val session = requireNotNull(SoulseekActiveSession.admitManualSession(gate))
        val listener = ServerSocket(0)
        try {
            assertTrue(SoulseekActiveSession.registerListener(listener, session))
            gate.replace(policy(true, false))
            assertFalse(listener.isClosed)
            assertTrue(SoulseekActiveSession.isCurrent(session))
        } finally {
            SoulseekActiveSession.detachListener(listener)
            listener.close()
        }
    }

    @Test fun revokingCanonicalDownloadClosesActiveSoulseekSocketImmediately() {
        val gate = ProviderPolicyGate(policy(true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val socket = Socket()
        val generation = SoulseekActiveSession.generation()
        assertTrue(SoulseekActiveSession.register(socket, generation))
        gate.replace(policy(false))
        assertTrue(socket.isClosed)
        assertFalse(SoulseekActiveSession.isCurrent(generation))
        assertFalse(SoulseekActiveSession.bindDownloadPermission(gate))
    }

    @Test fun revokedCapabilityCannotRegisterNewPeerSocketOrCommitNewFile() {
        val gate = ProviderPolicyGate(policy(true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val former = SoulseekActiveSession.generation()
        gate.replace(policy(false))
        val lateSocket = Socket()
        assertFalse(SoulseekActiveSession.register(
            lateSocket, SoulseekActiveSession.generation()))
        assertTrue(lateSocket.isClosed)
        var published = false
        try {
            SoulseekActiveSession.commitIfCurrent(
                SoulseekActiveSession.generation()) { published = true }
        } catch (_: java.io.IOException) { }
        assertFalse(published)
        assertFalse(SoulseekActiveSession.isCurrent(former))
    }

    @Test fun revokingSiblingDownloaderDoesNotCloseSoulseekSocket() {
        val gate = ProviderPolicyGate(policy(true, true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val socket = Socket()
        val generation = SoulseekActiveSession.generation()
        assertTrue(SoulseekActiveSession.register(socket, generation))
        gate.replace(policy(true, false))
        assertFalse(socket.isClosed)
        assertTrue(SoulseekActiveSession.isCurrent(generation))
    }
}

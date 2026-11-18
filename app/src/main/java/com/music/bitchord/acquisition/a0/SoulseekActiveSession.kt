package com.music.bitchord.acquisition.a0

import java.net.Socket
import java.net.ServerSocket
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRequestTicket
import com.music.bitchord.data.providers.ProviderRuntime

/**
 * Process-local P2P socket generation. Saving or forgetting an account revokes
 * the previous authenticated transport. Never persist sockets or password data.
 */
internal object SoulseekActiveSession {
    private var epoch = 0L
    private val sockets = mutableSetOf<Socket>()
    private val searchListeners = mutableSetOf<ServerSocket>()
    private val activeTransfers = mutableSetOf<A0ObservedCandidateHandoff>()

    private var currentDownloadGate: ProviderPolicyGate? = null
    private var currentDownloadTicket: ProviderRequestTicket? = null
    private var removeDownloadListener: (() -> Unit)? = null

    /** Bind an authenticated P2P session to the exact canonical DOWNLOAD
     * capability; removing Soulseek consent physically closes tracked sockets
     * and cancels peer transfers, without touching sibling download providers.
     */
    @Synchronized fun bindDownloadPermission(gate: ProviderPolicyGate?): Boolean {
        val ticket = gate?.open(ProviderCapability.DOWNLOAD, ProviderKey("soulseek"))
        if (ticket == null) {
            removeDownloadListener?.invoke()
            removeDownloadListener = null
            currentDownloadGate = null
            currentDownloadTicket = null
            revoke()
            return false
        }
        if (currentDownloadGate === gate && currentDownloadTicket == ticket &&
            gate.accept(ticket)) return true

        removeDownloadListener?.invoke()
        if (currentDownloadTicket != null) revoke()
        currentDownloadGate = gate
        currentDownloadTicket = ticket
        val unsubscribe = gate.onRevoke(ticket) {
            synchronized(this) {
                if (currentDownloadGate === gate && currentDownloadTicket == ticket) {
                    currentDownloadGate = null
                    currentDownloadTicket = null
                    removeDownloadListener = null
                    revoke()
                }
            }
        }
        if (!gate.accept(ticket)) {
            unsubscribe()
            currentDownloadGate = null
            currentDownloadTicket = null
            removeDownloadListener = null
            revoke()
            return false
        }
        removeDownloadListener = unsubscribe
        return true
    }

    private fun downloadAuthorized(): Boolean {
        val gate = currentDownloadGate ?: return false
        val ticket = currentDownloadTicket ?: return false
        return gate.accept(ticket)
    }

    /** Credential lifecycle must invalidate the bound P2P ticket before
     * refreshing readiness. Revoking sockets alone leaves the old ticket
     * reusable by a late transport registration.
     */
    fun onCredentialsChanged(
        configured: Boolean,
        refreshReadiness: (Boolean) -> Boolean? = {
            ProviderRuntime.refreshSoulseekAccountReadiness(it)
        },
    ): Boolean? {
        bindDownloadPermission(null)
        return refreshReadiness(configured)
    }

    /**
     * Mandatory preflight for a manual A0 session. Captures a generation only
     * after an exact DOWNLOAD × soulseek ticket has been admitted. A direct
     * pilot launch must never bypass the normal download routing decision.
     */
    @Synchronized fun admitManualSession(gate: ProviderPolicyGate?): Long? =
        if (bindDownloadPermission(gate)) epoch else null

    @Synchronized fun generation(): Long = epoch

    @Synchronized fun isCurrent(expected: Long): Boolean = epoch == expected

    /**
     * Hold the generation gate only around A3's durable AVAILABLE commit.
     * A2 copying stays outside the lock and remains cancellable on logout.
     */
    @Synchronized fun commitIfCurrent(expected: Long, commit: () -> Unit) {
        if (epoch != expected || !downloadAuthorized())
            throw java.io.IOException("Soulseek account or download capability revoked")
        commit()
    }

    @Synchronized fun registerTransfer(
        transfer: A0ObservedCandidateHandoff, expected: Long
    ): Boolean {
        if (epoch != expected || !downloadAuthorized()) {
            transfer.cancel()
            return false
        }
        activeTransfers.add(transfer)
        return true
    }

    @Synchronized fun detachTransfer(transfer: A0ObservedCandidateHandoff) {
        activeTransfers.remove(transfer)
    }

    @Synchronized fun register(socket: Socket, expectedGeneration: Long): Boolean {
        if (expectedGeneration != epoch || !downloadAuthorized()) {
            runCatching { socket.close() }
            return false
        }
        sockets.add(socket)
        return true
    }

    @Synchronized fun detach(socket: Socket) {
        sockets.remove(socket)
    }

    /** A ServerSocket also holds a live P2P entrypoint during search; unlike
     * accepted peers it cannot be tracked through register(Socket).
     */
    @Synchronized fun registerListener(
        listener: ServerSocket, expectedGeneration: Long,
    ): Boolean {
        if (expectedGeneration != epoch || !downloadAuthorized()) {
            runCatching { listener.close() }
            return false
        }
        searchListeners.add(listener)
        return true
    }

    @Synchronized fun detachListener(listener: ServerSocket) {
        searchListeners.remove(listener)
    }

    @Synchronized fun revoke() {
        epoch++
        // P/F transport can stay alive after the authenticated server socket
        // is no longer in use. Its own cancel closes both peer channels.
        val transfers = activeTransfers.toList()
        val listeners = searchListeners.toList()
        val connections = sockets.toList()
        activeTransfers.clear()
        searchListeners.clear()
        sockets.clear()
        // Cancel outside the mutable-set iteration: closing a transfer may
        // synchronously detach itself from the active session. Close search
        // listeners too, so a pending accept cannot outlive the opt-out.
        transfers.forEach { runCatching { it.cancel() } }
        listeners.forEach { runCatching { it.close() } }
        connections.forEach { runCatching { it.close() } }
    }
}

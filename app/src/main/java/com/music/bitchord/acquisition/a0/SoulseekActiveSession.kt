package com.music.bitchord.acquisition.a0

import java.net.Socket

/**
 * Process-local P2P socket generation. Saving or forgetting an account revokes
 * the previous authenticated transport. Never persist sockets or password data.
 */
internal object SoulseekActiveSession {
    private var epoch = 0L
    private val sockets = mutableSetOf<Socket>()
    private val activeTransfers = mutableSetOf<A0ObservedCandidateHandoff>()
    private val activeAutoTransfers=mutableSetOf<A0ServerAssociatedTransfer>()

    @Synchronized fun generation(): Long = epoch

    @Synchronized fun isCurrent(expected: Long): Boolean = epoch == expected

    /**
     * Hold the generation gate only around A3's durable AVAILABLE commit.
     * A2 copying stays outside the lock and remains cancellable on logout.
     */
    @Synchronized fun commitIfCurrent(expected: Long, commit: () -> Unit) {
        if (epoch != expected) throw java.io.IOException("Soulseek account revoked")
        commit()
    }

    @Synchronized fun registerTransfer(
        transfer: A0ObservedCandidateHandoff, expected: Long
    ): Boolean {
        if (epoch != expected) {
            transfer.cancel()
            return false
        }
        activeTransfers.add(transfer)
        return true
    }

    @Synchronized fun detachTransfer(transfer: A0ObservedCandidateHandoff) {
        activeTransfers.remove(transfer)
    }

    @Synchronized fun registerAutoTransfer(
        transfer:A0ServerAssociatedTransfer, expectedGeneration:Long,
    ):Boolean {
        if(expectedGeneration!=epoch) {
            transfer.cancel()
            return false
        }
        activeAutoTransfers.add(transfer)
        return true
    }

    @Synchronized fun detachAutoTransfer(transfer:A0ServerAssociatedTransfer) {
        activeAutoTransfers.remove(transfer)
    }

    @Synchronized fun register(socket: Socket, expectedGeneration: Long): Boolean {
        if (expectedGeneration != epoch) {
            runCatching { socket.close() }
            return false
        }
        sockets.add(socket)
        return true
    }

    @Synchronized fun detach(socket: Socket) {
        sockets.remove(socket)
    }

    @Synchronized fun revoke() {
        epoch++
        // P/F transport can stay alive after the authenticated server socket
        // is no longer in use. Its own cancel closes both peer channels.
        activeTransfers.forEach { runCatching { it.cancel() } }
        activeTransfers.clear()
        activeAutoTransfers.forEach {runCatching {it.cancel()}}
        activeAutoTransfers.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}

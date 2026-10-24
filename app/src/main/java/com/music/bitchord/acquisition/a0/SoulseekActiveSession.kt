package com.music.bitchord.acquisition.a0

import java.net.Socket

/**
 * Process-local P2P socket generation. Saving or forgetting an account revokes
 * the previous authenticated transport. Never persist sockets or password data.
 */
internal object SoulseekActiveSession {
    private var epoch = 0L
    private val sockets = mutableSetOf<Socket>()

    @Synchronized fun generation(): Long = epoch

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
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}

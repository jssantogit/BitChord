package com.music.bitchord.acquisition.a0

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dispatches already accepted inbound Soulseek P sockets concurrently.
 * Slow peers never block ServerSocket.accept() or the authenticated server
 * reader. All sockets are tracked for deterministic teardown before P/F.
 */
internal class A0DirectPeerSearchCollector(
    private val process: (Socket) -> Unit
) {
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    // Match the indirect P channel's bounded 12 workers + 128 queued offers.
    // Inbound direct peers must not be starved by the former 8 + 96 budget.
    private val workers = A0PeerSearchWorkers(queueCapacity = 128, parallelism = 12)

    /** Owns the socket even when the queue is full or the search is closing. */
    fun offer(socket: Socket): Boolean {
        if (closed.get()) {
            runCatching { socket.close() }
            return false
        }
        sockets.add(socket)
        val accepted = workers.submit {
            try {
                if (!closed.get()) process(socket)
            } finally {
                sockets.remove(socket)
                runCatching { socket.close() }
            }
        }
        if (!accepted) {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
        return accepted
    }

    /** Interrupt any in-flight reads before joining the P worker pool. */
    fun closeSockets() {
        closed.set(true)
        sockets.toList().forEach { runCatching { it.close() } }
    }

    fun stop(timeoutMillis: Long): Boolean {
        closeSockets()
        return workers.stop(timeoutMillis)
    }

    fun cancel() {
        closeSockets()
        workers.cancel()
    }
}

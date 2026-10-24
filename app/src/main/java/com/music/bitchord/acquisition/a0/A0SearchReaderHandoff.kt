package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Relinquish the Soulseek server socket after the one-shot search.
 *
 * Interrupting a thread blocked in socket I/O may invalidate the socket
 * across Android runtimes. Never call shutdownNow on readers before handing
 * that SAME authenticated socket to P/F. First close ONLY search listener
 * and peer-message sockets, then join readers. If not fully terminated,
 * fail closed and let the enclosing session finally close the server socket.
 */
internal object A0SearchReaderHandoff {
    fun stop(
        readers:ExecutorService,
        closeSearchSockets:()->Unit,
        timeoutMillis:Long
    ) {
        require(timeoutMillis in 100..8000)
        closeSearchSockets()
        readers.shutdown()
        val stopped=try {
            readers.awaitTermination(timeoutMillis,TimeUnit.MILLISECONDS)
        }catch(e:InterruptedException){
            Thread.currentThread().interrupt()
            throw IOException("A0 search handoff interrupted",e)
        }
        if(!stopped)throw IOException("A0 search readers still own socket")
    }
}

/**
 * One-shot completion is monotonic: consuming the inbox entry must NOT reopen
 * an authenticated server reader or start another outbound peer connection.
 *
 * Mark complete before publishing to the inbox so a racing poll cannot drain
 * the queue and let the reader resume searching. Never interrupt socket I/O.
 */
internal class A0SearchReaderGate {
    private val completed=java.util.concurrent.atomic.AtomicBoolean(false)

    fun isOpen():Boolean=!completed.get()

    fun publish(inbox:java.util.concurrent.BlockingQueue<String>,summary:String):Boolean {
        if(!completed.compareAndSet(false,true))return false
        return inbox.offer(summary)
    }

    fun stop() {
        completed.set(true)
    }
}

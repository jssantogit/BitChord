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

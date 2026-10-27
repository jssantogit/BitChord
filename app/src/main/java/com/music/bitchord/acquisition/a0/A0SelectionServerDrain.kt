package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One exclusive reader owns authenticated server input while the one-file
 * selection dialog is visible. Incoming search offers have no destination
 * after the first validated result; consume ONLY complete framed messages.
 *
 * The owner stops reading at a frame boundary BEFORE sending GetPeerAddress.
 * This prevents thousands of queued indirect offers masking the next reply.
 * No usernames, packet payloads, IPs or paths are stored or logged.
 */
internal object A0SelectionServerDrain {
    data class Selection(val index:Int?,val discardedFrames:Int)

    private const val MAX_TOTAL_BYTES=64L*1024*1024
    private const val MAX_FRAMES=65_536

    fun awaitChoice(
        server:Socket,
        choices:BlockingQueue<Int>,
        timeoutMillis:Long,
        isCancelled:()->Boolean,
        onFrameDrained:()->Unit={}
    ):Selection {
        require(timeoutMillis in 1..35_000)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val originalTimeout=server.soTimeout
        val progress=A0ProgressiveServerFrameReader(server.getInputStream())
        var frames=0
        var bytes=0L
        var pendingChoice:Int?=null
        try {
            while(!isCancelled()) {
                if(pendingChoice==null) pendingChoice=choices.poll()
                // Selection may arrive while we are partway through a TCP
                // frame. Preserve the choice and finish framing first.
                if(pendingChoice!=null && progress.atFrameBoundary)
                    return Selection(pendingChoice,frames)
                val timeLeft=deadline-System.nanoTime()
                if(timeLeft<=0L) {
                    if(!progress.atFrameBoundary)
                        throw IOException("A0 incomplete server frame during selection")
                    break
                }
                // Use the existing 750ms frame timeout to avoid false partial
                // failures on slow peers; no other thread reads this socket.
                server.soTimeout=minOf(
                    750L,TimeUnit.NANOSECONDS.toMillis(timeLeft).coerceAtLeast(1L)
                ).toInt()
                try {
                    val frame=progress.readRawFrame()
                    val code=(frame[4].toInt() and 255) or
                        ((frame[5].toInt() and 255) shl 8) or
                        ((frame[6].toInt() and 255) shl 16) or
                        (frame[7].toInt() shl 24)
                    if(code==41)throw A0PeerRendezvousWire.SessionReplaced()
                    frames++
                    bytes+=frame.size
                    if(frames>MAX_FRAMES || bytes>MAX_TOTAL_BYTES) {
                        throw IOException("A0 manual selection backlog safety budget exhausted")
                    }
                    onFrameDrained()
                }catch(_:SocketTimeoutException) {
                    // Resume the same frame on the next bounded read.
                    // Deadline and cancellation still fail closed if partial.
                }
            }
            if(!progress.atFrameBoundary)
                throw IOException("A0 incomplete server frame during selection")
            return Selection(null,frames)
        } finally {
            // The caller remains the authenticated socket owner and starts
            // P/F only after this function has returned.
            if(!server.isClosed)server.soTimeout=originalTimeout
        }
    }

}

package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.io.InputStream
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

    private const val MAX_FRAME_BYTES=1_048_576
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
        val progress=A0FrameReadProgress(server.getInputStream())
        var frames=0
        var bytes=0L
        try {
            while(!isCancelled()) {
                val choice=choices.poll()
                if(choice!=null)return Selection(choice,frames)
                val timeLeft=deadline-System.nanoTime()
                if(timeLeft<=0L)break
                // Use the existing 750ms frame timeout to avoid false partial
                // failures on slow peers; no other thread reads this socket.
                server.soTimeout=minOf(
                    125L,TimeUnit.NANOSECONDS.toMillis(timeLeft).coerceAtLeast(1L)
                ).toInt()
                progress.nextFrame()
                try {
                    val length=read32(progress)
                    if(length !in 4..MAX_FRAME_BYTES) {
                        throw IOException("A0 invalid server frame during selection")
                    }
                    val code=read32(progress)
                    if(code==41)throw A0PeerRendezvousWire.SessionReplaced()
                    skipExactly(progress,length-4)
                    frames++
                    bytes+=length.toLong()+4
                    if(frames>MAX_FRAMES || bytes>MAX_TOTAL_BYTES) {
                        throw IOException("A0 manual selection backlog safety budget exhausted")
                    }
                    progress.nextFrame()
                    onFrameDrained()
                }catch(error:SocketTimeoutException) {
                    // A timeout in the middle of a server frame would leave
                    // P/F parsing misaligned. Do not reuse that socket.
                    if(!progress.atFrameBoundary) {
                        throw IOException("A0 incomplete server frame during selection",error)
                    }
                }
            }
            return Selection(null,frames)
        } finally {
            // The caller remains the authenticated socket owner and starts
            // P/F only after this function has returned.
            if(!server.isClosed)server.soTimeout=originalTimeout
        }
    }

    private fun read32(input:InputStream):Int {
        val bytes=ByteArray(4)
        readExactly(input,bytes,4)
        return (bytes[0].toInt() and 255) or
            ((bytes[1].toInt() and 255) shl 8) or
            ((bytes[2].toInt() and 255) shl 16) or
            ((bytes[3].toInt() and 255) shl 24)
    }

    private fun skipExactly(input:InputStream,count:Int) {
        val buffer=ByteArray(4096)
        var left=count
        while(left>0) {
            val n=input.read(buffer,0,minOf(left,buffer.size))
            if(n<1)throw IOException("A0 incomplete server frame during selection")
            left-=n
        }
    }

    private fun readExactly(input:InputStream,bytes:ByteArray,count:Int) {
        var read=0
        while(read<count) {
            val n=input.read(bytes,read,count-read)
            if(n<1)throw IOException("A0 incomplete server frame during selection")
            read+=n
        }
    }
}

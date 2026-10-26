package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * Resumable, bounded reader for the authenticated Soulseek server.
 *
 * SocketTimeoutException does NOT discard partially received TCP frames.
 * Only completed frames are passed to the existing fail-closed parser;
 * an unfinished frame at search teardown is unsafe to reuse for P/F.
 *
 * This class is single-reader only. It never persists or logs wire bytes.
 */
internal class A0ProgressiveServerFrameReader(
    private val input: InputStream
) {
    private val lengthField=ByteArray(4)
    private var lengthReceived=0
    private var body:ByteArray?=null
    private var bodyReceived=0

    val atFrameBoundary:Boolean get()=lengthReceived==0 && body==null

    fun readEvent(onIgnoredCode:(Int)->Unit = {}):A0IndirectSearchWire.Offer? =
        A0IndirectSearchWire.readServerEvent(
            ByteArrayInputStream(readRawFrame()),onIgnoredCode)

    /**
     * At shutdown, complete only an already-started frame. Bounded by the
     * socket's own SO_TIMEOUT and a small retry count. A partial stream never
     * crosses into P/F; no new frame is consumed at a clean boundary.
     */
    fun finishPendingFrame(maxReadTimeouts:Int=3):Boolean {
        require(maxReadTimeouts in 1..4)
        if(atFrameBoundary)return true
        var timeouts=0
        while(timeouts<maxReadTimeouts) {
            try {
                val completed=readRawFrame()
                // A session-replacement frame cannot be ignored just because
                // it arrived while finishing search.
                val code=(completed[4].toInt() and 255) or
                    ((completed[5].toInt() and 255) shl 8) or
                    ((completed[6].toInt() and 255) shl 16) or
                    (completed[7].toInt() shl 24)
                return code!=41 && atFrameBoundary
            }catch(_:SocketTimeoutException) {
                timeouts++
            }catch(_:IOException) {
                return false
            }
        }
        return false
    }

    /** Complete server message, including its 4-byte length. */
    fun readRawFrame():ByteArray {
        if(body==null) {
            while(lengthReceived<4) {
                val n=input.read(lengthField,lengthReceived,4-lengthReceived)
                if(n<=0)throw IOException("Incomplete authenticated server frame length")
                lengthReceived+=n
            }
            val size=(lengthField[0].toInt() and 255) or
                ((lengthField[1].toInt() and 255) shl 8) or
                ((lengthField[2].toInt() and 255) shl 16) or
                ((lengthField[3].toInt() and 255) shl 24)
            // Same byte cap as A0IndirectSearchWire. Never allocate from an
            // untrusted 32-bit length without validating it.
            if(size !in 4..1_048_576) throw IOException("Unsupported server frame length")
            body=ByteArray(size)
        }
        val target=checkNotNull(body)
        while(bodyReceived<target.size) {
            val n=input.read(target,bodyReceived,target.size-bodyReceived)
            if(n<=0)throw IOException("Incomplete authenticated server frame body")
            bodyReceived+=n
        }
        val complete=lengthField+target
        // A complete but malformed offer can be rejected without poisoning the
        // following frame. The socket stays aligned after the parser throws.
        lengthReceived=0
        bodyReceived=0
        body=null
        return complete
    }

}

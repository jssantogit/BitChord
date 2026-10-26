package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

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

    fun readEvent(onIgnoredCode:(Int)->Unit = {}):A0IndirectSearchWire.Offer? {
        if(body==null) {
            lengthReceived+=readInto(lengthField,lengthReceived,4-lengthReceived)
            if(lengthReceived<4) throw IOException("Partial server frame length")
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
        bodyReceived+=readInto(target,bodyReceived,target.size-bodyReceived)
        if(bodyReceived!=target.size)throw IOException("Partial server frame")
        val complete=lengthField+target
        // A complete but malformed offer can be rejected without poisoning the
        // following frame. The socket stays aligned after the parser throws.
        lengthReceived=0
        bodyReceived=0
        body=null
        return A0IndirectSearchWire.readServerEvent(ByteArrayInputStream(complete),onIgnoredCode)
    }

    private fun readInto(destination:ByteArray,offset:Int,length:Int):Int {
        var received=0
        while(received<length) {
            // SocketTimeoutException propagates; all previously read bytes are
            // kept in the fields above, with their exact byte offsets.
            val count=input.read(destination,offset+received,length-received)
            if(count<=0)throw IOException("Incomplete authenticated server frame")
            received+=count
        }
        return received
    }
}

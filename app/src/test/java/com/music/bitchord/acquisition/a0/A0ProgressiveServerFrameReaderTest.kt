package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

class A0ProgressiveServerFrameReaderTest {
    @Test fun readTimeoutDuringPartialLengthOrPayloadKeepsAlignment() {
        val stream = InterruptedReads(
            frame(64, byteArrayOf(1, 2, 3)) + offer("peer", 4820, 1234),
            timeoutAfter = setOf(2, 11, 22)
        )
        val reader = A0ProgressiveServerFrameReader(stream)
        val ignored = mutableListOf<Int>()
        var event: A0IndirectSearchWire.Offer? = null
        var timeouts = 0
        // The only result is a P offer. Interrupted frames are retried
        // instead of losing subsequent valid peer connections.
        for (attempt in 0 until 30) {
            if (event != null) break
            try {
                val next = reader.readEvent(ignored::add)
                if (next != null) event = next
            } catch (_: SocketTimeoutException) { timeouts++ }
        }
        assertEquals(3, timeouts)
        assertEquals(listOf(64), ignored)
        assertEquals("peer", event?.username)
        assertEquals(4820, event?.port)
        assertEquals(1234, event?.token)
        assertTrue(reader.atFrameBoundary)
    }

    @Test fun unfinishedSearchFrameCanDrainSafelyBeforeTransferHandoff() {
        val bytes=frame(64,byteArrayOf(1,2,3)) + offer("peer",4820,1234)
        val reader=A0ProgressiveServerFrameReader(
            InterruptedReads(bytes,timeoutAfter=setOf(2)))
        try {
            reader.readEvent()
            fail("Injected timeout must interrupt first frame")
        }catch(_:SocketTimeoutException){}
        assertFalse(reader.atFrameBoundary)
        assertTrue(reader.finishPendingFrame(maxReadTimeouts=3))
        assertTrue(reader.atFrameBoundary)
        val offer=reader.readEvent()
        assertEquals("peer",offer?.username)
    }

    @Test fun incompleteFrameCanNeverBeMistakenForSafePfhandoff() {
        val raw=offer("peer",4820,1234).copyOf(9)
        val reader=A0ProgressiveServerFrameReader(ByteArrayInputStream(raw))
        try {
            reader.readEvent()
            fail("partial frame must fail")
        } catch (_: IOException) { }
        assertFalse(reader.atFrameBoundary)
    }

    private fun offer(user:String, port:Int, token:Int):ByteArray =
        frame(18, str(user)+str("P")+byteArrayOf(4,4,8,8)+i32(port)+i32(token))
    private fun frame(code:Int, body:ByteArray):ByteArray=i32(4+body.size)+i32(code)+body
    private fun i32(n:Int):ByteArray=ByteArray(4){(n ushr (it*8)).toByte()}
    private fun str(s:String):ByteArray=s.toByteArray(Charsets.UTF_8).let{i32(it.size)+it}

    private class InterruptedReads(
        private val bytes:ByteArray,
        private val timeoutAfter:Set<Int>
    ):InputStream() {
        private var at=0
        private val interrupted=hashSetOf<Int>()
        override fun read():Int {
            if(at in timeoutAfter && interrupted.add(at))throw SocketTimeoutException()
            return if(at==bytes.size)-1 else bytes[at++].toInt() and 0xff
        }
        override fun read(target:ByteArray,offset:Int,length:Int):Int {
            require(length>0)
            if(at in timeoutAfter && interrupted.add(at))throw SocketTimeoutException()
            if(at==bytes.size)return -1
            val next=timeoutAfter.filter{it>at && it !in interrupted}.minOrNull() ?: bytes.size
            val take=minOf(length,bytes.size-at,next-at)
            bytes.copyInto(target,offset,at,at+take)
            at+=take
            return take
        }
    }
}

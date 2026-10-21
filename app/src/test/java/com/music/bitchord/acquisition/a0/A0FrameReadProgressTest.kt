package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

/** A socket timeout after any consumed server frame byte must abort handoff. */
class A0FrameReadProgressTest {
    @Test fun timeoutWithoutAnyFrameBytesIsSafeToRetryFromFrameBoundary() {
        val stream=A0FrameReadProgress(ByteArrayInputStream(byteArrayOf(4,3,2,1)))
        assertTrue(stream.atFrameBoundary)
        try {throw SocketTimeoutException("no byte read yet")}
        catch(_:SocketTimeoutException) {assertTrue(stream.atFrameBoundary)}
        assertEquals(4,stream.read())
        assertFalse(stream.atFrameBoundary)
        stream.nextFrame()
        assertTrue(stream.atFrameBoundary)
    }

    @Test fun timeoutAfterPartialHeaderIsNotSafeToRetry() {
        val source=object:InputStream() {
            var reads=0
            override fun read():Int {
                if(reads++<2)return 0x7f
                throw SocketTimeoutException("partial header stalled")
            }
        }
        val stream=A0FrameReadProgress(source)
        assertEquals(0x7f,stream.read())
        assertEquals(0x7f,stream.read())
        try {stream.read();fail("expected socket timeout")}
        catch(_:SocketTimeoutException) {assertFalse(stream.atFrameBoundary)}
        assertEquals(2,stream.bytesConsumedInFrame)
    }

    @Test fun wrappedBulkReadsAreCountedAndNextFrameResetsOnlyAfterSuccessfulParse() {
        val source=ByteArrayInputStream(byteArrayOf(4,0,0,0,1,2,3,4))
        val stream=A0FrameReadProgress(source)
        val b=ByteArray(4)
        assertEquals(4,stream.read(b))
        assertEquals(4,stream.bytesConsumedInFrame)
        assertFalse(stream.atFrameBoundary)
        stream.nextFrame()
        assertEquals(0,stream.bytesConsumedInFrame)
        assertEquals(4,stream.read(b,0,4))
        assertFalse(stream.atFrameBoundary)
        assertEquals(4,stream.bytesConsumedInFrame)
    }
}

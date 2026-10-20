package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED tests: precise login receive stage without retaining or logging wire bytes. */
class A0LoginReadProgressTest {

    @Test fun distinguishesNoDataPartialFrameHeaderAndPayload() {
        var firstByteNotices = 0
        val source = A0LoginReadProgress(
            ByteArrayInputStream(byteArrayOf(9, 0, 0, 0, 1, 2, 3)),
            onFirstByte = { firstByteNotices++ },
        )
        assertEquals(A0LoginReadProgress.Phase.NO_BYTES, source.phase)
        assertEquals(9, source.read())
        assertEquals(A0LoginReadProgress.Phase.PARTIAL_HEADER, source.phase)
        val buffer = ByteArray(3)
        assertEquals(3, source.read(buffer, 0, buffer.size))
        assertEquals(A0LoginReadProgress.Phase.HEADER_ONLY, source.phase)
        assertEquals(3, source.read(ByteArray(16)))
        assertEquals(A0LoginReadProgress.Phase.PAYLOAD_SEEN, source.phase)
        assertEquals(-1, source.read())
        assertEquals(1, firstByteNotices)
        assertFalse(source.toString().contains("1, 2, 3"))
    }

    @Test fun zeroByteTimeoutRemainsNoBytesAndNeverRetriesAutomatically() {
        var reads = 0
        val failing = object : InputStream() {
            override fun read(): Int {
                reads++
                throw SocketTimeoutException("secret username peer ip token not safe for logs")
            }
        }
        val source = A0LoginReadProgress(failing)
        try {
            source.read()
            throw AssertionError("Expected timeout")
        } catch (_: SocketTimeoutException) {
            assertEquals(A0LoginReadProgress.Phase.NO_BYTES, source.phase)
            assertEquals(1, reads)
            assertFalse(source.toString().contains("secret"))
        }
    }

    @Test fun partialHeaderFailureKeepsCorrectPhaseAndNeverLeaksServerPayload() {
        val source = A0LoginReadProgress(ByteArrayInputStream(byteArrayOf(20, 0)))
        assertEquals(2, source.read(ByteArray(8)))
        assertEquals(-1, source.read())
        assertEquals(A0LoginReadProgress.Phase.PARTIAL_HEADER, source.phase)
    }
}

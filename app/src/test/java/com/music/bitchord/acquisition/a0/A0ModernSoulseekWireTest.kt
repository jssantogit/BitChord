package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Modern Soulseek flow per Nicotine+ protocol documentation (2026):
 * QueueUpload (P code 43), peer-initiated F, downloader sends raw uint64 offset.
 * This is a wire-only fixture; it does not connect to Soulseek.
 */
class A0ModernSoulseekWireTest {
    @Test
    fun queueUploadContainsOnlyPeerMessageCodeAndFilename() {
        val bytes = A0SoulseekPeerWireProbe.queueUploadRequest("Owned\\bitchord-a0.wav")
        val wire = ByteArrayInputStream(bytes)
        val frameSize = read32(wire)
        assertEquals(bytes.size - 4, frameSize)
        assertEquals(43, read32(wire))
        val filenameSize = read32(wire)
        assertEquals("Owned\\bitchord-a0.wav".toByteArray(Charsets.UTF_8).size, filenameSize)
        val filename = ByteArray(filenameSize)
        assertEquals(filenameSize, wire.read(filename))
        assertEquals("Owned\\bitchord-a0.wav", String(filename, Charsets.UTF_8))
        assertEquals(-1, wire.read()) // No fake token, direction or size follows.
    }

    @Test
    fun firstDownloadOffsetIsEightUnframedZeroBytes() {
        val bytes = A0SoulseekPeerWireProbe.initialDownloadOffset()
        assertEquals(8, bytes.size)
        assertArrayEquals(ByteArray(8), bytes)
        assertEquals(0L, read64(ByteArrayInputStream(bytes)))
    }

    @Test
    fun invalidRemoteFilenameCannotBeQueued() {
        for (name in listOf("", "\u0000unsafe", "x".repeat(2048))) {
            try {
                A0SoulseekPeerWireProbe.queueUploadRequest(name)
                fail("Malformed remote filename must be rejected before network I/O")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    private fun read32(input: ByteArrayInputStream): Int {
        val b = ByteArray(4)
        assertEquals(4, input.read(b))
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun read64(input: ByteArrayInputStream): Long {
        val b = ByteArray(8)
        assertEquals(8, input.read(b))
        return b.indices.fold(0L) { value, i -> value or ((b[i].toLong() and 255L) shl (i * 8)) }
    }
}

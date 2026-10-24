package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Loopback-only peer-message wire fixture; not a Soulseek P2P session. */
class A0SoulseekPeerWireProbeTest {
    @Test(timeout = 12000)
    fun peerInitializationAndDownloadNegotiationCrossActualLoopbackTcp() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 4000
                        val init = ByteArrayInputStream(readWire(socket.getInputStream()))
                        assertEquals(1, init.read()) // Initialisation.PeerInit: ONE-byte message code
                        assertEquals("a0-local-only", readString(init))
                        assertEquals("P", readString(init))
                        assertEquals(71, readInt(init))
                        assertEquals(-1, init.read())

                        val request = ByteArrayInputStream(readWire(socket.getInputStream()))
                        assertEquals(40, readInt(request)) // Peer.TransferRequest is FOUR-byte code
                        assertEquals(0, readInt(request)) // TransferDirection.Download
                        assertEquals(73, readInt(request))
                        assertEquals("Owned\\demo.wav", readString(request))
                        assertEquals(4096L, readLong(request))
                        assertEquals(-1, request.read())
                        socket.getOutputStream().write(transferResponse(token = 73, allowed = true, size = 4096L))
                        socket.getOutputStream().flush()
                        true
                    }
                }
                Socket("127.0.0.1", server.localPort).use { client ->
                    client.soTimeout = 4000
                    client.getOutputStream().write(A0SoulseekPeerWireProbe.peerInit("a0-local-only", 71))
                    client.getOutputStream().write(A0SoulseekPeerWireProbe.downloadRequest(
                        "Owned\\demo.wav", 73, 4096L
                    ))
                    client.getOutputStream().flush()
                    val decision = A0SoulseekPeerWireProbe.readTransferDecision(
                        client.getInputStream(), expectedToken = 73, advertisedByteCount = 4096L
                    )
                    assertTrue(decision.allowed)
                    assertEquals(4096L, decision.peerReportedByteCount)
                }
                assertTrue(peer.get(7, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun uploadDirectionIsNeverConstructedForExplicitDownload() {
        val bytes = A0SoulseekPeerWireProbe.downloadRequest("owned.wav", 3, 44L)
        val frame = ByteArrayInputStream(bytes.copyOfRange(4, bytes.size))
        assertEquals(40, readInt(frame))
        assertEquals(0, readInt(frame))
        assertEquals(3, readInt(frame))
        assertEquals("owned.wav", readString(frame))
        assertEquals(44L, readLong(frame))
        assertEquals(-1, frame.read())
    }

    @Test
    fun deniedTransferReasonAndPeerMetadataStayUntrustedAndNotPropagated() {
        val response = transferResponse(41, allowed = false, reason = "secret-peer-reason/path/credential")
        val result = A0SoulseekPeerWireProbe.readTransferDecision(ByteArrayInputStream(response), 41, 8192)
        assertFalse(result.allowed)
        assertEquals(null, result.peerReportedByteCount)
        assertFalse(result.toString().contains("secret-peer-reason"))
    }

    @Test
    fun allowedResponseWithoutSizeRemainsUnknownNotFabricated() {
        val bytes = transferResponse(51, allowed = true)
        val decision = A0SoulseekPeerWireProbe.readTransferDecision(ByteArrayInputStream(bytes), 51, 4000L)
        assertTrue(decision.allowed)
        assertEquals(null, decision.peerReportedByteCount)
    }

    @Test
    fun mismatchedTokenWrongCodeAndConflictingSizeFailClosed() {
        assertProtocolRejected {
            A0SoulseekPeerWireProbe.readTransferDecision(
                ByteArrayInputStream(transferResponse(80, true, 500L)), 81, 500L
            )
        }
        assertProtocolRejected {
            A0SoulseekPeerWireProbe.readTransferDecision(
                ByteArrayInputStream(transferResponse(80, true, 600L)), 80, 500L
            )
        }
        assertProtocolRejected {
            val wrong = transferResponse(80, true, 500L).also { it[4] = 40 }
            A0SoulseekPeerWireProbe.readTransferDecision(ByteArrayInputStream(wrong), 80, 500L)
        }
    }

    @Test
    fun fragmentedTruncatedOversizedAndTrailingPeerFramesFailClosed() {
        val good = transferResponse(6, true, 1200L)
        val fragmented = object : InputStream() {
            private val source = ByteArrayInputStream(good)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 1))
        }
        assertEquals(1200L, A0SoulseekPeerWireProbe.readTransferDecision(fragmented, 6, 1200L).peerReportedByteCount)
        assertProtocolRejected {
            A0SoulseekPeerWireProbe.readTransferDecision(ByteArrayInputStream(good.copyOf(good.size - 1)), 6, 1200L)
        }
        val tooBig = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x7f, 0)
        assertProtocolRejected {
            A0SoulseekPeerWireProbe.readTransferDecision(ByteArrayInputStream(tooBig), 6, 1200L)
        }
        val trailing = good.copyOf(good.size + 1).also { it[0] = (it.size - 4).toByte() }
        assertProtocolRejected {
            A0SoulseekPeerWireProbe.readTransferDecision(ByteArrayInputStream(trailing), 6, 1200L)
        }
    }

    @Test
    fun invalidTokensSizesAndRemoteNamesAreRejectedBeforeConstructingMessages() {
        for (name in listOf("", "\u0000bad", "x".repeat(2048))) {
            try {
                A0SoulseekPeerWireProbe.downloadRequest(name, 3, 1024L)
                fail("Invalid peer filename should be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        for (token in listOf(0, -1)) {
            try { A0SoulseekPeerWireProbe.peerInit("local-fixture", token); fail("invalid token") }
            catch (_: IllegalArgumentException) { }
        }
        try { A0SoulseekPeerWireProbe.downloadRequest("a.wav", 1, Long.MAX_VALUE); fail("unbounded size") }
        catch (_: IllegalArgumentException) { }
        assertProtocolRejected {
            A0SoulseekPeerWireProbe.readTransferDecision(ByteArrayInputStream(transferResponse(1, true, 3)), 0, 3)
        }
    }

    private fun transferResponse(token: Int, allowed: Boolean, size: Long? = null, reason: String = "denied"): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(i32(41))
            write(i32(token))
            write(if (allowed) 1 else 0)
            if (allowed) { if (size != null) write(i64(size)) } else write(str(reason))
        }.toByteArray()
        return i32(payload.size) + payload
    }
    private fun str(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return i32(bytes.size) + bytes
    }
    private fun i32(n: Int): ByteArray = ByteArray(4) { index -> (n ushr (index * 8)).toByte() }
    private fun i64(n: Long): ByteArray = ByteArray(8) { index -> (n ushr (index * 8)).toByte() }
    private fun readInt(input: InputStream): Int {
        val b = readExactly(input, 4)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun readLong(input: InputStream): Long = readExactly(input, 8)
        .foldIndexed(0L) { index, accumulator, byte -> accumulator or ((byte.toLong() and 255) shl (index * 8)) }
    private fun readString(input: InputStream): String {
        val count = readInt(input)
        require(count in 0..1024)
        return String(readExactly(input, count), Charsets.UTF_8)
    }
    private fun readWire(input: InputStream): ByteArray {
        val count = readInt(input)
        require(count in 4..2048)
        return readExactly(input, count)
    }
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(bytes, offset, count - offset)
            if (n < 0) throw EOFException("incomplete local fixture")
            offset += n
        }
        return bytes
    }
    private fun assertProtocolRejected(body: () -> Unit) {
        try { body(); fail("Protocol mismatch must be rejected") }
        catch (_: IOException) { }
        catch (_: IllegalArgumentException) { }
    }
}

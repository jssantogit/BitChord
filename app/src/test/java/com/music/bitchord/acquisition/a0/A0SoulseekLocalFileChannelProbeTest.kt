package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A0 test-only direct transfer data socket, not an actual Soulseek peer.
 * Expected SHA-256 is computed by the test owner, not authenticated by Soulseek.
 */
class A0SoulseekLocalFileChannelProbeTest {
    @get:Rule val files = TemporaryFolder()

    @Test(timeout = 15000)
    fun fileChannelUsesSeparateFConnectionRawTokenAndExactSyntheticBytes() {
        val data = ownedWav()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val fakePeer = pool.submit<Boolean> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 4000
                        val init = ByteArrayInputStream(readFrame(socket.getInputStream()))
                        assertEquals(1, init.read()) // ONE-byte PeerInit code
                        assertEquals("test-owner", readString(init))
                        assertEquals("F", readString(init)) // separate raw file socket, not P
                        assertEquals(207, readInt(init)) // connection token
                        assertEquals(-1, init.read())
                        assertEquals(511, readInt(socket.getInputStream())) // raw transfer token, UNFRAMED
                        socket.getOutputStream().write(data.copyOfRange(0, 9))
                        socket.getOutputStream().write(data.copyOfRange(9, data.size))
                        socket.getOutputStream().flush()
                        true
                    }
                }
                val result = A0SoulseekLocalFileChannelProbe.receiveFixture(
                    port = listener.localPort,
                    username = "test-owner",
                    connectionToken = 207,
                    transferToken = 511,
                    expectedBytes = data.size.toLong(),
                    trustedSha256Hex = sha256(data),
                    appPrivateDirectory = files.root
                )
                assertTrue(fakePeer.get(8, TimeUnit.SECONDS))
                assertEquals(data.size.toLong(), result.byteCount)
                assertEquals("WAV_HEADER_ONLY", result.containerHint)
                assertArrayEquals(data, result.file.readBytes())
                assertTrue(result.file.name.endsWith(".verified"))
                assertFalse(files.root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test(timeout = 15000)
    fun incorrectTrustedDigestCausesNoCommitOrPartialFile() {
        val data = ownedWav()
        withFakeSender(data) { port ->
            assertIOException {
                A0SoulseekLocalFileChannelProbe.receiveFixture(
                    port, "test-owner", 207, 511, data.size.toLong(), "0".repeat(64), files.root
                )
            }
        }
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    @Test(timeout = 15000)
    fun disconnectedFileSocketDoesNotPromotePartialAudio() {
        val data = ownedWav()
        withFakeSender(data.copyOf(data.size / 2)) { port ->
            assertIOException {
                A0SoulseekLocalFileChannelProbe.receiveFixture(
                    port, "test-owner", 207, 511, data.size.toLong(), sha256(data), files.root
                )
            }
        }
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun invalidInputsAreRejectedWithoutOpeningASocket() {
        assertIllegalArgument { A0SoulseekLocalFileChannelProbe.receiveFixture(0, "test", 1, 2, 300L, "0".repeat(64), files.root) }
        assertIllegalArgument { A0SoulseekLocalFileChannelProbe.receiveFixture(80, "", 1, 2, 300L, "0".repeat(64), files.root) }
        assertIllegalArgument { A0SoulseekLocalFileChannelProbe.receiveFixture(80, "test", -1, 2, 300L, "0".repeat(64), files.root) }
        assertIllegalArgument { A0SoulseekLocalFileChannelProbe.receiveFixture(80, "test", 1, 0, 300L, "0".repeat(64), files.root) }
        assertIllegalArgument { A0SoulseekLocalFileChannelProbe.receiveFixture(80, "test", 1, 2, Long.MAX_VALUE, "0".repeat(64), files.root) }
        assertIllegalArgument { A0SoulseekLocalFileChannelProbe.receiveFixture(80, "test", 1, 2, 300L, "wrong", files.root) }
        assertTrue(files.root.listFiles().isNullOrEmpty())
    }

    private fun withFakeSender(data: ByteArray, block: (Int) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val server = pool.submit<Boolean> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 4000
                        readFrame(peer.getInputStream())
                        readInt(peer.getInputStream())
                        peer.getOutputStream().write(data)
                        peer.getOutputStream().flush()
                        true
                    }
                }
                block(listener.localPort)
                assertTrue(server.get(8, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }
    private fun ownedWav(): ByteArray {
        val pcm = ByteArray(512) { 0 }
        val head = ByteArray(44)
        fun marker(i: Int, name: String) = name.toByteArray(Charsets.US_ASCII).copyInto(head, i)
        fun int32(i: Int, v: Int) { repeat(4) { head[i + it] = (v ushr (it * 8)).toByte() } }
        fun int16(i: Int, v: Int) { repeat(2) { head[i + it] = (v ushr (it * 8)).toByte() } }
        marker(0, "RIFF"); int32(4, pcm.size + 36); marker(8, "WAVE")
        marker(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, 8000); int32(28, 16000); int16(32, 2); int16(34, 16)
        marker(36, "data"); int32(40, pcm.size)
        return head + pcm
    }
    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun readFrame(stream: InputStream): ByteArray {
        val size = readInt(stream)
        require(size in 4..2048)
        return readExactly(stream, size)
    }
    private fun readString(stream: InputStream): String {
        val size = readInt(stream)
        require(size in 0..1024)
        return String(readExactly(stream, size), Charsets.UTF_8)
    }
    private fun readInt(stream: InputStream): Int {
        val b = readExactly(stream, 4)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun readExactly(stream: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = stream.read(bytes, offset, count - offset)
            if (n < 0) throw EOFException("Incomplete local test protocol")
            offset += n
        }
        return bytes
    }
    private fun assertIOException(work: () -> Unit) {
        try { work(); fail("A0 raw file fixture must fail closed") } catch (_: IOException) {}
    }
    private fun assertIllegalArgument(work: () -> Unit) {
        try { work(); fail("A0 invalid input must fail before I/O") } catch (_: IllegalArgumentException) {}
    }
}
